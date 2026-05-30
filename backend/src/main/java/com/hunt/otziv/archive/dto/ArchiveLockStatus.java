package com.hunt.otziv.archive.dto;

public record ArchiveLockStatus(
        String name,
        boolean locked,
        Long ownerConnectionId,
        boolean heldByCurrentConnection
) {
}
