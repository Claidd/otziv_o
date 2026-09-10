package com.hunt.otziv.manager_control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.client_messages.api.DeliveryOperation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Unit-test clock/queue adapter. Transaction, encryption and concurrency use the MySQL suite. */
class MemoryManagerMessageQueue extends ManagerClientMessageQueue {
    private final Map<String, Command> commands = new LinkedHashMap<>();
    private final Map<String, DeliveryOperation> states = new LinkedHashMap<>();
    private final Map<String, String> leases = new LinkedHashMap<>();
    private final Map<String, Boolean> due = new LinkedHashMap<>();
    MemoryManagerMessageQueue() { super(null, new ObjectMapper().findAndRegisterModules(), null); }
    @Override public void enqueue(Command command) {
        var old = commands.putIfAbsent(command.operationId(), command);
        if (old != null && !old.equals(command)) throw new IllegalStateException("different envelope");
        states.putIfAbsent(command.operationId(), new DeliveryOperation(command.operationId(), "QUEUED", 0, null));
        due.putIfAbsent(command.operationId(), true);
    }
    @Override Command snapshot(String id) { return commands.get(id); }
    @Override public DeliveryOperation status(long cardId, String id) {
        return commands.containsKey(id) && commands.get(id).cardId() == cardId ? states.get(id) : null;
    }
    @Override public Optional<Claim> claim() {
        return commands.values().stream().filter(c -> Boolean.TRUE.equals(due.get(c.operationId())))
                .filter(c -> java.util.Set.of("QUEUED", "RETRYABLE", "UNKNOWN").contains(states.get(c.operationId()).status()))
                .findFirst().map(c -> {
                    var state = states.get(c.operationId());
                    var claim = new Claim(c.operationId(), c.cardId(), c.kind(), state.status(), state.attempts(), UUID.randomUUID().toString());
                    states.put(c.operationId(), new DeliveryOperation(c.operationId(), "SENDING", state.attempts() + (claim.mayDispatch() ? 1 : 0), null));
                    due.put(c.operationId(), false); leases.put(c.operationId(), claim.token()); return claim;
                });
    }
    @Override public boolean owns(Claim claim) { return Objects.equals(leases.get(claim.operationId()), claim.token()); }
    @Override public void finish(Claim claim, String state, String code, int delay) {
        if (!owns(claim)) return;
        int attempts = states.get(claim.operationId()).attempts();
        states.put(claim.operationId(), new DeliveryOperation(claim.operationId(), state,
                attempts - ("live_disabled".equals(code) && claim.mayDispatch() ? 1 : 0), code));
        leases.remove(claim.operationId());
    }
    void advance() { due.replaceAll((id, ready) -> true); }
}
