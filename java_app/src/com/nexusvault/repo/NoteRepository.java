package com.nexusvault.repo;

import com.nexusvault.db.DbmsClient;
import com.nexusvault.db.QueryResult;
import com.nexusvault.model.Note;

import java.util.*;

/**
 * Data Access Object (Repository) for FTS5 Full-Text Search Knowledge Base Notes.
 * Uses the custom C DBMS's `CREATE VIRTUAL TABLE ... USING fts5(...)` and `MATCH` operator.
 */
public final class NoteRepository {

    private final DbmsClient db;

    public NoteRepository(DbmsClient db) {
        this.db = db;
    }

    public List<Note> findAllOrSearch(String query) {
        String sql;
        if (query != null && !query.isBlank()) {
            String q = DbmsClient.sqlStr(query.trim());
            sql = "SELECT id, title, body FROM notes_fts WHERE body MATCH " + q + " OR title MATCH " + q + " ORDER BY id DESC;";
        } else {
            sql = "SELECT id, title, body FROM notes_fts ORDER BY id DESC;";
        }

        QueryResult res = db.execute(sql);
        List<Note> list = new ArrayList<>();
        if (!res.isOk()) return list;
        for (List<String> row : res.rows()) {
            if (row.size() >= 3) {
                list.add(new Note(
                    parseInt(row.get(0), 0),
                    nullSafe(row.get(1)),
                    nullSafe(row.get(2))
                ));
            }
        }
        return list;
    }

    public synchronized Note create(String title, String body) {
        int nextId = nextId();
        String sql = "INSERT INTO notes_fts VALUES ("
            + nextId + ", "
            + DbmsClient.sqlStr(title) + ", "
            + DbmsClient.sqlStr(body) + ");";
        QueryResult res = db.execute(sql);
        if (!res.isOk()) {
            throw new IllegalStateException("Failed to create note: " + res.error());
        }
        db.notifyChannel("nexus_events", "NOTE_INDEXED:" + title);
        return new Note(nextId, DbmsClient.sanitizeSql(title), DbmsClient.sanitizeSql(body));
    }

    public synchronized boolean delete(int id) {
        QueryResult res = db.execute("DELETE FROM notes_fts WHERE id = " + id + ";");
        if (res.isOk()) {
            db.notifyChannel("nexus_events", "NOTE_DELETED:#" + id);
            return true;
        }
        return false;
    }

    private int nextId() {
        QueryResult res = db.execute("SELECT id FROM notes_fts ORDER BY id DESC LIMIT 1;");
        if (res.isOk() && !res.rows().isEmpty()) {
            return parseInt(res.rows().get(0).get(0), 0) + 1;
        }
        return 1;
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }
}
