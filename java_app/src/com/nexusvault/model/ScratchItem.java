package com.nexusvault.model;

/**
 * Immutable domain model representing an Ephemeral Vault item backed by the DBMS's native
 * row-level TTL (`INSERT ... EXPIRES <seconds>`).
 */
public record ScratchItem(
    int id,
    String label,
    String content,
    int ttlSeconds,
    long expiresAtEpochSec
) {}
