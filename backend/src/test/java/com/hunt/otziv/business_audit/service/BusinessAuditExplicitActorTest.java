package com.hunt.otziv.business_audit.service;

import com.hunt.otziv.business_audit.model.BusinessAuditEvent;
import com.hunt.otziv.business_audit.repository.BusinessAuditEventRepository;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actor selection and validation only; MySQL commit guarantees have separate integration coverage. */
class BusinessAuditExplicitActorTest {
    private final BusinessAuditEventRepository repository = mock(BusinessAuditEventRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final BusinessAuditService service = new BusinessAuditService(repository, transactions);

    @BeforeEach void setUp() { SecurityContextHolder.getContext().setAuthentication(actor("ambient-b")); }
    @AfterEach void cleanUp() { SecurityContextHolder.clearContext(); RequestContextHolder.resetRequestAttributes(); }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitStrictActorIsCapturedBeforeTransactionBeginsEvenIfAmbientChanges(boolean emptyAmbient) {
        if (emptyAmbient) SecurityContextHolder.clearContext();
        when(transactions.getTransaction(any())).thenAnswer(call -> {
            assertThat(((TransactionDefinition) call.getArgument(0)).getPropagationBehavior())
                    .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            SecurityContextHolder.getContext().setAuthentication(actor("transaction-ambient-c"));
            return new SimpleTransactionStatus();
        });

        service.recordStrict(actor("captured-a"), "FIXTURE_ACTION", "fixture", 7L, 8L, 9L, null, null, "fixture-details");

        var event = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(repository).save(event.capture());
        verify(transactions).commit(any());
        assertThat(event.getValue().getActor()).isEqualTo("captured-a");
        assertThat(event.getValue().getOrderId()).isEqualTo(8L);
        assertThat(event.getValue().getReviewId()).isEqualTo(9L);
        assertThat(event.getValue().getSource()).isEqualTo("cron_or_maintenance");
    }

    @Test
    void explicitSafeAuditKeepsCapturedActorAndBestEffortPersistenceContract() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(repository.save(any())).thenAnswer(call -> {
            BusinessAuditEvent event = call.getArgument(0);
            assertThat(event.getActor()).isEqualTo("captured-a");
            throw new IllegalStateException("fixture persistence unavailable");
        });
        assertThatCode(() -> service.recordSafely(actor("captured-a"), "FIXTURE_ACTION", "fixture", 7L,
                null, null, null, null, "fixture-details")).doesNotThrowAnyException();
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
    }

    @ParameterizedTest
    @MethodSource("invalidActors")
    void invalidExplicitActorIsRejectedBeforeAnyTransactionEvenForSafeAudit(Authentication invalid, boolean strict) {
        assertThatThrownBy(() -> {
            if (strict) service.recordStrict(invalid, "FIXTURE_ACTION", "fixture", 7L, null, null, null, null, null);
            else service.recordSafely(invalid, "FIXTURE_ACTION", "fixture", 7L, null, null, null, null, null);
        }).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(repository, transactions);
    }

    static Stream<Arguments> invalidActors() {
        return Stream.of(false, true).flatMap(strict -> Stream.of(
                Arguments.of(null, strict),
                Arguments.of(new AnonymousAuthenticationToken("fixture-key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))), strict),
                Arguments.of(new UsernamePasswordAuthenticationToken("unauthenticated", "unused"), strict),
                Arguments.of(actor(" \t "), strict)));
    }

    private static Authentication actor(String name) {
        return new UsernamePasswordAuthenticationToken(name, "unused", List.of(new SimpleGrantedAuthority("ROLE_WORKER")));
    }
}
