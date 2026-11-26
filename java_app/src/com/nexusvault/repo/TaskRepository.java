package com.nexusvault.repo;

import com.nexusvault.db.DbmsClient;
import com.nexusvault.db.QueryResult;
import com.nexusvault.model.Task;

import java.time.LocalDate;
import java.util.*;

/**
 * Data Access Object (Repository) for Kanban & Workspace Tasks.
 */
public final class TaskRepository {

    private final DbmsClient db;

    public TaskRepository(DbmsClient db) {
        this.db = db;
    }

    public List<Task> findAll(Integer projectId, String status) {
        StringBuilder sql = new StringBuilder(
            "SELECT id, project_id, title, description, status, priority, due_date, created_at FROM tasks"
        );
        List<String> clauses = new ArrayList<>();
        if (projectId != null && projectId > 0) {
            clauses.add("project_id = " + projectId);
        }
        if (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) {
            clauses.add("status = " + DbmsClient.sqlStr(status.toUpperCase()));
        }
        if (!clauses.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", clauses));
        }
        sql.append(" ORDER BY id DESC;");

        QueryResult res = db.execute(sql.toString());
        List<Task> list = new ArrayList<>();
        if (!res.isOk()) return list;
        for (List<String> row : res.rows()) {
            if (row.size() >= 8) {
                list.add(new Task(
                    parseInt(row.get(0), 0),
                    parseInt(row.get(1), 1),
                    nullSafe(row.get(2)),
                    nullSafe(row.get(3)),
                    nullSafe(row.get(4), "TODO"),
                    nullSafe(row.get(5), "MEDIUM"),
                    nullSafe(row.get(6)),
                    nullSafe(row.get(7))
                ));
            }
        }
        return list;
    }

    public synchronized Task create(int projectId, String title, String description, String priority, String dueDate) {
        int nextId = nextId();
        String today = LocalDate.now().toString();
        String prio = normalizePriority(priority);
        String due = (dueDate == null || dueDate.isBlank()) ? LocalDate.now().plusDays(7).toString() : dueDate.trim();

        String sql = "INSERT INTO tasks VALUES ("
            + nextId + ", "
            + projectId + ", "
            + DbmsClient.sqlStr(title) + ", "
            + DbmsClient.sqlStr(description) + ", "
            + "'TODO', "
            + DbmsClient.sqlStr(prio) + ", "
            + DbmsClient.sqlStr(due) + ", "
            + DbmsClient.sqlStr(today) + ");";

        QueryResult res = db.execute(sql);
        if (!res.isOk()) {
            throw new IllegalStateException("Failed to create task: " + res.error());
        }
        db.notifyChannel("nexus_events", "TASK_CREATED:" + title);
        return new Task(nextId, projectId, DbmsClient.sanitizeSql(title), DbmsClient.sanitizeSql(description), "TODO", prio, due, today);
    }

    public synchronized boolean updateStatus(int id, String status) {
        String norm = normalizeStatus(status);
        QueryResult res = db.execute("UPDATE tasks SET status = " + DbmsClient.sqlStr(norm) + " WHERE id = " + id + ";");
        if (res.isOk()) {
            db.notifyChannel("nexus_events", "TASK_MOVED:#" + id + " -> " + norm);
            return true;
        }
        return false;
    }

    public synchronized boolean delete(int id) {
        QueryResult res = db.execute("DELETE FROM tasks WHERE id = " + id + ";");
        if (res.isOk()) {
            db.notifyChannel("nexus_events", "TASK_DELETED:#" + id);
            return true;
        }
        return false;
    }

    private int nextId() {
        QueryResult res = db.execute("SELECT id FROM tasks ORDER BY id DESC LIMIT 1;");
        if (res.isOk() && !res.rows().isEmpty()) {
            return parseInt(res.rows().get(0).get(0), 0) + 1;
        }
        return 1;
    }

    private static String normalizeStatus(String s) {
        if (s == null) return "TODO";
        return switch (s.trim().toUpperCase()) {
            case "IN_PROGRESS" -> "IN_PROGRESS";
            case "DONE" -> "DONE";
            default -> "TODO";
        };
    }

    private static String normalizePriority(String p) {
        if (p == null) return "MEDIUM";
        return switch (p.trim().toUpperCase()) {
            case "HIGH" -> "HIGH";
            case "LOW" -> "LOW";
            default -> "MEDIUM";
        };
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }
    private static String nullSafe(String s, String def) { return (s == null || s.isBlank()) ? def : s; }
}
