package com.nexusvault.web;

import com.nexusvault.db.*;
import com.nexusvault.model.*;
import com.nexusvault.repo.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Pure Java 21 REST + SSE + Static Web Server built on JDK's `com.sun.net.httpserver.HttpServer`.
 */
public final class WebServer {

    private final int port;
    private final Path webRoot;
    private final DbmsClient db;
    private final PubSubListener pubSub;
    private final ProjectRepository projects;
    private final TaskRepository tasks;
    private final NoteRepository notes;
    private final ScratchpadRepository scratchpad;
    private HttpServer server;

    public WebServer(int port, Path webRoot, DbmsClient db, PubSubListener pubSub) {
        this.port = port;
        this.webRoot = webRoot;
        this.db = db;
        this.pubSub = pubSub;
        this.projects = new ProjectRepository(db);
        this.tasks = new TaskRepository(db);
        this.notes = new NoteRepository(db);
        this.scratchpad = new ScratchpadRepository(db);
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/api/dashboard", this::handleDashboard);
        server.createContext("/api/projects", this::handleProjects);
        server.createContext("/api/tasks", this::handleTasks);
        server.createContext("/api/notes", this::handleNotes);
        server.createContext("/api/scratchpad", this::handleScratchpad);
        server.createContext("/api/telemetry", this::handleTelemetry);
        server.createContext("/api/sql", this::handleSqlConsole);
        server.createContext("/api/engine/vacuum", this::handleVacuum);
        server.createContext("/api/engine/backup", this::handleBackup);
        server.createContext("/api/events", this::handleSseEvents);
        server.createContext("/", this::handleStatic);

        server.start();
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private void handleDashboard(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "Method not allowed"));
            return;
        }
        List<Project> allProjects = projects.findAll();
        List<Task> allTasks = tasks.findAll(null, null);
        List<Note> allNotes = notes.findAllOrSearch(null);
        List<ScratchItem> activeScratch = scratchpad.findActive();

        int todo = 0, inProgress = 0, done = 0;
        int high = 0, medium = 0, low = 0;
        Map<Integer, Integer> tasksByProject = new HashMap<>();
        Map<Integer, Integer> doneByProject = new HashMap<>();

        for (Task t : allTasks) {
            switch (t.status()) {
                case "DONE" -> done++;
                case "IN_PROGRESS" -> inProgress++;
                default -> todo++;
            }
            switch (t.priority()) {
                case "HIGH" -> high++;
                case "LOW" -> low++;
                default -> medium++;
            }
            tasksByProject.merge(t.projectId(), 1, Integer::sum);
            if ("DONE".equals(t.status())) {
                doneByProject.merge(t.projectId(), 1, Integer::sum);
            }
        }

        List<Map<String, Object>> projectStats = new ArrayList<>();
        for (Project p : allProjects) {
            int totalP = tasksByProject.getOrDefault(p.id(), 0);
            int doneP = doneByProject.getOrDefault(p.id(), 0);
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", p.id());
            pm.put("name", p.name());
            pm.put("description", p.description());
            pm.put("color", p.color());
            pm.put("totalTasks", totalP);
            pm.put("doneTasks", doneP);
            pm.put("progressPct", totalP == 0 ? 0 : Math.round((doneP * 100.0) / totalP));
            projectStats.add(pm);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dbHealthy", db.isHealthy());
        body.put("dbEndpoint", db.getHost() + ":" + db.getPort());
        body.put("counts", Map.of(
            "projects", allProjects.size(),
            "tasks", allTasks.size(),
            "todo", todo,
            "inProgress", inProgress,
            "done", done,
            "notes", allNotes.size(),
            "scratchpad", activeScratch.size()
        ));
        body.put("priorities", Map.of("high", high, "medium", medium, "low", low));
        body.put("projects", projectStats);
        body.put("telemetry", db.getTelemetry().stats());
        sendJson(ex, 200, body);
    }

    private void handleProjects(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod().toUpperCase();
        String path = ex.getRequestURI().getPath();

        if ("GET".equals(method)) {
            List<Map<String, Object>> out = projects.findAll().stream().map(p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.id());
                m.put("name", p.name());
                m.put("description", p.description());
                m.put("color", p.color());
                m.put("createdAt", p.createdAt());
                return m;
            }).toList();
            sendJson(ex, 200, Map.of("projects", out));
            return;
        }

        if ("POST".equals(method)) {
            Map<String, Object> req = JsonUtil.parseObject(readBody(ex));
            String name = JsonUtil.getString(req, "name", "").trim();
            if (name.isEmpty()) {
                sendJson(ex, 400, Map.of("error", "Project name is required"));
                return;
            }
            String desc = JsonUtil.getString(req, "description", "");
            String color = JsonUtil.getString(req, "color", "#6366f1");
            Project created = projects.create(name, desc, color);
            sendJson(ex, 201, Map.of(
                "id", created.id(),
                "name", created.name(),
                "description", created.description(),
                "color", created.color(),
                "createdAt", created.createdAt()
            ));
            return;
        }

        if ("DELETE".equals(method)) {
            int id = extractTrailingId(path, "/api/projects/");
            if (id <= 0) {
                sendJson(ex, 400, Map.of("error", "Invalid project id"));
                return;
            }
            boolean ok = projects.delete(id);
            sendJson(ex, ok ? 200 : 400, Map.of("deleted", ok));
            return;
        }

        sendJson(ex, 405, Map.of("error", "Method not allowed"));
    }

    private void handleTasks(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod().toUpperCase();
        String path = ex.getRequestURI().getPath();

        if ("GET".equals(method)) {
            Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
            Integer projectId = q.containsKey("projectId") ? tryParseInt(q.get("projectId")) : null;
            String status = q.get("status");

            Map<Integer, Project> projMap = new HashMap<>();
            for (Project p : projects.findAll()) projMap.put(p.id(), p);

            List<Map<String, Object>> out = tasks.findAll(projectId, status).stream().map(t -> {
                Project p = projMap.get(t.projectId());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", t.id());
                m.put("projectId", t.projectId());
                m.put("projectName", p != null ? p.name() : "General");
                m.put("projectColor", p != null ? p.color() : "#6366f1");
                m.put("title", t.title());
                m.put("description", t.description());
                m.put("status", t.status());
                m.put("priority", t.priority());
                m.put("dueDate", t.dueDate());
                m.put("createdAt", t.createdAt());
                return m;
            }).toList();
            sendJson(ex, 200, Map.of("tasks", out));
            return;
        }

        if ("POST".equals(method)) {
            Map<String, Object> req = JsonUtil.parseObject(readBody(ex));
            String title = JsonUtil.getString(req, "title", "").trim();
            if (title.isEmpty()) {
                sendJson(ex, 400, Map.of("error", "Task title is required"));
                return;
            }
            int projectId = JsonUtil.getInt(req, "projectId", 1);
            String desc = JsonUtil.getString(req, "description", "");
            String priority = JsonUtil.getString(req, "priority", "MEDIUM");
            String dueDate = JsonUtil.getString(req, "dueDate", "");
            Task created = tasks.create(projectId, title, desc, priority, dueDate);
            sendJson(ex, 201, Map.of("id", created.id(), "title", created.title(), "status", created.status()));
            return;
        }

        if ("PATCH".equals(method) || "PUT".equals(method)) {
            String sub = path.replace("/api/tasks/", "");
            String[] parts = sub.split("/");
            int id = parts.length > 0 ? tryParseIntOrZero(parts[0]) : 0;
            if (id <= 0) {
                sendJson(ex, 400, Map.of("error", "Invalid task id"));
                return;
            }
            Map<String, Object> req = JsonUtil.parseObject(readBody(ex));
            String status = JsonUtil.getString(req, "status", "TODO");
            boolean ok = tasks.updateStatus(id, status);
            sendJson(ex, ok ? 200 : 400, Map.of("updated", ok, "status", status));
            return;
        }

        if ("DELETE".equals(method)) {
            int id = extractTrailingId(path, "/api/tasks/");
            if (id <= 0) {
                sendJson(ex, 400, Map.of("error", "Invalid task id"));
                return;
            }
            boolean ok = tasks.delete(id);
            sendJson(ex, ok ? 200 : 400, Map.of("deleted", ok));
            return;
        }

        sendJson(ex, 405, Map.of("error", "Method not allowed"));
    }

    private void handleNotes(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod().toUpperCase();
        String path = ex.getRequestURI().getPath();

        if ("GET".equals(method)) {
            Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
            String search = q.get("q");
            List<Map<String, Object>> out = notes.findAllOrSearch(search).stream().map(n -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", n.id());
                m.put("title", n.title());
                m.put("body", n.body());
                return m;
            }).toList();
            sendJson(ex, 200, Map.of("notes", out, "query", search == null ? "" : search));
            return;
        }

        if ("POST".equals(method)) {
            Map<String, Object> req = JsonUtil.parseObject(readBody(ex));
            String title = JsonUtil.getString(req, "title", "").trim();
            String body = JsonUtil.getString(req, "body", "").trim();
            if (title.isEmpty() || body.isEmpty()) {
                sendJson(ex, 400, Map.of("error", "Title and body are required"));
                return;
            }
            Note created = notes.create(title, body);
            sendJson(ex, 201, Map.of("id", created.id(), "title", created.title(), "body", created.body()));
            return;
        }

        if ("DELETE".equals(method)) {
            int id = extractTrailingId(path, "/api/notes/");
            if (id <= 0) {
                sendJson(ex, 400, Map.of("error", "Invalid note id"));
                return;
            }
            boolean ok = notes.delete(id);
            sendJson(ex, ok ? 200 : 400, Map.of("deleted", ok));
            return;
        }

        sendJson(ex, 405, Map.of("error", "Method not allowed"));
    }

    private void handleScratchpad(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod().toUpperCase();
        String path = ex.getRequestURI().getPath();

        if ("GET".equals(method)) {
            long now = System.currentTimeMillis() / 1000L;
            List<Map<String, Object>> out = scratchpad.findActive().stream().map(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.id());
                m.put("label", s.label());
                m.put("content", s.content());
                m.put("ttlSeconds", s.ttlSeconds());
                m.put("expiresAt", s.expiresAtEpochSec());
                m.put("remainingSeconds", Math.max(0, s.expiresAtEpochSec() - now));
                return m;
            }).toList();
            sendJson(ex, 200, Map.of("items", out));
            return;
        }

        if ("POST".equals(method)) {
            Map<String, Object> req = JsonUtil.parseObject(readBody(ex));
            String label = JsonUtil.getString(req, "label", "").trim();
            String content = JsonUtil.getString(req, "content", "").trim();
            int ttl = JsonUtil.getInt(req, "ttlSeconds", 30);
            if (label.isEmpty() || content.isEmpty()) {
                sendJson(ex, 400, Map.of("error", "Label and content are required"));
                return;
            }
            ScratchItem item = scratchpad.create(label, content, ttl);
            sendJson(ex, 201, Map.of("id", item.id(), "ttlSeconds", item.ttlSeconds(), "expiresAt", item.expiresAtEpochSec()));
            return;
        }

        if ("DELETE".equals(method)) {
            int id = extractTrailingId(path, "/api/scratchpad/");
            if (id <= 0) {
                sendJson(ex, 400, Map.of("error", "Invalid item id"));
                return;
            }
            boolean ok = scratchpad.delete(id);
            sendJson(ex, ok ? 200 : 400, Map.of("deleted", ok));
            return;
        }

        sendJson(ex, 405, Map.of("error", "Method not allowed"));
    }

    private void handleTelemetry(HttpExchange ex) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stats", db.getTelemetry().stats());
        body.put("queries", db.getTelemetry().recentAsMaps());
        sendJson(ex, 200, body);
    }

    private void handleSqlConsole(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "Method not allowed"));
            return;
        }
        Map<String, Object> req = JsonUtil.parseObject(readBody(ex));
        String sql = JsonUtil.getString(req, "sql", "").trim();
        if (sql.isEmpty()) {
            sendJson(ex, 400, Map.of("error", "SQL statement is required"));
            return;
        }
        QueryResult res = db.execute(sql);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sql", res.sql());
        out.put("ok", res.isOk());
        out.put("durationMs", Math.round(res.durationMs() * 100.0) / 100.0);
        out.put("columns", res.columns());
        out.put("rows", res.rows());
        out.put("error", res.error() == null ? "" : res.error());
        sendJson(ex, res.isOk() ? 200 : 400, out);
    }

    private void handleVacuum(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "Method not allowed"));
            return;
        }
        QueryResult res = db.execute("VACUUM;");
        if (res.isOk()) {
            db.notifyChannel("nexus_events", "ENGINE_VACUUM_COMPLETED");
        }
        sendJson(ex, res.isOk() ? 200 : 500, Map.of(
            "ok", res.isOk(),
            "durationMs", Math.round(res.durationMs() * 100.0) / 100.0,
            "error", res.error() == null ? "" : res.error()
        ));
    }

    private void handleBackup(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "Method not allowed"));
            return;
        }
        String backupFile = "nexusvault_backup_" + (System.currentTimeMillis() / 1000L) + ".db";
        QueryResult res = db.execute("BACKUP DATABASE TO '" + backupFile + "';");
        if (res.isOk()) {
            db.notifyChannel("nexus_events", "ENGINE_BACKUP:" + backupFile);
        }
        sendJson(ex, res.isOk() ? 200 : 500, Map.of(
            "ok", res.isOk(),
            "file", backupFile,
            "durationMs", Math.round(res.durationMs() * 100.0) / 100.0,
            "error", res.error() == null ? "" : res.error()
        ));
    }

    private void handleSseEvents(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);

        OutputStream out = ex.getResponseBody();
        Object lock = new Object();
        final boolean[] alive = { true };

        Consumer<String> listener = payload -> {
            synchronized (lock) {
                if (!alive[0]) return;
                try {
                    String msg = "data: " + JsonUtil.toJson(Map.of("event", payload, "ts", System.currentTimeMillis())) + "\n\n";
                    out.write(msg.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException e) {
                    alive[0] = false;
                }
            }
        };

        pubSub.addSubscriber(listener);
        try {
            out.write(("data: " + JsonUtil.toJson(Map.of("event", "CONNECTED_TO_DBMS_PUBSUB", "ts", System.currentTimeMillis())) + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (alive[0]) {
                Thread.sleep(15000);
                synchronized (lock) {
                    if (!alive[0]) break;
                    out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } catch (Exception ignored) {
        } finally {
            alive[0] = false;
            pubSub.removeSubscriber(listener);
            try { out.close(); } catch (IOException ignored) {}
        }
    }

    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if ("/".equals(path) || path.isEmpty()) path = "/index.html";
        Path resolved = webRoot.resolve(path.substring(1)).normalize();
        if (!resolved.startsWith(webRoot) || !Files.exists(resolved) || Files.isDirectory(resolved)) {
            byte[] notFound = "Not Found".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(404, notFound.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(notFound); }
            return;
        }
        String contentType = switch (getExtension(resolved.getFileName().toString())) {
            case "html" -> "text/html; charset=utf-8";
            case "css"  -> "text/css; charset=utf-8";
            case "js"   -> "application/javascript; charset=utf-8";
            case "svg"  -> "image/svg+xml";
            default     -> "application/octet-stream";
        };
        byte[] bytes = Files.readAllBytes(resolved);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void sendJson(HttpExchange ex, int status, Object payload) throws IOException {
        byte[] bytes = JsonUtil.toJson(payload).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isEmpty()) return map;
        for (String pair : raw.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String k = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                map.put(k, v);
            }
        }
        return map;
    }

    private static int extractTrailingId(String path, String prefix) {
        if (!path.startsWith(prefix)) return -1;
        String tail = path.substring(prefix.length()).replace("/", "");
        return tryParseIntOrZero(tail);
    }

    private static Integer tryParseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return null; }
    }

    private static int tryParseIntOrZero(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static String getExtension(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }
}
