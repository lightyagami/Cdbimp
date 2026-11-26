"""Comprehensive Vector Database & End-to-End RAG Performance Evaluation Suite."""

import json
import math
import os
import random
import statistics
import sys
import time
from pathlib import Path
from typing import Dict, List

sys.path.insert(0, str(Path(__file__).resolve().parent))

from dbms_vector_store import DbmsVectorStore, cosine_sim_py, l2_dist_py
from rag_engine import RagEngine


def percentile(values: List[float], p: float) -> float:
    if not values:
        return 0.0
    s = sorted(values)
    k = (len(s) - 1) * (p / 100.0)
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return round(s[int(k)], 2)
    return round(s[f] + (s[c] - s[f]) * (k - f), 2)


def random_unit_vec(dim: int, rng: random.Random) -> List[float]:
    v = [rng.gauss(0.0, 1.0) for _ in range(dim)]
    norm = math.sqrt(sum(x * x for x in v)) or 1.0
    return [x / norm for x in v]


def benchmark_vector_scaling(sizes=(50, 200, 500), dim: int = 768, num_queries: int = 15) -> List[Dict]:
    """Benchmarks ingestion throughput, storage size, KNN latency, and Recall@K vs float64 ground truth."""
    rng = random.Random(42)
    results = []

    for n in sizes:
        db_path = f"/tmp/rag_bench_{n}.db"
        for suffix in ("", "-wal", "-journal"):
            if os.path.exists(db_path + suffix):
                os.remove(db_path + suffix)

        store = DbmsVectorStore(db_path=db_path, dim=dim)
        corpus_vecs: Dict[int, List[float]] = {}

        t_ins_start = time.perf_counter()
        insert_latencies = []
        for i in range(1, n + 1):
            v = random_unit_vec(dim, rng)
            corpus_vecs[i] = v
            ms = store.insert_chunk(i, f"bench_doc_{i % 10}", i, f"Synthetic vector payload #{i}", v)
            insert_latencies.append(ms)
        total_ins_sec = time.perf_counter() - t_ins_start
        size_kb = store.db_size_kb()

        # Run cosine & l2 KNN queries and compare against exact float64 ground truth
        cos_latencies = []
        l2_latencies = []
        recall1_hits = 0
        recall3_hits = 0
        recall5_hits = 0
        mrr_sum = 0.0

        for _ in range(num_queries):
            q = random_unit_vec(dim, rng)

            # Exact float64 cosine ground truth
            gt_cos = sorted(corpus_vecs.keys(), key=lambda cid: cosine_sim_py(q, corpus_vecs[cid]), reverse=True)
            gt_top1 = gt_cos[0]
            gt_top5 = set(gt_cos[:5])

            hits_cos, ms_cos = store.search_knn(q, top_k=5, metric="cosine")
            cos_latencies.append(ms_cos)
            pred_ids = [h["id"] for h in hits_cos]

            if pred_ids and pred_ids[0] == gt_top1:
                recall1_hits += 1
            if gt_top1 in pred_ids[:3]:
                recall3_hits += 1
            recall5_hits += len(set(pred_ids[:5]) & gt_top5) / 5.0

            if gt_top1 in pred_ids:
                mrr_sum += 1.0 / (pred_ids.index(gt_top1) + 1)

            _, ms_l2 = store.search_knn(q, top_k=5, metric="l2")
            l2_latencies.append(ms_l2)

        store.close()
        for suffix in ("", "-wal", "-journal"):
            if os.path.exists(db_path + suffix):
                os.remove(db_path + suffix)

        avg_cos = statistics.mean(cos_latencies)
        results.append(
            {
                "num_vectors": n,
                "dim": dim,
                "insert_throughput_vps": round(n / total_ins_sec, 1),
                "insert_avg_ms": round(statistics.mean(insert_latencies), 2),
                "db_size_kb": size_kb,
                "cosine_p50_ms": percentile(cos_latencies, 50),
                "cosine_p95_ms": percentile(cos_latencies, 95),
                "cosine_qps": round(1000.0 / avg_cos, 1) if avg_cos > 0 else 0.0,
                "l2_p50_ms": percentile(l2_latencies, 50),
                "recall_at_1": round(recall1_hits / num_queries, 4),
                "recall_at_3": round(recall3_hits / num_queries, 4),
                "recall_at_5": round(recall5_hits / num_queries, 4),
                "mrr": round(mrr_sum / num_queries, 4),
            }
        )
        print(
            f"[Vector Bench N={n:3d}] "
            f"Insert: {results[-1]['insert_throughput_vps']:6.1f} vec/s | "
            f"Cosine p50: {results[-1]['cosine_p50_ms']:5.2f} ms ({results[-1]['cosine_qps']:5.1f} QPS) | "
            f"Recall@1: {results[-1]['recall_at_1']*100:.1f}% | Recall@5: {results[-1]['recall_at_5']*100:.1f}% | "
            f"DB: {size_kb:.1f} KB"
        )

    return results


def benchmark_e2e_rag(chat_model: str = "qwen3:8b") -> Dict:
    """Indexes real DBMS docs and evaluates end-to-end RAG retrieval & answer quality with Ollama."""
    db_path = "/tmp/rag_e2e_eval.db"
    for suffix in ("", "-wal", "-journal"):
        if os.path.exists(db_path + suffix):
            os.remove(db_path + suffix)

    rag = RagEngine(db_path=db_path, embed_model="nomic-embed-text:latest", chat_model=chat_model)
    ingest_stats = rag.ingest_project_docs()
    total_chunks = sum(x["chunks_added"] for x in ingest_stats)
    total_embed_ms = round(sum(x["embed_ms"] for x in ingest_stats), 1)
    total_insert_ms = round(sum(x["insert_ms"] for x in ingest_stats), 1)
    db_size_kb = rag.store.db_size_kb()
    print(
        f"\n[RAG Ingest] Indexed {total_chunks} chunks from {len(ingest_stats)} docs | "
        f"Ollama embed: {total_embed_ms} ms | C DBMS insert: {total_insert_ms} ms | DB: {db_size_kb} KB"
    )

    qa_suite = [
        {
            "question": "What is the page size used by the DBMS storage engine?",
            "expected_keywords": ["4096", "4kb", "4 kb"],
        },
        {
            "question": "How does WAL mode improve concurrency and crash recovery in this DBMS?",
            "expected_keywords": ["wal", "crc", "checkpoint", "write-ahead"],
        },
        {
            "question": "What C API functions are used to prepare and step through a SQL query?",
            "expected_keywords": ["dbms_prepare", "dbms_step", "dbms_finalize"],
        },
        {
            "question": "How does MVCC handle concurrent readers and writers?",
            "expected_keywords": ["snapshot", "shadow", "xmin", "xmax", "reader"],
        },
        {
            "question": "What is the VDBE in this database engine?",
            "expected_keywords": ["bytecode", "virtual", "register", "opcode"],
        },
    ]

    cases = []
    retrieval_hits = 0
    answer_hits = 0
    embed_times, knn_times, llm_times = [], [], []

    for item in qa_suite:
        q = item["question"]
        kws = item["expected_keywords"]
        res = rag.ask(q, top_k=4, metric="cosine", model=chat_model)

        retrieved_blob = " ".join(s["content"].lower() for s in res["sources"])
        answer_blob = res["answer"].lower()
        ret_hit = any(k in retrieved_blob for k in kws)
        ans_hit = any(k in answer_blob for k in kws)
        if ret_hit:
            retrieval_hits += 1
        if ans_hit:
            answer_hits += 1

        embed_times.append(res["timing"]["embed_ms"])
        knn_times.append(res["timing"]["knn_search_ms"])
        llm_times.append(res["timing"]["llm_ms"])

        cases.append(
            {
                "question": q,
                "retrieval_hit": ret_hit,
                "answer_hit": ans_hit,
                "top1_cosine": res["sources"][0]["cosine_similarity"] if res["sources"] else 0.0,
                "top1_source": res["sources"][0]["source"] if res["sources"] else "",
                "timing": res["timing"],
                "answer_preview": res["answer"][:260],
            }
        )
        print(
            f"  Q: {q[:52]:52s} | Ret:{'OK' if ret_hit else 'MISS'} | Ans:{'OK' if ans_hit else 'MISS'} | "
            f"Embed:{res['timing']['embed_ms']:5.1f}ms | KNN:{res['timing']['knn_search_ms']:4.1f}ms | "
            f"LLM:{res['timing']['llm_ms']:6.0f}ms | cos={cases[-1]['top1_cosine']}"
        )

    rag.close()
    for suffix in ("", "-wal", "-journal"):
        if os.path.exists(db_path + suffix):
            os.remove(db_path + suffix)

    return {
        "chat_model": chat_model,
        "embed_model": "nomic-embed-text:latest",
        "docs_indexed": len(ingest_stats),
        "chunks_indexed": total_chunks,
        "ingest_embed_ms": total_embed_ms,
        "ingest_dbms_insert_ms": total_insert_ms,
        "db_size_kb": db_size_kb,
        "retrieval_hit_rate": round(retrieval_hits / len(qa_suite), 4),
        "answer_accuracy_rate": round(answer_hits / len(qa_suite), 4),
        "avg_embed_ms": round(statistics.mean(embed_times), 2),
        "avg_knn_ms": round(statistics.mean(knn_times), 2),
        "avg_llm_ms": round(statistics.mean(llm_times), 2),
        "cases": cases,
    }


def write_markdown_report(scaling: List[Dict], rag_eval: Dict, out_path: Path):
    lines = [
        "# Custom C DBMS Vector Search & Ollama RAG Evaluation Report",
        "",
        f"- **Embedding Model:** `{rag_eval['embed_model']}` (768 dimensions)",
        f"- **LLM Model:** `{rag_eval['chat_model']}`",
        "- **Vector Engine:** Custom C DBMS (`libdbms.so` via `ctypes`, B+Tree slotted pages + overflow chains, WAL mode)",
        "",
        "## 1. Vector Database Scaling & Exactness Benchmark (768-dim)",
        "",
        "| Vectors (N) | Insert (vec/s) | Avg Insert (ms) | Cosine p50 (ms) | Cosine p95 (ms) | QPS | L2 p50 (ms) | Recall@1 | Recall@5 | MRR | DB Size (KB) |",
        "|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for r in scaling:
        lines.append(
            f"| {r['num_vectors']} | {r['insert_throughput_vps']} | {r['insert_avg_ms']} | "
            f"{r['cosine_p50_ms']} | {r['cosine_p95_ms']} | {r['cosine_qps']} | {r['l2_p50_ms']} | "
            f"{r['recall_at_1']*100:.1f}% | {r['recall_at_5']*100:.1f}% | {r['mrr']:.4f} | {r['db_size_kb']} |"
        )

    lines += [
        "",
        "## 2. End-to-End RAG Benchmark (Project Documentation Corpus)",
        "",
        f"- **Corpus:** {rag_eval['docs_indexed']} Markdown docs → **{rag_eval['chunks_indexed']} chunks** ({rag_eval['db_size_kb']} KB on disk)",
        f"- **Ingestion Time:** Ollama batch embed `{rag_eval['ingest_embed_ms']} ms` · C DBMS insert `{rag_eval['ingest_dbms_insert_ms']} ms`",
        f"- **Retrieval Hit Rate (Top-4):** **{rag_eval['retrieval_hit_rate']*100:.1f}%**",
        f"- **Grounded Answer Accuracy:** **{rag_eval['answer_accuracy_rate']*100:.1f}%**",
        f"- **Average Latency Breakdown:** Embed `{rag_eval['avg_embed_ms']} ms` · **C DBMS KNN `{rag_eval['avg_knn_ms']} ms`** · Ollama LLM `{rag_eval['avg_llm_ms']} ms`",
        "",
        "| Question | Retrieval | Answer | Top-1 Cosine | Embed (ms) | DBMS KNN (ms) | LLM (ms) |",
        "|---|:-:|:-:|---:|---:|---:|---:|",
    ]
    for c in rag_eval["cases"]:
        lines.append(
            f"| {c['question']} | {'PASS' if c['retrieval_hit'] else 'MISS'} | "
            f"{'PASS' if c['answer_hit'] else 'MISS'} | {c['top1_cosine']} | "
            f"{c['timing']['embed_ms']} | {c['timing']['knn_search_ms']} | {c['timing']['llm_ms']} |"
        )

    out_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    model = sys.argv[1] if len(sys.argv) > 1 else "qwen3:8b"
    print("=== 1. Custom C DBMS 768-dim Vector Scaling & Accuracy Benchmark ===")
    scaling = benchmark_vector_scaling(sizes=(50, 200, 500), dim=768, num_queries=15)

    print(f"\n=== 2. End-to-End RAG Benchmark (nomic-embed-text + {model}) ===")
    rag_eval = benchmark_e2e_rag(chat_model=model)

    out_dir = Path(__file__).resolve().parent
    (out_dir / "eval_results.json").write_text(
        json.dumps({"scaling": scaling, "rag": rag_eval}, indent=2), encoding="utf-8"
    )
    write_markdown_report(scaling, rag_eval, out_dir / "eval_report.md")
    print(f"\nSaved report to {out_dir / 'eval_report.md'} and {out_dir / 'eval_results.json'}")


if __name__ == "__main__":
    main()
