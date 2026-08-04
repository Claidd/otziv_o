package com.hunt.otziv.r_review.controller;

import com.hunt.otziv.r_review.capability.model.ReviewCheckCapabilityScope;
import com.hunt.otziv.r_review.capability.service.ReviewCheckCapabilityService;
import com.hunt.otziv.r_review.capability.service.ReviewCheckCapabilityService.ResolvedCapability;
import com.hunt.otziv.r_review.controller.ApiReviewCheckController.ReviewCheckPermissions;
import com.hunt.otziv.r_review.controller.ApiReviewCheckController.ReviewCheckResponse;
import com.hunt.otziv.r_review.controller.ApiReviewCheckController.ReviewCheckReviewAnswerUpdateRequest;
import com.hunt.otziv.r_review.controller.ApiReviewCheckController.ReviewCheckReviewResponse;
import com.hunt.otziv.r_review.controller.ApiReviewCheckController.ReviewCheckReviewTextUpdateRequest;
import com.hunt.otziv.r_review.controller.ApiReviewCheckController.ReviewCheckUpdateRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hash-only capability API. The raw token is carried in a header so servlet,
 * reverse-proxy and audit request paths cannot accidentally log it.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/review-capability")
@Transactional
public class ApiSecureReviewCheckController {

    public static final String CAPABILITY_HEADER = "X-Review-Capability";
    private static final UUID OPAQUE_PUBLIC_ID = new UUID(0L, 0L);

    private final ReviewCheckCapabilityService capabilityService;
    private final ApiReviewCheckController legacyController;

    @GetMapping
    public ResponseEntity<ReviewCheckResponse> get(
            @RequestHeader(name = CAPABILITY_HEADER, required = false) String token
    ) {
        ResolvedCapability capability = resolve(token, ReviewCheckCapabilityScope.VIEW, "view");
        return noStore(restrict(legacyController.getReviewCheck(capability.orderDetailId(), null), capability));
    }

    @PutMapping
    public ResponseEntity<ReviewCheckResponse> save(
            @RequestHeader(name = CAPABILITY_HEADER, required = false) String token,
            @RequestBody ReviewCheckUpdateRequest request
    ) {
        ResolvedCapability capability = resolve(token, ReviewCheckCapabilityScope.EDIT, "edit");
        ReviewCheckResponse response = legacyController.saveReviews(capability.orderDetailId(), request, null);
        return noStore(restrict(response, capability));
    }

    @PutMapping("/reviews/{reviewId}/text")
    public ResponseEntity<ReviewCheckReviewResponse> updateText(
            @RequestHeader(name = CAPABILITY_HEADER, required = false) String token,
            @PathVariable Long reviewId,
            @RequestBody ReviewCheckReviewTextUpdateRequest request
    ) {
        ResolvedCapability capability = resolve(token, ReviewCheckCapabilityScope.EDIT, "edit");
        return noStore(publicReview(legacyController.updateReviewText(
                capability.orderDetailId(),
                reviewId,
                request,
                null
        )));
    }

    @PutMapping("/reviews/{reviewId}/answer")
    public ResponseEntity<ReviewCheckReviewResponse> updateAnswer(
            @RequestHeader(name = CAPABILITY_HEADER, required = false) String token,
            @PathVariable Long reviewId,
            @RequestBody ReviewCheckReviewAnswerUpdateRequest request
    ) {
        ResolvedCapability capability = resolve(token, ReviewCheckCapabilityScope.EDIT, "edit");
        return noStore(publicReview(legacyController.updateReviewAnswer(
                capability.orderDetailId(),
                reviewId,
                request,
                null
        )));
    }

    @PostMapping("/approve")
    public ResponseEntity<ReviewCheckResponse> approve(
            @RequestHeader(name = CAPABILITY_HEADER, required = false) String token,
            @RequestBody ReviewCheckUpdateRequest request,
            HttpServletRequest servletRequest
    ) throws Exception {
        ResolvedCapability capability = resolve(token, ReviewCheckCapabilityScope.APPROVE, "approve");
        ReviewCheckResponse response = legacyController.approveReviews(
                capability.orderDetailId(),
                editsAllowed(request, capability),
                null,
                servletRequest
        );
        return noStore(restrict(response, capability));
    }

    @PostMapping("/correction")
    public ResponseEntity<ReviewCheckResponse> correction(
            @RequestHeader(name = CAPABILITY_HEADER, required = false) String token,
            @RequestBody ReviewCheckUpdateRequest request
    ) throws Exception {
        ResolvedCapability capability = resolve(
                token,
                ReviewCheckCapabilityScope.SEND_CORRECTION,
                "correction"
        );
        ReviewCheckResponse response = legacyController.sendToCorrection(
                capability.orderDetailId(),
                editsAllowed(request, capability),
                null
        );
        return noStore(restrict(response, capability));
    }

    private ResolvedCapability resolve(
            String token,
            ReviewCheckCapabilityScope requiredScope,
            String action
    ) {
        return capabilityService.resolveAndTouch(token, requiredScope, action);
    }

    private ReviewCheckResponse restrict(ReviewCheckResponse response, ResolvedCapability capability) {
        ReviewCheckPermissions source = response.permissions();
        ReviewCheckPermissions restricted = new ReviewCheckPermissions(
                false,
                false,
                false,
                source.canApprovePublication() && capability.has(ReviewCheckCapabilityScope.APPROVE),
                source.canSave() && capability.has(ReviewCheckCapabilityScope.EDIT),
                source.canSendCorrection() && capability.has(ReviewCheckCapabilityScope.SEND_CORRECTION),
                false,
                false,
                false,
                false
        );

        // Never disclose the legacy UUID through a scoped opaque capability.
        return new ReviewCheckResponse(
                OPAQUE_PUBLIC_ID,
                null,
                null,
                response.companyTitle(),
                response.filialTitle(),
                response.status(),
                "",
                "",
                "",
                response.comment(),
                response.amount(),
                response.counter(),
                response.sum(),
                response.approved(),
                response.reviews().stream().map(this::publicReview).toList(),
                restricted
        );
    }

    private ReviewCheckReviewResponse publicReview(ReviewCheckReviewResponse review) {
        return new ReviewCheckReviewResponse(
                review.id(),
                review.text(),
                review.answer(),
                "",
                review.filialTitle(),
                "",
                "",
                "",
                review.productTitle(),
                review.productPhoto(),
                review.url(),
                review.publishedDate(),
                review.publish()
        );
    }

    private ReviewCheckUpdateRequest editsAllowed(
            ReviewCheckUpdateRequest request,
            ResolvedCapability capability
    ) {
        if (request == null || capability.has(ReviewCheckCapabilityScope.EDIT)) {
            return request;
        }
        return new ReviewCheckUpdateRequest(
                null,
                request.reviews() == null
                        ? null
                        : request.reviews().stream()
                                .map(review -> review == null
                                        ? null
                                        : new ApiReviewCheckController.ReviewCheckReviewUpdateRequest(
                                                review.id(),
                                                null,
                                                null,
                                                null,
                                                null,
                                                null
                                        ))
                                .toList()
        );
    }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(body);
    }
}
