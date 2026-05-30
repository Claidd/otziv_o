package com.hunt.otziv.archive.dto;

import java.util.Set;

public record ArchiveAccessScope(boolean allManagers, Set<Long> managerIds) {

    public static ArchiveAccessScope all() {
        return new ArchiveAccessScope(true, Set.of());
    }

    public static ArchiveAccessScope managers(Set<Long> managerIds) {
        return new ArchiveAccessScope(false, managerIds == null ? Set.of() : Set.copyOf(managerIds));
    }

    public boolean isUnrestricted() {
        return allManagers;
    }
}
