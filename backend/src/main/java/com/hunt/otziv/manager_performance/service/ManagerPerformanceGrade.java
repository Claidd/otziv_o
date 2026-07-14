package com.hunt.otziv.manager_performance.service;

public final class ManagerPerformanceGrade {

    private ManagerPerformanceGrade() {
    }

    public static String of(int score) {
        int safe = Math.max(0, Math.min(100, score));
        if (safe >= 90) return "A";
        if (safe >= 80) return "B";
        if (safe >= 70) return "C";
        if (safe >= 60) return "D";
        if (safe >= 50) return "E";
        if (safe >= 40) return "F";
        if (safe >= 30) return "G";
        if (safe >= 20) return "H";
        if (safe >= 10) return "I";
        return "J";
    }
}
