package com.nexusvault.model;

/**
 * Immutable domain model representing a searchable Knowledge Base Note / Code Snippet
 * backed by the custom C DBMS FTS5 full-text search virtual table.
 */
public record Note(
    int id,
    String title,
    String body
) {}
