package com.hunt.otziv.r_review.board;

public enum ReviewBoardScope {
    ADMIN,
    WORKER,
    MANAGER,
    OWNER;

    public static ReviewBoardScope fromRole(String role) {
        if ("ADMIN".equalsIgnoreCase(role)) {
            return ADMIN;
        }
        if ("OWNER".equalsIgnoreCase(role)) {
            return OWNER;
        }
        if ("MANAGER".equalsIgnoreCase(role)) {
            return MANAGER;
        }
        return WORKER;
    }
}
