package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.repository.SenderDispatchLedger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Test ledger with the same latest-per-(event, lane) contract as the JDBC one. */
public class InMemorySenderDispatchLedger extends SenderDispatchLedger {

    public record Row(String account, String lane, String event, LocalDateTime at) {}

    public final List<Row> rows = new ArrayList<>();

    public InMemorySenderDispatchLedger() {
        super(null);
    }

    @Override
    public Map<String, LocalDateTime> latest(String account) {
        Map<String, LocalDateTime> latest = new HashMap<>();
        for (Row row : rows) {
            if (!row.account().equals(account)) continue;
            latest.merge(row.event() + ":" + row.lane(), row.at(), (a, b) -> a.isAfter(b) ? a : b);
        }
        return latest;
    }

    @Override
    public void record(String account, String lane, String event, LocalDateTime occurredAt) {
        rows.add(new Row(account, lane, event, occurredAt));
    }

    @Override
    public int purgeOlderThan(LocalDateTime cutoff) {
        int before = rows.size();
        rows.removeIf(row -> row.at().isBefore(cutoff));
        return before - rows.size();
    }

    public long count(String account, String lane, String event) {
        return rows.stream().filter(r -> r.account().equals(account) && r.lane().equals(lane) && r.event().equals(event)).count();
    }
}
