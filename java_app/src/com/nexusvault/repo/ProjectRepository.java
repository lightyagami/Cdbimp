package com.nexusvault.repo;

import com.nexusvault.db.DbmsClient;
import com.nexusvault.db.QueryResult;
import com.nexusvault.model.Project;

import java.time.LocalDate;
import java.util.*;

/**
 * Data Access Object (Repository) for Workspace Projects.
 */
public final class ProjectRepository {

    private final DbmsClient db;

    public ProjectRepository(DbmsClient db) {
        this.db = db;
    }

    public List<Project> findAll() {
        QueryResult res = db.execute("SELECT id, name, description, color, created_at FROM projects ORDER BY id ASC;");
        List<Project> list = new ArrayList<>();
        if (!res.isOk()) return list;
        for (List<String> row : res.rows()) {
            if (row.size() >= 5) {
                list.add(new Project(
                    parseInt(row.get(0), 0),
                    nullSafe(row.get(1)),
                    nullSafe(row.get(2)),
                    nullSafe(row.get(3), "#6366f1"),
                    nullSafe(row.get(4))
                ));
            }
        }
        return list;
    }

    public synchronized Project create(String name, String description, String color) {
        int nextId = nextId();
        String today = LocalDate.now().toString();
        String safeColor = (color == null || color.isBlank()) ? "#6366f1" : color.trim();
        String sql = "INSERT INTO projects VALUES (" + nextId + ", "
            + DbmsClient.sqlStr(name) + ", "
            + DbmsClient.sqlStr(description) + ", "
            + DbmsClient.sqlStr(safeColor) + ", "
            + DbmsClient.sqlStr(today) + ");";
        QueryResult res = db.execute(sql);
        if (!res.isOk()) {
            throw new IllegalStateException("Failed to create project: " + res.error());
        }
        db.notifyChannel("nexus_events", "PROJECT_CREATED:" + name);
        return new Project(nextId, DbmsClient.sanitizeSql(name), DbmsClient.sanitizeSql(description), safeColor, today);
    }

    public synchronized boolean delete(int id) {
        // Delete child tasks and parent project atomically
        db.execute("DELETE FROM tasks WHERE project_id = " + id + ";");
        QueryResult res = db.execute("DELETE FROM projects WHERE id = " + id + ";");
        if (res.isOk()) {
            db.notifyChannel("nexus_events", "PROJECT_DELETED:" + id);
            return true;
        }
        return false;
    }

    private int nextId() {
        QueryResult res = db.execute("SELECT id FROM projects ORDER BY id DESC LIMIT 1;");
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
    private static String nullSafe(String s, String def) { return (s == null || s.isBlank()) ? def : s; }
}
