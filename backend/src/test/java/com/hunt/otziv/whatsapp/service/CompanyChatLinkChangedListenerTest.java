package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.c_companies.api.CompanyChatLinkChanged;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompanyChatLinkChangedListenerTest {
    @Configuration @EnableTransactionManagement
    static class Config { }

    @Test
    void verifiesExactInviteOnlyAfterCommitAndOutsideTheCompanyTransaction() {
        var whatsapp = mock(WhatsAppGroupLinkSyncService.class);
        var event = new CompanyChatLinkChanged(2505L, "https://chat.whatsapp.com/NewInvite123456");
        when(whatsapp.repairChangedCompanyLink(event.companyId(),event.urlChat())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new WhatsAppGroupLinkSyncService.WhatsAppGroupRepairResult(true,"verified");
        });
        try (var context = context(whatsapp)) {
            new TransactionTemplate(context.getBean(AbstractPlatformTransactionManager.class)).executeWithoutResult(status -> {
                context.publishEvent(event);
                verifyNoInteractions(whatsapp);
            });
            verify(whatsapp).repairChangedCompanyLink(event.companyId(),event.urlChat());
        }
    }

    @Test
    void cancellingOrRollingBackTheEditDoesNotVerifyOrChangeAnotherChat() {
        var whatsapp = mock(WhatsAppGroupLinkSyncService.class);
        try (var context = context(whatsapp)) {
            new TransactionTemplate(context.getBean(AbstractPlatformTransactionManager.class)).executeWithoutResult(status -> {
                context.publishEvent(new CompanyChatLinkChanged(2505L,"https://chat.whatsapp.com/NewInvite123456"));
                status.setRollbackOnly();
            });
            verifyNoInteractions(whatsapp);
        }
    }

    private AnnotationConfigApplicationContext context(WhatsAppGroupLinkSyncService whatsapp) {
        var context = new AnnotationConfigApplicationContext();
        context.register(Config.class);
        context.registerBean(WhatsAppGroupLinkSyncService.class, () -> whatsapp);
        context.registerBean(CompanyChatLinkChangedListener.class);
        context.registerBean(AbstractPlatformTransactionManager.class, () -> new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object transaction, TransactionDefinition definition) { }
            protected void doCommit(DefaultTransactionStatus status) { }
            protected void doRollback(DefaultTransactionStatus status) { }
            protected Object doSuspend(Object transaction) { return transaction; }
            protected void doResume(Object transaction, Object suspended) { }
        });
        context.refresh();
        return context;
    }
}
