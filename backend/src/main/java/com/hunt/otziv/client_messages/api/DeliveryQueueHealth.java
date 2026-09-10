package com.hunt.otziv.client_messages.api;

import java.util.List;

/** Read-only owner projection. Contains no destination, text, credentials or operation identifiers. */
public interface DeliveryQueueHealth {
    enum State { QUEUED, SENDING, RETRYABLE, UNKNOWN, FAILED }
    record Row(State state, long jobs, double oldestSeconds, long expiredLeases) { }
    String queueName();
    List<Row> deliveryQueueHealth();
}
