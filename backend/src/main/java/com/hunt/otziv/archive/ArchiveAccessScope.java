package com.hunt.otziv.archive;

import java.util.Set;

record ArchiveAccessScope(boolean allManagers, Set<Long> managerIds) {

    static ArchiveAccessScope all() {
        return new ArchiveAccessScope(true, Set.of());
    }

    static ArchiveAccessScope managers(Set<Long> managerIds) {
        return new ArchiveAccessScope(false, managerIds == null ? Set.of() : Set.copyOf(managerIds));
    }

    boolean isUnrestricted() {
        return allManagers;
    }
}
