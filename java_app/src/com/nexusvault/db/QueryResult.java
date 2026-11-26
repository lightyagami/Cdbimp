package com.nexusvault.db;

import java.util.List;

/**
 * Structured result returned by the custom C DBMS HTTP /query endpoint.
 */
public record QueryResult(
    String sql,
    List<String> columns,
    List<List<String>> rows,
    double durationMs,
    String error
) {
    public boolean isOk() {
        return error == null || error.isEmpty();
    }
}
