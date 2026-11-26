# NexusVault — Java 21 Workspace & Knowledge Hub on a Custom C DBMS

**NexusVault** is a self-hosted personal workspace, Kanban task manager, full-text searchable knowledge base, and ephemeral TTL vault written in **pure Java 21** (zero external dependencies) backed by our custom C relational DBMS (`db_server`).

Designed for **Java Project-Based Learning (PBL)** to showcase both modern Java engineering and real database internals.

## Architecture

```
Browser (HTML/CSS/JS + SSE)
       │  REST JSON + Server-Sent Events
       ▼
Java 21 WebServer (com.sun.net.httpserver.HttpServer + Virtual Threads)
       ├── Repositories (ProjectRepository, TaskRepository, NoteRepository, ScratchpadRepository)
       ├── DbmsClient (java.net.http.HttpClient -> POST /query + QueryTelemetry)
       └── PubSubListener (Daemon TCP Socket -> LISTEN nexus_events)
       │  HTTP JSON + Raw TCP Pub/Sub
       ▼
Custom C DBMS Server (./db_server)
       └── B+Tree Slotted Pages · WAL Mode · MVCC · FTS5 · Native Row-Level TTL · VACUUM · BACKUP
```

## Features Showcased

1. **Workspace & Kanban Tasks** — Relational tables with secondary indexes, project progress tracking, priority filtering, and status transitions.
2. **Knowledge Base with Full-Text Search (FTS5)** — Uses `CREATE VIRTUAL TABLE notes_fts USING fts5(title, body)` and `WHERE body MATCH '...'`.
3. **Ephemeral Vault with Native Row-Level TTL** — Inserts self-destructing secrets via `INSERT INTO scratchpad VALUES (...) EXPIRES <seconds>` that the C engine automatically reaps when expired.
4. **Live TCP `LISTEN`/`NOTIFY` → Browser SSE** — A background Java daemon thread listens on TCP `LISTEN nexus_events;` and pushes real-time notifications to the UI via Server-Sent Events.
5. **Live SQL Console & Engine Inspector** — Displays every SQL query executed by Java with millisecond latency, supports ad-hoc SQL queries, and provides one-click `VACUUM` and `BACKUP DATABASE`.

## Quick Start (Windows — University PC / USB Portable)

Copy the `java_app/` folder to any Windows PC with **Java 17+** installed (no C compiler or Maven needed — a standalone static `bin/db_server.exe` is already bundled):

```bat
cd java_app
run.bat
```

It compiles the Java sources, starts `bin\db_server.exe`, and opens **http://127.0.0.1:8090** automatically.

## Quick Start (Linux / macOS)

```bash
cd java_app
chmod +x run.sh
./run.sh
```

Then open **http://127.0.0.1:8090** in your browser.

Custom ports/database file:
```bash
./run.sh <web_port> <db_port> <db_file>
# Example:
./run.sh 8090 8088 nexusvault.db
```
