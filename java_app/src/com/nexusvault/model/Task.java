package com.nexusvault.model;

/**
 * Immutable domain model representing a Kanban/Workspace Task linked to a Project via foreign key.
 */
public record Task(
    int id,
    int projectId,
    String title,
    String description,
    String status,      // TODO, IN_PROGRESS, DONE
    String priority,    // LOW, MEDIUM, HIGH
    String dueDate,
    String createdAt
) {}
