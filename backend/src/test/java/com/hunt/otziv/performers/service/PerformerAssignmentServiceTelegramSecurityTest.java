package com.hunt.otziv.performers.service;

import com.hunt.otziv.c_cities.service.CityDistanceService;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.status.service.OrderStatusTransitionService;
import com.hunt.otziv.performers.model.PerformerAssignmentStatus;
import com.hunt.otziv.performers.model.PerformerOfferStatus;
import com.hunt.otziv.performers.model.PerformerProfile;
import com.hunt.otziv.performers.model.PerformerProfileStatus;
import com.hunt.otziv.performers.model.ReviewPerformerAssignment;
import com.hunt.otziv.performers.model.ReviewPerformerOffer;
import com.hunt.otziv.performers.repository.PerformerCityRepository;
import com.hunt.otziv.performers.repository.PerformerPayoutRepository;
import com.hunt.otziv.performers.repository.PerformerProfileRepository;
import com.hunt.otziv.performers.repository.PerformerTaskEvidenceRepository;
import com.hunt.otziv.performers.repository.ReviewPerformerAssignmentRepository;
import com.hunt.otziv.performers.repository.ReviewPerformerOfferRepository;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PerformerAssignmentServiceTelegramSecurityTest {

    @Mock private OrderRepository orderRepository;
    @Mock private ReviewRepository reviewRepository;
    @Mock private UserRepository userRepository;
    @Mock private PerformerProfileRepository performerProfileRepository;
    @Mock private PerformerCityRepository performerCityRepository;
    @Mock private ReviewPerformerAssignmentRepository assignmentRepository;
    @Mock private ReviewPerformerOfferRepository offerRepository;
    @Mock private PerformerTaskEvidenceRepository evidenceRepository;
    @Mock private PerformerPayoutRepository payoutRepository;
    @Mock private PerformerAssignmentMapper mapper;
    @Mock private PerformerTelegramNotificationService telegramNotificationService;
    @Mock private OrderStatusTransitionService orderStatusTransitionService;
    @Mock private PerformerRolloutService rolloutService;
    @Mock private PerformerAssignmentScreenshotStorage screenshotStorage;
    @Mock private CityDistanceService cityDistanceService;

    @InjectMocks private PerformerAssignmentService service;

    @ParameterizedTest
    @EnumSource(value = PerformerProfileStatus.class, names = {"NEW", "BLOCKED"})
    void telegramAcceptRejectsInactivePerformer(PerformerProfileStatus status) {
        ReviewPerformerOffer offer = offer(status, 700L);
        when(offerRepository.findByIdForAction(40L)).thenReturn(Optional.of(offer));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.acceptOfferFromTelegram(40L, 700L, 700L)
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        assertEquals("Профиль исполнителя еще не активирован", exception.getReason());
        verify(offerRepository, never()).save(offer);
        verifyNoInteractions(assignmentRepository, telegramNotificationService);
    }

    @ParameterizedTest
    @EnumSource(value = PerformerProfileStatus.class, names = {"NEW", "BLOCKED"})
    void telegramDeclineRejectsInactivePerformer(PerformerProfileStatus status) {
        ReviewPerformerOffer offer = offer(status, 700L);
        when(offerRepository.findByIdForAction(40L)).thenReturn(Optional.of(offer));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.declineOfferFromTelegram(40L, 700L, 700L)
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        assertEquals("Профиль исполнителя еще не активирован", exception.getReason());
        verify(offerRepository, never()).save(offer);
        verifyNoInteractions(assignmentRepository, telegramNotificationService);
    }

    @Test
    void telegramAcceptRejectsCallbackFromDifferentChat() {
        ReviewPerformerOffer offer = offer(PerformerProfileStatus.ACTIVE, 700L);
        when(offerRepository.findByIdForAction(40L)).thenReturn(Optional.of(offer));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.acceptOfferFromTelegram(40L, 700L, 999L)
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        assertEquals("Команда принадлежит другому Telegram-аккаунту", exception.getReason());
        verify(offerRepository, never()).save(offer);
        verifyNoInteractions(assignmentRepository, telegramNotificationService);
    }

    @Test
    void telegramDeclineRejectsCallbackFromDifferentSender() {
        ReviewPerformerOffer offer = offer(PerformerProfileStatus.ACTIVE, 700L);
        when(offerRepository.findByIdForAction(40L)).thenReturn(Optional.of(offer));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.declineOfferFromTelegram(40L, 999L, 700L)
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        assertEquals("Команда принадлежит другому Telegram-аккаунту", exception.getReason());
        verify(offerRepository, never()).save(offer);
        verifyNoInteractions(assignmentRepository, telegramNotificationService);
    }

    private ReviewPerformerOffer offer(PerformerProfileStatus status, Long telegramChatId) {
        User user = User.builder()
                .id(5L)
                .username("performer")
                .telegramChatId(telegramChatId)
                .active(true)
                .build();
        PerformerProfile performer = PerformerProfile.builder()
                .id(7L)
                .user(user)
                .status(status)
                .build();
        ReviewPerformerAssignment assignment = ReviewPerformerAssignment.builder()
                .id(9L)
                .status(PerformerAssignmentStatus.OFFERING)
                .build();
        return ReviewPerformerOffer.builder()
                .id(40L)
                .performer(performer)
                .assignment(assignment)
                .status(PerformerOfferStatus.OFFERED)
                .telegramChatId(telegramChatId)
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
    }
}
