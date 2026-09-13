package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.client_chat_control.dto.PreparedNoResponseReview;
import com.hunt.otziv.client_chat_control.api.ClientChatNoResponseReviews;
import com.hunt.otziv.client_chat_control.service.ClientChatNoResponseAiReviewService;
import com.hunt.otziv.manager_control.dto.ManagerControlItemActionRequest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import org.springframework.transaction.TransactionDefinition;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagerControlNoResponseReviewWorkflowTest {
    @Test void providerRunsOutsideTransactionAndUnauthorizedReadNeverCallsIt() {
        var snapshot = mock(ManagerControlNoResponseSnapshot.class);
        var reviews = mock(ClientChatNoResponseReviews.class);
        var source = new PreparedNoResponseReview(5L, 8L, "Спасибо", LocalDateTime.now(), 10L, null);
        when(snapshot.read(1L, null, null)).thenReturn(source);
        var answer = new ClientChatNoResponseAiReviewService.Review(true, true, "NO_RESPONSE_NEEDED", 99, "Благодарность", "deepseek");
        when(reviews.review(source)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new PreparedNoResponseReview(source.itemId(), source.messageId(), source.messageText(),
                    source.messageAt(), source.managerId(), answer);
        });
        var manager = new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected boolean isExistingTransaction(Object transaction) {
                return TransactionSynchronizationManager.isActualTransactionActive();
            }
            protected Object doSuspend(Object transaction) { return transaction; }
            protected void doResume(Object transaction, Object resources) {}
            protected void doBegin(Object transaction, TransactionDefinition definition) {}
            protected void doCommit(DefaultTransactionStatus status) {}
            protected void doRollback(DefaultTransactionStatus status) {}
        };
        var proxy = new ProxyFactory(new ManagerControlNoResponseReviewWorkflow(snapshot, reviews));
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        var workflow = (ManagerControlNoResponseReviewWorkflow) proxy.getProxy();
        var request = new ManagerControlItemActionRequest("ACKNOWLEDGED", "", false);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(workflow.prepare(1L, request, null, null).review()).isSameAs(answer);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        });
        when(snapshot.read(2L, null, null)).thenThrow(new org.springframework.security.access.AccessDeniedException("denied"));
        assertThatThrownBy(() -> workflow.prepare(2L, request, null, null))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(reviews, times(1)).review(any());
        assertThat(workflow.prepare(1L, new ManagerControlItemActionRequest("ACTION_TAKEN", "", false), null, null)).isNull();
    }
}
