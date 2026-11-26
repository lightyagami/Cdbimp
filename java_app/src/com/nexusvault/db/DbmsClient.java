package com.nexusvault.db;

import com.nexusvault.web.JsonUtil;

import java.io.*;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Pure Java 21 client driver for the custom C DBMS (`db_server`).
 * Uses HTTP POST /query for SQL execution and raw TCP sockets for LISTEN/NOTIFY Pub/Sub.
 */
public final class DbmsClient {

    private final String host;
    private final int port;
    private final HttpClient http;
    private final QueryTelemetry telemetry = new QueryTelemetry();

    public DbmsClient(String host, int port) {
        this.host = host;
        this.port = port;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    }

    public String getHost() { return host; }
    public int getPort() { return port; }
    public QueryTelemetry getTelemetry() { return telemetry; }

    public boolean isHealthy() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + port + "/health"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 && resp.body().contains("ok");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Executes a SQL statement against the custom C DBMS and records live telemetry.
     */
    @SuppressWarnings("unchecked")
    public QueryResult execute(String sql) {
        long startNs = System.nanoTime();
        String trimmed = sql.trim();
        if (!trimmed.endsWith(";")) {
            trimmed = trimmed + ";";
        }
        String payload = "{\"sql\":\"" + JsonUtil.escape(trimmed) + "\"}";

        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + port + "/query"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            double durationMs = (System.nanoTime() - startNs) / 1_000_000.0;
            Map<String, Object> parsed = JsonUtil.parseObject(resp.body());

            if (resp.statusCode() != 200 || parsed.containsKey("error")) {
                String err = JsonUtil.getString(parsed, "error", "HTTP " + resp.statusCode());
                telemetry.record(trimmed, 0, durationMs, false, err);
                return new QueryResult(trimmed, List.of(), List.of(), durationMs, err);
            }

            List<String> cols = new ArrayList<>();
            Object rawCols = parsed.get("columns");
            if (rawCols instanceof List<?> list) {
                for (Object c : list) cols.add(c == null ? "" : String.valueOf(c));
            }

            List<List<String>> rows = new ArrayList<>();
            Object rawRows = parsed.get("rows");
            if (rawRows instanceof List<?> rowList) {
                for (Object r : rowList) {
                    if (r instanceof List<?> cellList) {
                        List<String> row = new ArrayList<>(cellList.size());
                        for (Object cell : cellList) {
                            row.add(cell == null ? null : String.valueOf(cell));
                        }
                        rows.add(row);
                    }
                }
            }

            telemetry.record(trimmed, rows.size(), durationMs, true, "");
            return new QueryResult(trimmed, cols, rows, durationMs, null);
        } catch (Exception e) {
            double durationMs = (System.nanoTime() - startNs) / 1_000_000.0;
            telemetry.record(trimmed, 0, durationMs, false, e.getMessage());
            return new QueryResult(trimmed, List.of(), List.of(), durationMs, e.getMessage());
        }
    }

    /**
     * Publishes a real-time event via the custom C DBMS's native TCP `NOTIFY` command.
     */
    public void notifyChannel(String channel, String payload) {
        String safePayload = sanitizeSql(payload).replace("\n", " ");
        String cmd = "NOTIFY " + channel + " '" + safePayload + "';\n";
        try (Socket sock = new Socket(host, port)) {
            sock.setSoTimeout(1500);
            OutputStream out = sock.getOutputStream();
            out.write(cmd.getBytes(StandardCharsets.UTF_8));
            out.flush();
            // Read brief acknowledgement so server processes cleanly before close
            sock.getInputStream().read(new byte[256]);
        } catch (Exception ignored) {
            // Non-fatal if pub/sub notification cannot be delivered
        }
    }

    /**
     * Sanitizes a string for safe embedding in single-quoted SQL literals.
     * Replaces ASCII single quotes with typographic apostrophe to prevent SQL injection
     * and normalizes newlines/control characters.
     */
    public static String sanitizeSql(String input) {
        if (input == null) return "";
        return input
            .replace('\'', '\u2019')
            .replace('\r', ' ')
            .replace('\0', ' ')
            .trim();
    }

    public static String sqlStr(String input) {
        return "'" + sanitizeSql(input) + "'";
    }
}
