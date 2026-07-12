package com.hunt.otziv.p_products.worker_flow;

public enum WorkerPublicationSessionCloseReason {
    INACTIVITY,
    DAY_END,
    NO_AVAILABLE_PUBLICATIONS,
    COMPLETED,
    REPLACED
}
