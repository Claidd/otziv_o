package com.hunt.otziv.performers.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.performers.dto.PerformerAssignmentResponse;
import com.hunt.otziv.performers.model.ReviewPerformerAssignment;
import com.hunt.otziv.performers.model.ReviewPerformerOffer;
import com.hunt.otziv.performers.repository.ReviewPerformerOfferRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PerformerAssignmentMapper {

    private final ReviewPerformerOfferRepository offerRepository;

    public PerformerAssignmentResponse toResponse(ReviewPerformerAssignment assignment) {
        Long offerId = currentOfferId(assignment);
        Company company = assignment.getOrder() != null ? assignment.getOrder().getCompany() : null;
        Filial filial = assignment.getFilial();
        String cityTitle = assignment.getCity() != null ? safe(assignment.getCity().getTitle()) : "";
        String externalConfirmStatus = assignment.getReview() != null ? safe(assignment.getReview().getExternalConfirmStatus()) : "";
        String externalConfirmScreenshotUrl = assignment.getReview() != null ? safe(assignment.getReview().getExternalConfirmScreenshotUrl()) : "";

        return new PerformerAssignmentResponse(
                assignment.getId(),
                assignment.getOrder() != null ? assignment.getOrder().getId() : null,
                assignment.getReview() != null ? assignment.getReview().getId() : null,
                offerId,
                company != null ? safe(company.getTitle()) : "",
                filial != null ? safe(filial.getTitle()) : "",
                cityTitle,
                assignment.getPlatform() != null ? assignment.getPlatform().name() : "",
                assignment.getStatus() != null ? assignment.getStatus().name() : "",
                externalConfirmStatus,
                externalConfirmScreenshotUrl,
                safe(assignment.getClientApprovedTextSnapshot()),
                safe(assignment.getPerformerFinalText()),
                safe(assignment.getInstruction()),
                safe(assignment.getPublicationUrl()),
                safe(assignment.getPerformerPublicationScreenshotUrl()),
                safe(assignment.getManagerConfirmationScreenshotUrl()),
                dateTime(assignment.getAcceptedAt()),
                dateTime(assignment.getWalkedAt()),
                dateTime(assignment.getPublishAvailableAt()),
                dateTime(assignment.getPublishedClaimedAt()),
                dateTime(assignment.getVerifiedAt()),
                assignment.getPayoutAmount()
        );
    }

    private Long currentOfferId(ReviewPerformerAssignment assignment) {
        if (assignment == null || assignment.getId() == null) {
            return null;
        }
        List<ReviewPerformerOffer> offers = offerRepository.findByAssignmentIdAndStatuses(
                assignment.getId(),
                List.of(com.hunt.otziv.performers.model.PerformerOfferStatus.OFFERED)
        );
        return offers.isEmpty() ? null : offers.getFirst().getId();
    }

    private String dateTime(LocalDateTime value) {
        return value == null ? "" : value.toString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
