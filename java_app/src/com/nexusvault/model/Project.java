package com.nexusvault.model;

/**
 * Immutable domain model representing a Workspace Project stored in the custom C DBMS.
 */
public record Project(
    int id,
    String name,
    String description,
    String color,
    String createdAt
) {}
