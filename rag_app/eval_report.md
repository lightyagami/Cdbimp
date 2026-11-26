# Custom C DBMS Vector Search & Ollama RAG Evaluation Report

- **Embedding Model:** `nomic-embed-text:latest` (768 dimensions)
- **LLM Model:** `qwen3:8b`
- **Vector Engine:** Custom C DBMS (`libdbms.so` via `ctypes`, B+Tree slotted pages + overflow chains, WAL mode)

## 1. Vector Database Scaling & Exactness Benchmark (768-dim)

| Vectors (N) | Insert (vec/s) | Avg Insert (ms) | Cosine p50 (ms) | Cosine p95 (ms) | QPS | L2 p50 (ms) | Recall@1 | Recall@5 | MRR | DB Size (KB) |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 50 | 656.0 | 0.77 | 5.94 | 6.65 | 165.2 | 5.83 | 100.0% | 100.0% | 1.0000 | 1211.06 |
| 200 | 426.0 | 1.67 | 24.69 | 29.23 | 39.8 | 24.26 | 93.3% | 100.0% | 0.9667 | 5174.14 |
| 500 | 261.9 | 3.21 | 56.08 | 63.81 | 17.3 | 56.09 | 100.0% | 100.0% | 1.0000 | 13031.92 |

## 2. End-to-End RAG Benchmark (Project Documentation Corpus)

- **Corpus:** 6 Markdown docs → **59 chunks** (1424.3 KB on disk)
- **Ingestion Time:** Ollama batch embed `7631.8 ms` · C DBMS insert `48.0 ms`
- **Retrieval Hit Rate (Top-4):** **100.0%**
- **Grounded Answer Accuracy:** **100.0%**
- **Average Latency Breakdown:** Embed `399.46 ms` · **C DBMS KNN `10.72 ms`** · Ollama LLM `14538.5 ms`

| Question | Retrieval | Answer | Top-1 Cosine | Embed (ms) | DBMS KNN (ms) | LLM (ms) |
|---|:-:|:-:|---:|---:|---:|---:|
| What is the page size used by the DBMS storage engine? | PASS | PASS | 0.7138 | 93.36 | 8.13 | 43070.82 |
| How does WAL mode improve concurrency and crash recovery in this DBMS? | PASS | PASS | 0.674 | 1623.29 | 18.59 | 6866.7 |
| What C API functions are used to prepare and step through a SQL query? | PASS | PASS | 0.6509 | 76.92 | 9.19 | 6057.79 |
| How does MVCC handle concurrent readers and writers? | PASS | PASS | 0.5859 | 125.94 | 8.87 | 8943.52 |
| What is the VDBE in this database engine? | PASS | PASS | 0.7797 | 77.77 | 8.84 | 7753.69 |
