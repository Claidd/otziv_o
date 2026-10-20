package com.hunt.otziv.notification_media.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hunt.otziv.notification_media.api.StaffMediaSignal;
import com.hunt.otziv.notification_media.service.ContextualMediaFacts;
import com.hunt.otziv.notification_media.service.WorkerMediaContextService;
import com.hunt.otziv.u_users.api.DeferredUserAuthority;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.service.DeferredUserAuthorityService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

class ApiWorkerMediaContextRestTest {
    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean UserRepository users() { return mock(UserRepository.class); }
        @Bean ContextualMediaFacts facts() { return mock(ContextualMediaFacts.class); }
        @Bean DeferredUserAuthorityService authority(UserRepository users) {
            return new DeferredUserAuthorityService(users);
        }
        @Bean WorkerMediaContextService service(DeferredUserAuthority authority, ContextualMediaFacts facts,
                                                org.springframework.context.ApplicationEventPublisher events) {
            return new WorkerMediaContextService(authority, facts, events);
        }
        @Bean ApiWorkerMediaContextController controller(WorkerMediaContextService service) {
            return new ApiWorkerMediaContextController(service);
        }
        @Bean EventSink events() { return new EventSink(); }
        @Bean AbstractPlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                private final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> false);
                protected Object doGetTransaction() { return new Object(); }
                protected boolean isExistingTransaction(Object transaction) { return active.get(); }
                protected void doBegin(Object transaction, TransactionDefinition definition) { active.set(true); }
                protected void doCommit(DefaultTransactionStatus status) {}
                protected void doRollback(DefaultTransactionStatus status) {}
                protected void doCleanupAfterCompletion(Object transaction) { active.remove(); }
            };
        }
    }

    static class EventSink {
        final List<StaffMediaSignal> received = new ArrayList<>();
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void onSignal(StaffMediaSignal signal) { received.add(signal); }
    }

    @Test void authenticatedWorkerCanPostContextWithoutMandatoryTransactionFailure() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var user = new User();
            user.setId(4L);
            user.setUsername("worker");
            user.setActive(true);
            var role = new Role();
            role.setName("ROLE_WORKER");
            user.setRoles(List.of(role));
            when(context.getBean(UserRepository.class).findByUsername("worker")).thenReturn(Optional.of(user));
            when(context.getBean(ContextualMediaFacts.class).ownedCard(4L, "review", 9L))
                    .thenReturn(Map.of("id", 9L));

            var authentication = UsernamePasswordAuthenticationToken.authenticated("worker", null,
                    List.of(new SimpleGrantedAuthority("ROLE_WORKER")));
            var mvc = MockMvcBuilders.standaloneSetup(context.getBean(ApiWorkerMediaContextController.class)).build();
            mvc.perform(post("/api/worker/notification-media/context")
                            .principal(authentication)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"action":"UNSAVED_CHANGES","entityType":"review","entityId":9,"section":"publish"}
                                    """))
                    .andExpect(status().isNoContent());
            verify(context.getBean(ContextualMediaFacts.class)).ownedCard(4L, "review", 9L);
            mvc.perform(post("/api/worker/notification-media/context")
                            .principal(authentication)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"action":"UNSAVED_CHANGES","entityType":"review","entityId":10,"section":"publish"}
                                    """))
                    .andExpect(status().isNotFound());
            org.assertj.core.api.Assertions.assertThat(context.getBean(EventSink.class).received)
                    .containsExactly(new StaffMediaSignal(4L, "UNSAVED_CHANGES", "review", 9L, null, "publish"));
        }
    }
}
