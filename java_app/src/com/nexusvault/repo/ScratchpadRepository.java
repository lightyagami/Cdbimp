package com.nexusvault.repo;

import com.nexusvault.db.DbmsClient;
import com.nexusvault.db.QueryResult;
import com.nexusvault.model.ScratchItem;

import java.time.Instant;
import java.util.*;

/**
 * Data Access Object (Repository) for Ephemeral / Self-Destructing Vault Items.
 * Demonstrates the custom C DBMS's native row-level TTL (`INSERT ... EXPIRES <seconds>`).
 */
public final class ScratchpadRepository {

    private final DbmsClient db;
    private int idCounter = 100;

    public ScratchpadRepository(DbmsClient db) {
        this.db = db;
    }

    public List<ScratchItem> findActive() {
        QueryResult res = db.execute("SELECT id, label, content, ttl_seconds, expires_at FROM scratchpad ORDER BY id DESC;");
        List<ScratchItem> list = new ArrayList<>();
        if (!res.isOk()) return list;
        for (List<String> row : res.rows()) {
            if (row.size() >= 5) {
                list.add(new ScratchItem(
                    parseInt(row.get(0), 0),
                    nullSafe(row.get(1)),
                    nullSafe(row.get(2)),
                    parseInt(row.get(3), 60),
                    parseLong(row.get(4), Instant.now().getEpochSecond())
                ));
            }
        }
        return list;
    }

    public synchronized ScratchItem create(String label, String content, int ttlSeconds) {
        int clampedTtl = Math.max(5, Math.min(ttlSeconds, 86400));
        int nextId = nextId();
        long expiresAt = Instant.now().getEpochSecond() + clampedTtl;

        String sql = "INSERT INTO scratchpad VALUES ("
            + nextId + ", "
            + DbmsClient.sqlStr(label) + ", "
            + DbmsClient.sqlStr(content) + ", "
            + clampedTtl + ", "
            + DbmsClient.sqlStr(String.valueOf(expiresAt))
            + ") EXPIRES " + clampedTtl + ";";

        QueryResult res = db.execute(sql);
        if (!res.isOk()) {
            throw new IllegalStateException("Failed to create TTL scratch item: " + res.error());
        }
        db.notifyChannel("nexus_events", "TTL_SECRET_CREATED:" + label + " (" + clampedTtl + "s)");
        return new ScratchItem(nextId, DbmsClient.sanitizeSql(label), DbmsClient.sanitizeSql(content), clampedTtl, expiresAt);
    }

    public synchronized boolean delete(int id) {
        QueryResult res = db.execute("DELETE FROM scratchpad WHERE id = " + id + ";");
        if (res.isOk()) {
            db.notifyChannel("nexus_events", "TTL_SECRET_DELETED:#" + id);
            return true;
        }
        return false;
    }

    private int nextId() {
        QueryResult res = db.execute("SELECT id FROM scratchpad ORDER BY id DESC LIMIT 1;");
        if (res.isOk() && !res.rows().isEmpty()) {
            int maxDb = parseInt(res.rows().get(0).get(0), 0);
            idCounter = Math.max(idCounter, maxDb) + 1;
            return idCounter;
        }
        return ++idCounter;
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static long parseLong(String s, long def) {
        if (s == null) return def;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }
}
