package com.hunt.otziv.notification_media.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.hunt.otziv.config.settings.api.ContextualMediaSettings;
import com.hunt.otziv.notification_media.api.StaffMediaSignal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

class ContextualMediaTransactionTest {
    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean ContextualMediaFacts facts() { return mock(ContextualMediaFacts.class); }
        @Bean NotificationMediaDeliveryService delivery() { return mock(NotificationMediaDeliveryService.class); }
        @Bean ThematicNotificationDispatchStore dispatch() { return mock(ThematicNotificationDispatchStore.class); }
        @Bean ContextualMediaSettings settings() { return mock(ContextualMediaSettings.class); }
        @Bean ContextualStaffMediaService service(ContextualMediaFacts f, NotificationMediaDeliveryService d,
                ThematicNotificationDispatchStore s, ContextualMediaSettings settings) {
            return new ContextualStaffMediaService(f,d,s,settings);
        }
        @Bean AbstractPlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                protected Object doGetTransaction() { return new Object(); }
                protected void doBegin(Object transaction, TransactionDefinition definition) {}
                protected void doCommit(DefaultTransactionStatus status) {}
                protected void doRollback(DefaultTransactionStatus status) {}
            };
        }
    }

    @Test void successfulActionIsNotDeliveredBeforeCommitAndRollbackSuppressesIt() {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            var delivery=context.getBean(NotificationMediaDeliveryService.class);
            var tx=new TransactionTemplate(context.getBean(AbstractPlatformTransactionManager.class));
            tx.execute(status -> {
                context.publishEvent(new StaffMediaSignal(1,"REVIEW_PUBLISH","review",2L,2L,"publish"));
                verifyNoInteractions(delivery);
                status.setRollbackOnly();
                return null;
            });
            verifyNoInteractions(delivery,context.getBean(ContextualMediaFacts.class));
        }
    }
    @Test void committedActionRechecksFactsThenSends() {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            var delivery=context.getBean(NotificationMediaDeliveryService.class);
            var facts=context.getBean(ContextualMediaFacts.class);
            var settings=context.getBean(ContextualMediaSettings.class);
            var dispatch=context.getBean(ThematicNotificationDispatchStore.class);
            when(settings.enabled()).thenReturn(true);
            when(settings.maxPerDay()).thenReturn(2);
            when(facts.recipient(1)).thenReturn(Map.of("telegram_chat_id",100L));
            when(facts.ownedCard(1,"review",2L)).thenReturn(Map.of("published",true));
            when(dispatch.claim(anyString(),eq(1L),any(),eq(2))).thenReturn(true);
            new TransactionTemplate(context.getBean(AbstractPlatformTransactionManager.class)).execute(status -> {
                context.publishEvent(new StaffMediaSignal(1,"REVIEW_PUBLISH","review",2L,2L,"publish"));
                verifyNoInteractions(delivery);
                return null;
            });
            verify(delivery).sendMediaOnly(eq("WORKER_PUBLICATION_DONE"),eq(100L),eq(1L),anyString(),isNull());
        }
    }
}
