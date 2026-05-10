package com.hunt.otziv.archive;

public record ArchiveLockStatus(
        String name,
        boolean locked,
        Long ownerConnectionId,
        boolean heldByCurrentConnection
) {
}
