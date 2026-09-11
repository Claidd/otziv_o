package com.hunt.otziv.u_users.readmodel;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Identity-owned values stay internal; the public API exposes only the assembly boundary. */
public final class BoardIdentityReadContext {
    private enum Kind { USER_NAME, MANAGER_USER, WORKER_USER }
    private record Key(Kind kind, Object id) {}
    private static final int MAX_ENTRIES = 64;
    private static final ThreadLocal<Map<Key, Object>> CURRENT = new ThreadLocal<>();
    private BoardIdentityReadContext() {}

    public static Runnable begin() {
        Map<Key, Object> previous = CURRENT.get();
        CURRENT.set(new HashMap<>());
        return () -> {
            if (previous == null) CURRENT.remove();
            else { previous.clear(); CURRENT.set(previous); }
        };
    }

    public static Optional<User> user(String name, Supplier<Optional<User>> loader) {
        return read(new Key(Kind.USER_NAME, name), loader);
    }

    public static Optional<Manager> manager(Long userId, Supplier<Optional<Manager>> loader) {
        return read(new Key(Kind.MANAGER_USER, userId), loader);
    }

    public static Optional<Worker> worker(Long userId, Supplier<Optional<Worker>> loader) {
        return read(new Key(Kind.WORKER_USER, userId), loader);
    }

    @SuppressWarnings("unchecked")
    private static <T> T read(Key key, Supplier<T> loader) {
        Map<Key, Object> values = CURRENT.get();
        if (values == null) return loader.get();
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            values.clear();
            return loader.get();
        }
        if (values.containsKey(key)) return (T) values.get(key);
        T value = loader.get();
        if (values.size() < MAX_ENTRIES) values.put(key, value);
        return value;
    }
}
