package com.hunt.otziv.s3.controller;

import com.hunt.otziv.s3.api.ReviewPhotoUploads;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ReviewFileControllerTest {
    @Test
    void forwardsActorAndReviewIdToTheSharedAuthorizedWorkflow() {
        ReviewPhotoUploads uploads = mock(ReviewPhotoUploads.class);
        MultipartFile file = mock(MultipartFile.class);
        Authentication actor = mock(Authentication.class);
        RedirectAttributes attributes = mock(RedirectAttributes.class);
        assertThat(new ReviewFileController(uploads).uploadPhoto(17L, file, actor, attributes))
                .isEqualTo("redirect:/review/editReview/17");
        verify(uploads).replace(17L, null, file, actor);
        verify(attributes).addFlashAttribute("saveSuccess", true);
    }

    @Test
    void deniedUploadDoesNotReportSuccess() {
        ReviewPhotoUploads uploads = mock(ReviewPhotoUploads.class);
        MultipartFile file = mock(MultipartFile.class);
        Authentication actor = mock(Authentication.class);
        RedirectAttributes attributes = mock(RedirectAttributes.class);
        doThrow(new org.springframework.security.access.AccessDeniedException("foreign review"))
                .when(uploads).replace(17L, null, file, actor);
        assertThatThrownBy(() -> new ReviewFileController(uploads).uploadPhoto(17L, file, actor, attributes))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(attributes);
    }

    @Test
    void unavailableReviewIsMappedToNotFoundAtTheHttpBoundary() {
        ReviewPhotoUploads uploads = mock(ReviewPhotoUploads.class);
        MultipartFile file = mock(MultipartFile.class);
        Authentication actor = mock(Authentication.class);
        RedirectAttributes attributes = mock(RedirectAttributes.class);
        doThrow(new com.hunt.otziv.p_products.api.ReviewPhotoRecords.Unavailable("Отзыв не найден"))
                .when(uploads).replace(17L, null, file, actor);
        assertThatThrownBy(() -> new ReviewFileController(uploads).uploadPhoto(17L, file, actor, attributes))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        verifyNoInteractions(attributes);
    }
}
