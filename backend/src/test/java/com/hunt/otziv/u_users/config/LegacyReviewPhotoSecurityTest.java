package com.hunt.otziv.u_users.config;

import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.p_products.application.ReviewPhotoRecordService;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.worker_access.repository.WorkerAssignmentMutationGuardRepository;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.photo.ReviewPhotoReferencePolicy;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.s3.api.ReviewPhotoUploads;
import com.hunt.otziv.s3.cleanup.service.S3UploadRegistry;
import com.hunt.otziv.s3.controller.ReviewFileController;
import com.hunt.otziv.s3.service.ReviewPhotoUploadService;
import com.hunt.otziv.s3.service.S3UploadServiceImpl;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual SecurityConfig, MVC binding, workflow and staff access policy; persistence is a fixed ownership fixture. */
class LegacyReviewPhotoSecurityTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach void setup() { start(true); }
    @AfterEach void close() { if (context != null) context.close(); }

    void start(boolean legacyEnabled) {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new org.springframework.mock.web.MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("photo-test", Map.of(
                "otziv.legacy.enabled", Boolean.toString(legacyEnabled),
                "jwt.secret", "test-only-legacy-photo-secret-with-32-bytes")));
        context.register(PhotoConfiguration.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
    }

    @Test void foreignReviewIsRejectedForEveryLegacyStaffRoleBeforeUpload() throws Exception {
        for (String role : List.of("WORKER", "MANAGER", "OPERATOR")) {
            int status = mvc.perform(request(18, role)).andReturn().getResponse().getStatus();
            assertThat(status).isIn(403, 404, 409, 302);
        }
        verifyNoInteractions(context.getBean(S3UploadServiceImpl.class));
        verify(context.getBean(ReviewRepository.class), never()).save(any());
    }

    @Test void assignedWorkerUsesTheSameAuthorizedUploadWorkflow() throws Exception {
        mvc.perform(request(17, "WORKER")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/review/editReview/17"))
                .andExpect(flash().attribute("saveSuccess", true));
        verify(context.getBean(S3UploadServiceImpl.class)).stageReviewPhoto(any(), eq(17L));
        verify(context.getBean(ReviewRepository.class)).save(any());
    }

    @Test void disabledLegacyDoesNotRegisterTheUploadController() throws Exception {
        close(); start(false);
        assertThat(context.getBeansOfType(ReviewFileController.class)).isEmpty();
        assertThat(mvc.perform(request(17, "WORKER")).andReturn().getResponse().getStatus()).isIn(401, 403, 404, 302);
        verifyNoInteractions(context.getBean(S3UploadServiceImpl.class));
    }

    private RequestBuilder request(long id, String role) {
        MockHttpSession session = new MockHttpSession();
        var actor = new UsernamePasswordAuthenticationToken(role.toLowerCase(), "unused",
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(actor));
        var token = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "photo-test-csrf");
        session.setAttribute(HttpSessionCsrfTokenRepository.class.getName() + ".CSRF_TOKEN", token);
        byte[] value = token.getToken().getBytes(StandardCharsets.UTF_8);
        byte[] xor = new byte[value.length * 2];
        for (int index = 0; index < value.length; index++) {
            xor[index] = (byte) (0x5A ^ index);
            xor[value.length + index] = (byte) (xor[index] ^ value[index]);
        }
        return multipart("/reviews/{id}/upload-photo", id)
                .file(new MockMultipartFile("file", "photo.png", "image/png", new byte[] {1, 2, 3}))
                .session(session).param("_csrf", Base64.getUrlEncoder().encodeToString(xor));
    }

    @Configuration(proxyBeanMethods = false)
    @Import({LegacyBotSecurityContractTest.TestConfiguration.class, ReviewFileController.class})
    static class PhotoConfiguration {
        @Bean S3UploadServiceImpl photoStorage() {
            var storage = mock(S3UploadServiceImpl.class);
            when(storage.stageReviewPhoto(any(), eq(17L))).thenReturn(new S3UploadRegistry.Upload(
                    "fixture", "bucket", "reviews/17-new.jpg", "https://cdn.test/reviews/17-new.jpg", 17));
            return storage;
        }
        @Bean ReviewRepository photoReviews() {
            var repository = mock(ReviewRepository.class);
            when(repository.findById(17L)).thenReturn(Optional.of(Review.builder().id(17L).url("old").build()));
            when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            return repository;
        }
        @Bean ReviewPhotoUploads photoUploads(S3UploadServiceImpl storage, ReviewRepository reviews) {
            var ownership = mock(WorkerAssignmentMutationGuardRepository.class);
            when(ownership.findOrderIdByReviewId(anyLong())).thenAnswer(i -> Optional.of(i.<Long>getArgument(0) == 17L ? 10L : 20L));
            when(ownership.findCurrentOrderIdByReviewId(anyLong())).thenAnswer(i -> Optional.of(i.<Long>getArgument(0) == 17L ? 10L : 20L));
            when(ownership.findCurrentManagerIdByOrderId(anyLong())).thenAnswer(i -> Optional.of(i.<Long>getArgument(0) == 10L ? 1L : 2L));
            when(ownership.lockOwnedReview(17L, "worker")).thenReturn(Optional.of(17L));
            var managers = mock(ManagerAccessService.class);
            when(managers.canAccessCurrentOrderManager(eq(1L), any(Authentication.class))).thenReturn(true);
            var access = new WorkerAssignmentMutationGuardService(ownership, managers, mock(OrderAggregateMutationLockService.class));
            var records = new ReviewPhotoRecordService(access, reviews, mock(ReviewPhotoReferencePolicy.class));
            return new ReviewPhotoUploadService(records, storage, mock(S3UploadRegistry.class), new FixtureTransactions());
        }
    }

    /** Exercises Spring's actual transaction lifecycle without inventing database behavior in this MVC test. */
    static class FixtureTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {}
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }
}
