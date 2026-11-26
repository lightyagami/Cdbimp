"""End-to-end RAG Engine combining the custom C DBMS Vector Store and Ollama."""

import os
from pathlib import Path
from typing import Dict, List, Optional

from dbms_vector_store import DbmsVectorStore
from ollama_client import OllamaClient


def chunk_text(text: str, chunk_size: int = 550, overlap: int = 90) -> List[str]:
    """Splits text into overlapping character chunks aligned on whitespace boundaries."""
    cleaned = " ".join(text.split())
    if not cleaned:
        return []
    chunks: List[str] = []
    start = 0
    n = len(cleaned)
    while start < n:
        end = min(n, start + chunk_size)
        if end < n:
            space = cleaned.rfind(" ", start + chunk_size // 2, end)
            if space != -1:
                end = space
        piece = cleaned[start:end].strip()
        if len(piece) >= 40:
            chunks.append(piece)
        if end >= n:
            break
        start = max(end - overlap, start + 1)
    return chunks


class RagEngine:
    def __init__(
        self,
        db_path: str = "rag_knowledge.db",
        embed_model: str = "nomic-embed-text:latest",
        chat_model: str = "qwen3:8b",
    ):
        self.store = DbmsVectorStore(db_path=db_path, dim=768)
        self.ollama = OllamaClient(embed_model=embed_model, chat_model=chat_model)

    def ingest_text(self, source_name: str, text: str, chunk_size: int = 550, overlap: int = 90) -> Dict:
        chunks = chunk_text(text, chunk_size=chunk_size, overlap=overlap)
        if not chunks:
            return {"source": source_name, "chunks_added": 0, "embed_ms": 0.0, "insert_ms": 0.0}

        embeddings, embed_ms = self.ollama.embed_batch(chunks)
        next_id = self.store.next_id()
        total_insert_ms = 0.0
        for idx, (chunk, emb) in enumerate(zip(chunks, embeddings)):
            total_insert_ms += self.store.insert_chunk(next_id + idx, source_name, idx, chunk, emb)

        return {
            "source": source_name,
            "chunks_added": len(chunks),
            "embed_ms": round(embed_ms, 2),
            "insert_ms": round(total_insert_ms, 2),
        }

    def ingest_project_docs(self, root_dir: Optional[str] = None) -> List[Dict]:
        base = Path(root_dir) if root_dir else Path(__file__).resolve().parent.parent
        doc_files = [
            base / "DOCUMENTATION.md",
            base / "API_DESIGN.md",
            base / "Docs" / "architecture.md",
            base / "Docs" / "storage_format.md",
            base / "Docs" / "concurrency_and_transactions.md",
            base / "Docs" / "vdbe_bytecode.md",
        ]
        results = []
        for path in doc_files:
            if path.exists():
                content = path.read_text(encoding="utf-8", errors="ignore")
                rel = str(path.relative_to(base))
                results.append(self.ingest_text(rel, content))
        return results

    def ask(
        self,
        question: str,
        top_k: int = 4,
        metric: str = "cosine",
        model: Optional[str] = None,
    ) -> Dict:
        q_vec, embed_ms = self.ollama.embed(question)
        hits, knn_ms = self.store.search_knn(q_vec, top_k=top_k, metric=metric)

        context_blocks = []
        for i, h in enumerate(hits, 1):
            context_blocks.append(
                f"[{i}] Source: {h['source']} (chunk #{h['chunk_idx']}, cosine={h['cosine_similarity']})\n"
                f"{h['content']}"
            )
        context_text = "\n\n".join(context_blocks)

        system_prompt = (
            "You are a precise technical assistant answering questions strictly from the retrieved "
            "context chunks stored in a custom C vector database. Cite sources inline like [1], [2]. "
            "If the context does not contain the answer, say so clearly. Be concise."
        )
        user_prompt = (
            f"Retrieved Context:\n{context_text}\n\n"
            f"Question: {question}\n\n"
            f"Answer (with citations):"
        )

        answer, llm_ms, meta = self.ollama.generate(user_prompt, system=system_prompt, model=model)
        return {
            "question": question,
            "answer": answer,
            "sources": hits,
            "timing": {
                "embed_ms": round(embed_ms, 2),
                "knn_search_ms": round(knn_ms, 2),
                "llm_ms": round(llm_ms, 2),
                "total_ms": round(embed_ms + knn_ms + llm_ms, 2),
            },
            "meta": meta,
        }

    def close(self):
        self.store.close()
