package com.hunt.otziv.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PaymentLinkPreparationDeadlockRetryTest {

    @Test
    void mysqlDeadlockRollsBackBeforeRetryAndOnlySuccessfulLinkCommits() {
        CountingTransactions transactions = new CountingTransactions();
        PaymentLinkTransactionExecutor executor = proxiedExecutor(transactions);
        AtomicInteger attempts = new AtomicInteger();
        List<String> committedLinkTokens = new ArrayList<>();

        String token = PaymentLinkPreparationWorkflow.retryDeadlockedPreparation(27202L,
                () -> executor.required(() -> {
                    int attempt = attempts.incrementAndGet();
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    if (attempt == 2) {
                        assertThat(transactions.rollbacks).isEqualTo(1);
                    }
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            committedLinkTokens.add("link-" + attempt);
                        }
                    });
                    if (attempt == 1) {
                        throw PaymentLinkPreparationWorkflow.markPreparationDeadlock(mysqlDeadlock());
                    }
                    return "link-" + attempt;
                }));

        assertThat(token).isEqualTo("link-2");
        assertThat(attempts).hasValue(2);
        assertThat(transactions.rollbacks).isEqualTo(1);
        assertThat(transactions.commits).isEqualTo(1);
        assertThat(committedLinkTokens).containsExactly("link-2");
    }

    @Test
    void unrelatedSqlLockTimeoutAndBusinessFailureDoNotRetry() {
        CountingTransactions transactions = new CountingTransactions();
        PaymentLinkTransactionExecutor executor = proxiedExecutor(transactions);
        AtomicInteger attempts = new AtomicInteger();
        RuntimeException lockTimeout = sqlFailure("HY000", 1205);

        assertThatThrownBy(() -> PaymentLinkPreparationWorkflow.retryDeadlockedPreparation(27202L,
                () -> executor.required(() -> {
                    attempts.incrementAndGet();
                    throw PaymentLinkPreparationWorkflow.markPreparationDeadlock(lockTimeout);
                }))).isSameAs(lockTimeout);
        assertThat(attempts).hasValue(1);
        assertThat(transactions.rollbacks).isEqualTo(1);

        RuntimeException businessFailure = new IllegalStateException("payment route is blocked");
        assertThatThrownBy(() -> PaymentLinkPreparationWorkflow.retryDeadlockedPreparation(27202L,
                () -> executor.required(() -> {
                    attempts.incrementAndGet();
                    throw PaymentLinkPreparationWorkflow.markPreparationDeadlock(businessFailure);
                }))).isSameAs(businessFailure);
        assertThat(attempts).hasValue(2);
        assertThat(transactions.rollbacks).isEqualTo(2);
    }

    @Test
    void committedTransactionIsNeverRepeatedForAnAfterCommitDeadlock() {
        CountingTransactions transactions = new CountingTransactions();
        PaymentLinkTransactionExecutor executor = proxiedExecutor(transactions);
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> PaymentLinkPreparationWorkflow.retryDeadlockedPreparation(27202L,
                () -> executor.required(() -> {
                    attempts.incrementAndGet();
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            throw mysqlDeadlock();
                        }
                    });
                    return "committed-link";
                }))).isInstanceOf(CannotAcquireLockException.class);
        assertThat(attempts).hasValue(1);
        assertThat(transactions.commits).isEqualTo(1);
        assertThat(transactions.rollbacks).isZero();
    }

    @Test
    void deadlockExhaustionReturnsOriginalFailureAfterThreeRolledBackAttempts() {
        CountingTransactions transactions = new CountingTransactions();
        PaymentLinkTransactionExecutor executor = proxiedExecutor(transactions);
        AtomicInteger attempts = new AtomicInteger();
        RuntimeException deadlock = mysqlDeadlock();

        assertThatThrownBy(() -> PaymentLinkPreparationWorkflow.retryDeadlockedPreparation(27202L,
                () -> executor.required(() -> {
                    attempts.incrementAndGet();
                    throw PaymentLinkPreparationWorkflow.markPreparationDeadlock(deadlock);
                }))).isSameAs(deadlock);
        assertThat(attempts).hasValue(3);
        assertThat(transactions.rollbacks).isEqualTo(3);
        assertThat(transactions.commits).isZero();
    }

    private static CannotAcquireLockException mysqlDeadlock() {
        return sqlFailure("40001", 1213);
    }

    private static CannotAcquireLockException sqlFailure(String state, int code) {
        return new CannotAcquireLockException("database lock failure",
                new SQLException("transaction rolled back", state, code));
    }

    private static PaymentLinkTransactionExecutor proxiedExecutor(CountingTransactions transactions) {
        ProxyFactory factory = new ProxyFactory(new PaymentLinkTransactionExecutor());
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions,
                new AnnotationTransactionAttributeSource(false)));
        return (PaymentLinkTransactionExecutor) factory.getProxy();
    }

    private static final class CountingTransactions extends AbstractPlatformTransactionManager {
        private static final class Transaction implements SmartTransactionObject {
            private boolean active;

            @Override
            public boolean isRollbackOnly() {
                return false;
            }
        }

        private final ThreadLocal<Transaction> current = new ThreadLocal<>();
        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return current.get() == null ? new Transaction() : current.get();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return ((Transaction) transaction).active;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            ((Transaction) transaction).active = true;
            current.set((Transaction) transaction);
        }

        @Override
        protected Object doSuspend(Object transaction) {
            current.remove();
            return transaction;
        }

        @Override
        protected void doResume(Object transaction, Object suspendedResources) {
            current.set((Transaction) suspendedResources);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            ((Transaction) transaction).active = false;
            current.remove();
        }
    }
}
