"""Python Vector Database driver backed by the custom C DBMS (libdbms.so)."""

import ctypes
import math
import os
import threading
import time
from pathlib import Path
from typing import Dict, List, Optional, Tuple


DBMS_OK = 0
DBMS_ROW = 100
DBMS_DONE = 101


def format_vector(vec: List[float], decimals: int = 4) -> str:
    return "[" + ",".join(f"{x:.{decimals}f}" for x in vec) + "]"


def parse_vector(vec_str: str) -> List[float]:
    s = vec_str.strip()
    if s.startswith("["):
        s = s[1:]
    if s.endswith("]"):
        s = s[:-1]
    if not s:
        return []
    return [float(x) for x in s.split(",") if x.strip()]


def cosine_sim_py(a: List[float], b: List[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    if na == 0.0 or nb == 0.0:
        return 0.0
    return dot / (na * nb)


def l2_dist_py(a: List[float], b: List[float]) -> float:
    return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))


def sanitize_sql(text: str) -> str:
    return (
        text.replace("'", "\u2019")
        .replace("\r", " ")
        .replace("\n", " ")
        .replace("\0", "")
        .strip()
    )


class DbmsVectorStore:
    """In-process Vector Store wrapping libdbms.so via ctypes."""

    def __init__(self, db_path: str, dim: int = 768, lib_path: Optional[str] = None):
        self.db_path = db_path
        self.dim = dim
        self._lock = threading.Lock()
        self._lib = self._load_lib(lib_path)
        self._db = ctypes.c_void_p()

        rc = self._lib.dbms_open(db_path.encode("utf-8"), ctypes.byref(self._db))
        if rc != DBMS_OK:
            raise RuntimeError(f"Failed to open DBMS at {db_path} (rc={rc})")

        self._init_schema()

    @staticmethod
    def _load_lib(lib_path: Optional[str]) -> ctypes.CDLL:
        candidates = [
            lib_path,
            str(Path(__file__).resolve().parent.parent / "libdbms.so"),
            "./libdbms.so",
            "../libdbms.so",
        ]
        resolved = next((p for p in candidates if p and os.path.exists(p)), None)
        if not resolved:
            raise FileNotFoundError("libdbms.so not found. Run `make` in the DBMS root directory.")

        lib = ctypes.CDLL(resolved)
        lib.dbms_open.argtypes = [ctypes.c_char_p, ctypes.POINTER(ctypes.c_void_p)]
        lib.dbms_open.restype = ctypes.c_int
        lib.dbms_close.argtypes = [ctypes.c_void_p]
        lib.dbms_close.restype = ctypes.c_int
        lib.dbms_prepare_v2.argtypes = [
            ctypes.c_void_p,
            ctypes.c_char_p,
            ctypes.c_int,
            ctypes.POINTER(ctypes.c_void_p),
            ctypes.c_void_p,
        ]
        lib.dbms_prepare_v2.restype = ctypes.c_int
        lib.dbms_step.argtypes = [ctypes.c_void_p]
        lib.dbms_step.restype = ctypes.c_int
        lib.dbms_finalize.argtypes = [ctypes.c_void_p]
        lib.dbms_finalize.restype = ctypes.c_int
        lib.dbms_column_count.argtypes = [ctypes.c_void_p]
        lib.dbms_column_count.restype = ctypes.c_int
        lib.dbms_column_text.argtypes = [ctypes.c_void_p, ctypes.c_int]
        lib.dbms_column_text.restype = ctypes.c_char_p
        lib.dbms_errmsg.argtypes = [ctypes.c_void_p]
        lib.dbms_errmsg.restype = ctypes.c_char_p
        return lib

    def close(self):
        with self._lock:
            if self._db and self._db.value:
                self._lib.dbms_close(self._db)
                self._db = ctypes.c_void_p()

    def execute(self, sql: str) -> Tuple[List[List[str]], float]:
        with self._lock:
            t0 = time.perf_counter()
            stmt = ctypes.c_void_p()
            raw = sql.encode("utf-8")
            rc = self._lib.dbms_prepare_v2(self._db, raw, len(raw), ctypes.byref(stmt), None)
            if rc != DBMS_OK:
                err = self._lib.dbms_errmsg(self._db)
                raise RuntimeError(f"Prepare error: {err.decode() if err else rc}")
            rows: List[List[str]] = []
            try:
                ncols = self._lib.dbms_column_count(stmt)
                while True:
                    sr = self._lib.dbms_step(stmt)
                    if sr == DBMS_ROW:
                        row = []
                        for c in range(ncols):
                            val = self._lib.dbms_column_text(stmt, c)
                            row.append(val.decode("utf-8", errors="replace") if val else "")
                        rows.append(row)
                    elif sr == DBMS_DONE:
                        break
                    else:
                        err = self._lib.dbms_errmsg(self._db)
                        raise RuntimeError(f"Step error ({sr}): {err.decode() if err else sr}")
            finally:
                self._lib.dbms_finalize(stmt)
            ms = (time.perf_counter() - t0) * 1000.0
            return rows, ms

    def _init_schema(self):
        try:
            self.execute("PRAGMA journal_mode = WAL;")
        except Exception:
            pass
        try:
            self.execute(
                f"CREATE TABLE rag_chunks ("
                f"id INT PRIMARY KEY, "
                f"source TEXT, "
                f"chunk_idx INT, "
                f"content TEXT, "
                f"embedding VECTOR({self.dim})"
                f");"
            )
        except Exception:
            # Table already exists
            pass

    def count_chunks(self) -> int:
        rows, _ = self.execute("SELECT id FROM rag_chunks;")
        return len(rows)

    def next_id(self) -> int:
        rows, _ = self.execute("SELECT id FROM rag_chunks ORDER BY id DESC LIMIT 1;")
        if not rows:
            return 1
        try:
            return int(rows[0][0]) + 1
        except ValueError:
            return 1

    def insert_chunk(self, chunk_id: int, source: str, chunk_idx: int, content: str, embedding: List[float]) -> float:
        safe_src = sanitize_sql(source)[:180]
        safe_content = sanitize_sql(content)[:1800]
        vec_str = format_vector(embedding)
        sql = (
            f"INSERT INTO rag_chunks VALUES ("
            f"{chunk_id}, '{safe_src}', {chunk_idx}, '{safe_content}', '{vec_str}'"
            f");"
        )
        _, ms = self.execute(sql)
        return ms

    def search_knn(
        self,
        query_embedding: List[float],
        top_k: int = 4,
        metric: str = "cosine",
    ) -> Tuple[List[Dict], float]:
        vec_str = format_vector(query_embedding)
        if metric == "l2":
            order_expr = f"l2_distance(embedding, '{vec_str}') ASC"
        else:
            order_expr = f"cosine_similarity(embedding, '{vec_str}') DESC"

        sql = (
            f"SELECT id, source, chunk_idx, content, embedding "
            f"FROM rag_chunks "
            f"ORDER BY {order_expr} "
            f"LIMIT {top_k};"
        )
        rows, ms = self.execute(sql)
        results = []
        for r in rows:
            emb = parse_vector(r[4]) if len(r) > 4 else []
            score = cosine_sim_py(query_embedding, emb) if emb else 0.0
            dist = l2_dist_py(query_embedding, emb) if emb else 0.0
            results.append(
                {
                    "id": int(r[0]),
                    "source": r[1],
                    "chunk_idx": int(r[2]),
                    "content": r[3],
                    "cosine_similarity": round(score, 4),
                    "l2_distance": round(dist, 4),
                }
            )
        return results, ms

    def list_sources(self) -> List[Dict]:
        rows, _ = self.execute("SELECT id, source FROM rag_chunks ORDER BY id ASC;")
        counts: Dict[str, int] = {}
        for r in rows:
            counts[r[1]] = counts.get(r[1], 0) + 1
        return [{"source": k, "chunks": v} for k, v in counts.items()]

    def clear_all(self):
        self.execute("DELETE FROM rag_chunks;")

    def db_size_kb(self) -> float:
        total = 0
        for suffix in ("", "-wal", "-journal"):
            p = self.db_path + suffix
            if os.path.exists(p):
                total += os.path.getsize(p)
        return round(total / 1024.0, 2)
