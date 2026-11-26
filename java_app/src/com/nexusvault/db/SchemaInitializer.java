package com.nexusvault.db;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Initializes the NexusVault relational schema, secondary indexes, FTS5 virtual table,
 * and seeds realistic starter workspace data on first launch.
 */
public final class SchemaInitializer {

    private SchemaInitializer() {}

    public static void initialize(DbmsClient db) {
        // Enable Write-Ahead Logging for concurrent reads & crash safety
        db.execute("PRAGMA journal_mode = WAL;");

        // 1. Projects table
        db.execute("""
            CREATE TABLE projects (
                id INT PRIMARY KEY,
                name VARCHAR(120) NOT NULL,
                description TEXT,
                color VARCHAR(24),
                created_at VARCHAR(32)
            );
            """);

        // 2. Tasks table with Foreign Key to projects
        db.execute("""
            CREATE TABLE tasks (
                id INT PRIMARY KEY,
                project_id INT,
                title VARCHAR(180) NOT NULL,
                description TEXT,
                status VARCHAR(24),
                priority VARCHAR(16),
                due_date VARCHAR(24),
                created_at VARCHAR(32)
            );
            """);
        db.execute("CREATE INDEX idx_tasks_project ON tasks(project_id);");
        db.execute("CREATE INDEX idx_tasks_status ON tasks(status);");

        // 3. Full-Text Search (FTS5) Knowledge Base table
        db.execute("CREATE VIRTUAL TABLE notes_fts USING fts5(title, body);");

        // 4. Ephemeral Vault table (demonstrates native row-level TTL `EXPIRES <seconds>`)
        db.execute("""
            CREATE TABLE scratchpad (
                id INT PRIMARY KEY,
                label VARCHAR(120) NOT NULL,
                content TEXT,
                ttl_seconds INT,
                expires_at VARCHAR(32)
            );
            """);

        seedIfEmpty(db);
    }

    private static void seedIfEmpty(DbmsClient db) {
        QueryResult check = db.execute("SELECT id FROM projects LIMIT 1;");
        if (check.isOk() && !check.rows().isEmpty()) {
            return;
        }

        String today = LocalDate.now().toString();
        String d3 = LocalDate.now().plusDays(3).toString();
        String d7 = LocalDate.now().plusDays(7).toString();
        String d14 = LocalDate.now().plusDays(14).toString();

        // Seed Projects
        db.execute("INSERT INTO projects VALUES (1, 'Custom C DBMS Core', 'B+Tree storage, WAL, MVCC, and VDBE engine improvements', '#6366f1', '" + today + "');");
        db.execute("INSERT INTO projects VALUES (2, 'Java PBL Showcase', 'NexusVault workspace built in pure Java 21 on top of our C DBMS', '#10b981', '" + today + "');");
        db.execute("INSERT INTO projects VALUES (3, 'University Coursework', 'Algorithms, Operating Systems, and Database Systems labs', '#f59e0b', '" + today + "');");

        // Seed Tasks
        db.execute("INSERT INTO tasks VALUES (1, 1, 'Verify B+Tree split byte balancing', 'Stress test variable-length cells across leaf splits and merges', 'DONE', 'HIGH', '" + today + "', '" + today + "');");
        db.execute("INSERT INTO tasks VALUES (2, 1, 'Benchmark WAL checkpoint throughput', 'Measure write latency under concurrent TCP & HTTP clients', 'IN_PROGRESS', 'HIGH', '" + d3 + "', '" + today + "');");
        db.execute("INSERT INTO tasks VALUES (3, 2, 'Demo FTS5 full-text search in Viva', 'Show instant MATCH queries across knowledge base snippets', 'TODO', 'HIGH', '" + d3 + "', '" + today + "');");
        db.execute("INSERT INTO tasks VALUES (4, 2, 'Present TTL self-destructing vault', 'Insert a 30-second expiring secret and watch the C engine reap it automatically', 'IN_PROGRESS', 'MEDIUM', '" + d7 + "', '" + today + "');");
        db.execute("INSERT INTO tasks VALUES (5, 3, 'Prepare ER diagram & architecture slides', 'Document Java 21 HttpServer -> DbmsClient -> C db_server -> B+Tree flow', 'TODO', 'MEDIUM', '" + d7 + "', '" + today + "');");
        db.execute("INSERT INTO tasks VALUES (6, 3, 'Review MVCC snapshot isolation notes', 'Walk through xmin/xmax visibility rules and shadow paging', 'TODO', 'LOW', '" + d14 + "', '" + today + "');");

        // Seed FTS5 Knowledge Base Notes
        db.execute("INSERT INTO notes_fts VALUES (1, 'B+Tree Storage & Slotted Pages', 'Our custom C DBMS stores rows inside 4KB slotted leaf pages with overflow chains for large text payloads. Leaf splits balance cumulative byte size to prevent page overflow.');");
        db.execute("INSERT INTO notes_fts VALUES (2, 'WAL Mode & MVCC Concurrency', 'Write-Ahead Logging appends CRC32-checksummed frames to the WAL file. Readers register snapshot XIDs so concurrent writers never block analytical scans.');");
        db.execute("INSERT INTO notes_fts VALUES (3, 'Java 21 Zero-Dependency Stack', 'NexusVault uses java.net.http.HttpClient, com.sun.net.httpserver.HttpServer, and raw TCP sockets for LISTEN/NOTIFY PubSub with zero Maven or external JARs.');");
        db.execute("INSERT INTO notes_fts VALUES (4, 'Native Row-Level TTL Expiration', 'Rows inserted with INSERT INTO scratchpad VALUES (...) EXPIRES 60 automatically vanish from queries once their Unix expiration timestamp passes.');");

        // Seed one Ephemeral Scratchpad item (expires in 15 minutes so it is visible on first demo)
        long exp = Instant.now().getEpochSecond() + 900;
        db.execute("INSERT INTO scratchpad VALUES (1, 'Demo Presentation Checklist', '1. Create a task  2. Search FTS5 for MVCC  3. Create a 15s TTL secret  4. Open Live SQL Inspector', 900, '" + exp + "') EXPIRES 900;");
    }
}
