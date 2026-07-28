package com.hunt.otziv.workload_shadow.lock;

import com.hunt.otziv.workload_shadow.repository.WorkloadShadowRecalculationLockRepository;
import jakarta.annotation.PreDestroy;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class WorkloadShadowRecalculationLockService {

    static final String LOCK_NAME = "GLOBAL_RECALCULATION";
    static final int LEASE_SECONDS = 120;
    static final int HEARTBEAT_SECONDS = 30;

    private final WorkloadShadowRecalculationLockRepository repository;
    private final ScheduledExecutorService heartbeatExecutor;
    private final Supplier<String> tokenSupplier;

    @Autowired
    public WorkloadShadowRecalculationLockService(
            WorkloadShadowRecalculationLockRepository repository
    ) {
        this(repository, newHeartbeatExecutor(), () -> UUID.randomUUID().toString());
    }

    WorkloadShadowRecalculationLockService(
            WorkloadShadowRecalculationLockRepository repository,
            ScheduledExecutorService heartbeatExecutor,
            Supplier<String> tokenSupplier
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.heartbeatExecutor = Objects.requireNonNull(heartbeatExecutor);
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier);
    }

    public Optional<WorkloadShadowRecalculationLease> tryAcquire(String requestedInstanceId) {
        String instanceId = normalizeInstanceId(requestedInstanceId);
        String ownerToken = Objects.requireNonNull(tokenSupplier.get(), "ownerToken");
        repository.ensureLockRow(LOCK_NAME);
        int acquired = repository.tryAcquire(
                LOCK_NAME,
                instanceId,
                ownerToken,
                LEASE_SECONDS
        );
        if (acquired != 1) {
            return Optional.empty();
        }
        return Optional.of(new DatabaseLease(instanceId, ownerToken));
    }

    @PreDestroy
    void shutdownHeartbeatExecutor() {
        heartbeatExecutor.shutdownNow();
    }

    private static ScheduledExecutorService newHeartbeatExecutor() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "workload-shadow-recalculation-lease");
            thread.setDaemon(true);
            return thread;
        });
    }

    private String normalizeInstanceId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return "unknown-instance";
        }
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    private final class DatabaseLease implements WorkloadShadowRecalculationLease {

        private final String instanceId;
        private final String ownerToken;
        private final AtomicBoolean attached = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean lost = new AtomicBoolean(false);
        private final AtomicReference<Throwable> lossCause = new AtomicReference<>();
        private volatile ScheduledFuture<?> heartbeat;

        private DatabaseLease(String instanceId, String ownerToken) {
            this.instanceId = instanceId;
            this.ownerToken = ownerToken;
        }

        @Override
        public void attachRun(long runId) {
            requireOpen();
            if (runId <= 0) {
                throw new IllegalArgumentException("runId должен быть положительным");
            }
            if (!attached.compareAndSet(false, true)) {
                throw new IllegalStateException("Запуск уже привязан к lease workload shadow");
            }
            int updated = repository.attachRun(
                    LOCK_NAME,
                    instanceId,
                    ownerToken,
                    runId,
                    LEASE_SECONDS
            );
            if (updated != 1) {
                markLost(null);
                throw lostException("Не удалось привязать запуск " + runId);
            }
            heartbeat = heartbeatExecutor.scheduleWithFixedDelay(
                    this::heartbeat,
                    HEARTBEAT_SECONDS,
                    HEARTBEAT_SECONDS,
                    TimeUnit.SECONDS
            );
        }

        @Override
        public void checkpoint(String phase) {
            requireOpen();
            if (!attached.get()) {
                throw new IllegalStateException("К lease workload shadow ещё не привязан запуск");
            }
            if (lost.get()) {
                throw lostException("Lease уже был потерян на heartbeat");
            }
            try {
                int updated = repository.renew(
                        LOCK_NAME,
                        instanceId,
                        ownerToken,
                        LEASE_SECONDS
                );
                if (updated != 1) {
                    markLost(null);
                    throw lostException("Lease больше не принадлежит этому запуску");
                }
            } catch (WorkloadShadowLeaseLostException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                markLost(exception);
                throw lostException("Не удалось подтвердить lease", exception);
            }
            log.debug(
                    "Workload shadow lease checkpoint passed, instance={}, phase={}",
                    instanceId,
                    phase
            );
        }

        private void heartbeat() {
            if (closed.get() || lost.get()) {
                return;
            }
            try {
                int updated = repository.renew(
                        LOCK_NAME,
                        instanceId,
                        ownerToken,
                        LEASE_SECONDS
                );
                if (updated != 1) {
                    markLost(null);
                    log.error(
                            "Workload shadow recalculation lease ownership was lost, instance={}",
                            instanceId
                    );
                }
            } catch (RuntimeException exception) {
                markLost(exception);
                log.error(
                        "Workload shadow recalculation lease heartbeat failed, instance={}",
                        instanceId,
                        exception
                );
            }
        }

        private void markLost(Throwable cause) {
            if (cause != null) {
                lossCause.compareAndSet(null, cause);
            }
            lost.set(true);
            ScheduledFuture<?> scheduledHeartbeat = heartbeat;
            if (scheduledHeartbeat != null) {
                scheduledHeartbeat.cancel(false);
            }
        }

        private void requireOpen() {
            if (closed.get()) {
                throw new IllegalStateException("Lease workload shadow уже закрыт");
            }
        }

        private WorkloadShadowLeaseLostException lostException(String detail) {
            Throwable cause = lossCause.get();
            return cause == null
                    ? new WorkloadShadowLeaseLostException(detail)
                    : new WorkloadShadowLeaseLostException(detail, cause);
        }

        private WorkloadShadowLeaseLostException lostException(
                String detail,
                Throwable cause
        ) {
            return new WorkloadShadowLeaseLostException(detail, cause);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            ScheduledFuture<?> scheduledHeartbeat = heartbeat;
            if (scheduledHeartbeat != null) {
                scheduledHeartbeat.cancel(false);
            }
            try {
                repository.release(LOCK_NAME, instanceId, ownerToken);
            } catch (RuntimeException exception) {
                log.error(
                        "Failed to release workload shadow recalculation lease; "
                                + "it will expire automatically, instance={}",
                        instanceId,
                        exception
                );
            }
        }
    }
}
