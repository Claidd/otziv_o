package com.hunt.otziv.p_products.application;

public class WorkerOrderCommandException extends RuntimeException {
    public enum Kind {
        BAD_REQUEST(400), FORBIDDEN(403), NOT_FOUND(404), CONFLICT(409), LEGACY(0);
        private final int statusCode;
        Kind(int statusCode) { this.statusCode = statusCode; }
    }
    private final Kind kind;
    private final int statusCode;
    public WorkerOrderCommandException(Kind kind, String message) { this(kind, message, null); }
    public WorkerOrderCommandException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.statusCode = kind.statusCode;
    }
    private WorkerOrderCommandException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.kind = switch (statusCode) {
            case 400 -> Kind.BAD_REQUEST;
            case 403 -> Kind.FORBIDDEN;
            case 404 -> Kind.NOT_FOUND;
            case 409 -> Kind.CONFLICT;
            default -> Kind.LEGACY;
        };
        this.statusCode = statusCode;
    }
    public static WorkerOrderCommandException legacy(int statusCode, String message, Throwable cause) {
        return new WorkerOrderCommandException(statusCode, message, cause);
    }
    public Kind kind() { return kind; }
    public int statusCode() { return statusCode; }
}
