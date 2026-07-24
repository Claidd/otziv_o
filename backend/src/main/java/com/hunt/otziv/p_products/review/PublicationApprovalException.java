package com.hunt.otziv.p_products.review;

public class PublicationApprovalException extends RuntimeException {

    private final Long orderId;
    private final String problem;
    private final String solution;

    public PublicationApprovalException(Long orderId, String problem, String solution) {
        this(orderId, problem, solution, null);
    }

    public PublicationApprovalException(Long orderId, String problem, String solution, Throwable cause) {
        super(message(orderId, problem, solution), cause);
        this.orderId = orderId;
        this.problem = safe(problem);
        this.solution = safe(solution);
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getProblem() {
        return problem;
    }

    public String getSolution() {
        return solution;
    }

    private static String message(Long orderId, String problem, String solution) {
        return "Заказ #" + (orderId == null ? "-" : orderId)
                + ". Проблема: " + safe(problem)
                + ". Решение: " + safe(solution);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
