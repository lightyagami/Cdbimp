package com.nexusvault.db;

import java.time.Instant;
import java.util.*;

/**
 * Thread-safe bounded ring buffer capturing live SQL telemetry for the Engine Inspector.
 */
public final class QueryTelemetry {

    public record Entry(
        long seq,
        String timestamp,
        String sql,
        int rowCount,
        double durationMs,
        boolean ok,
        String error
    ) {}

    private static final int MAX_ENTRIES = 60;
    private final Deque<Entry> entries = new ArrayDeque<>(MAX_ENTRIES);
    private long seq = 0;
    private long totalQueries = 0;
    private double totalDurationMs = 0.0;

    public synchronized void record(String sql, int rowCount, double durationMs, boolean ok, String error) {
        seq++;
        totalQueries++;
        totalDurationMs += durationMs;
        if (entries.size() >= MAX_ENTRIES) {
            entries.removeLast();
        }
        entries.addFirst(new Entry(
            seq,
            Instant.now().toString(),
            sql,
            rowCount,
            Math.round(durationMs * 100.0) / 100.0,
            ok,
            error == null ? "" : error
        ));
    }

    public synchronized List<Map<String, Object>> recentAsMaps() {
        List<Map<String, Object>> out = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", e.seq());
            m.put("timestamp", e.timestamp());
            m.put("sql", e.sql());
            m.put("rowCount", e.rowCount());
            m.put("durationMs", e.durationMs());
            m.put("ok", e.ok());
            m.put("error", e.error());
            out.add(m);
        }
        return out;
    }

    public synchronized Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalQueries", totalQueries);
        m.put("avgLatencyMs", totalQueries == 0 ? 0.0 : Math.round((totalDurationMs / totalQueries) * 100.0) / 100.0);
        return m;
    }
}
