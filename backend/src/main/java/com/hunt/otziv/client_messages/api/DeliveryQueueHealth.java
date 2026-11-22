package com.hunt.otziv.client_messages.api;

import java.util.List;

/** Read-only owner projection. Contains no destination, text, credentials or operation identifiers. */
public interface DeliveryQueueHealth {
    enum State { QUEUED, SENDING, RETRYABLE, UNKNOWN, FAILED }
    /**
     * @param oldestSeconds overdue age of the oldest message in this state: normally since it was
     *                      created; a message deliberately held by the sending schedule counts only
     *                      from when its re-check fell due, so a planned wait is not reported as delay
     */
    record Row(State state, long jobs, double oldestSeconds, long expiredLeases) { }
    String queueName();
    List<Row> deliveryQueueHealth();
}
