package com.hunt.otziv.p_products.board.model;

import java.util.List;

/** Transport-independent read model, with stable section order. */
public record WorkerOverdueOrders(int thresholdDays, long total, List<Section> statuses) {
    public WorkerOverdueOrders { statuses = List.copyOf(statuses); }
    public record Section(String status, long count, long maxDays) {}
}
