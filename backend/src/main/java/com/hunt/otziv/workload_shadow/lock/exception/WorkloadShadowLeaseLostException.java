package com.hunt.otziv.workload_shadow.lock.exception;

public class WorkloadShadowLeaseLostException extends IllegalStateException {

    public WorkloadShadowLeaseLostException(String message) {
        super(message);
    }

    public WorkloadShadowLeaseLostException(String message, Throwable cause) {
        super(message, cause);
    }
}
