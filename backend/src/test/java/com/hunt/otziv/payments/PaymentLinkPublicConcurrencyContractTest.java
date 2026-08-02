package com.hunt.otziv.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.payments.service.PaymentLinkTransactionExecutor;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class PaymentLinkPublicConcurrencyContractTest {

    @Test
    void providerReadIsSuspendedWhileStateApplyUsesIndependentShortTransaction() throws Exception {
        assertNotSupported("publicLink", String.class);
        assertNotSupported("reconcileActiveLinkForOrder", Long.class);
        assertNotSupported("reconcileBankLink", Long.class);
        assertNotSupported("reconcileBankLink", Long.class, LocalDateTime.class);
        assertNotSupported("cancel", Long.class);
        assertNotSupported("confirmManual", Long.class, String.class);
        assertNotSupported("handleTbankWebhook", Map.class);
        assertNotSupported("publicSbpBanks", String.class, String.class, String.class);

        Transactional apply = PaymentLinkTransactionExecutor.class
                .getMethod("required", Supplier.class)
                .getAnnotation(Transactional.class);
        assertThat(apply).isNotNull();
        assertThat(apply.propagation()).isEqualTo(Propagation.REQUIRES_NEW);

        Transactional readOnly = PaymentLinkTransactionExecutor.class
                .getMethod("readOnly", Supplier.class)
                .getAnnotation(Transactional.class);
        assertThat(readOnly).isNotNull();
        assertThat(readOnly.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(readOnly.readOnly()).isTrue();
    }

    @Test
    void publicTokenMutationUsesPessimisticWriteLock() throws Exception {
        Lock lock = PaymentLinkRepository.class
                .getMethod("findByTokenForUpdate", String.class)
                .getAnnotation(Lock.class);
        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);

        Transactional report = PaymentLinkService.class
                .getMethod("reportManualPayment", String.class)
                .getAnnotation(Transactional.class);
        assertThat(report).isNotNull();
        assertThat(report.readOnly()).isFalse();
    }

    private void assertNotSupported(String methodName, Class<?>... parameterTypes) throws Exception {
        Transactional transactional = PaymentLinkService.class
                .getMethod(methodName, parameterTypes)
                .getAnnotation(Transactional.class);
        assertThat(transactional).as(methodName + " transaction boundary").isNotNull();
        assertThat(transactional.propagation()).as(methodName + " propagation")
                .isEqualTo(Propagation.NOT_SUPPORTED);
    }
}
