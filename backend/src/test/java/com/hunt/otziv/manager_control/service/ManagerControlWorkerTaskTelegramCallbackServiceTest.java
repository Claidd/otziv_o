package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.review_recovery.services.ReviewRecoveryTaskService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.model.WorkerRiskResolutionAction;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagerControlWorkerTaskTelegramCallbackServiceTest {

    @Mock
    private ManagerDailyControlConcreteItemRepository concreteItemRepository;
    @Mock
    private UserService userService;
    @Mock
    private PersonalReminderService personalReminderService;
    @Mock
    private BadReviewTaskService badReviewTaskService;
    @Mock
    private ReviewRecoveryTaskService reviewRecoveryTaskService;
    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private WorkerRiskIncidentRepository riskIncidentRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private TelegramService telegramService;

    private ManagerControlWorkerTaskTelegramCallbackService service;

    @BeforeEach
    void setUp() {
        service = new ManagerControlWorkerTaskTelegramCallbackService(
                concreteItemRepository,
                userService,
                personalReminderService,
                badReviewTaskService,
                reviewRecoveryTaskService,
                reviewRepository,
                orderRepository,
                riskIncidentRepository,
                userRepository,
                telegramService
        );
    }

    @Test
    void riskExplanationButtonFromBoundWorkerGroupDoesNotRequirePersonalTelegramBinding() {
        User worker = worker();
        WorkerRiskIncident incident = riskIncident();
        ManagerDailyControlConcreteItem item = riskConcreteItem();

        when(concreteItemRepository.findById(30L)).thenReturn(Optional.of(item));
        when(riskIncidentRepository.findById(77L)).thenReturn(Optional.of(incident));
        when(userRepository.findById(2L)).thenReturn(Optional.of(worker));
        when(userService.findByChatId(444L)).thenReturn(Optional.empty());

        Optional<String> answer = service.handle(callbackFromGroup(-100123L, 444L, "mc-task-risk-explain:30"));

        assertEquals(Optional.of("Напишите пояснение следующим сообщением"), answer);
        ArgumentCaptor<WorkerRiskIncident> captor = ArgumentCaptor.forClass(WorkerRiskIncident.class);
        verify(riskIncidentRepository).save(captor.capture());
        assertEquals(WorkerRiskResolutionAction.EXPLANATION_REQUESTED, captor.getValue().getResolutionAction());
        verify(telegramService).sendForceReplyMessage(eq(-100123L), any());
    }

    @Test
    void groupTextStoresPendingGeneralExplanationForBoundWorkerGroup() {
        User worker = worker();
        ManagerDailyControlConcreteItem item = generalConcreteItem();

        when(userService.findByChatId(444L)).thenReturn(Optional.empty());
        when(userRepository.findAllByWorkerTelegramGroupChatIdOrderById(-100123L)).thenReturn(List.of(worker));
        when(concreteItemRepository.findByWorkerNotificationUserIdAndWorkerExplanationRequestedAtIsNotNullAndWorkerExplanationAtIsNullOrderByWorkerExplanationPromptedAtDesc(2L))
                .thenReturn(List.of(item));
        when(concreteItemRepository.save(any(ManagerDailyControlConcreteItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        boolean handled = service.handleWorkerGroupTextMessage(-100123L, 444L, "Закрыла, потому что карточка была ошибочная.");

        assertEquals(true, handled);
        ArgumentCaptor<ManagerDailyControlConcreteItem> captor = ArgumentCaptor.forClass(ManagerDailyControlConcreteItem.class);
        verify(concreteItemRepository).save(captor.capture());
        assertEquals("Закрыла, потому что карточка была ошибочная.", captor.getValue().getWorkerExplanation());
        assertEquals(2L, captor.getValue().getWorkerExplanationByUserId());
        verify(telegramService).sendMessage(eq(-100123L), any(String.class));
    }

    @Test
    void acceptButtonOnlyAcknowledgesTaskWithoutRequestingExplanation() {
        User worker = worker();
        WorkerRiskIncident incident = riskIncident();
        ManagerDailyControlConcreteItem item = riskConcreteItem();

        when(concreteItemRepository.findById(30L)).thenReturn(Optional.of(item));
        when(riskIncidentRepository.findById(77L)).thenReturn(Optional.of(incident));
        when(userRepository.findById(2L)).thenReturn(Optional.of(worker));
        when(userService.findByChatId(444L)).thenReturn(Optional.of(worker));

        Optional<String> answer = service.handle(callbackFromGroup(-100123L, 444L, "mc-task-ack:30"));

        assertEquals(Optional.of("Принято. Менеджер увидит подтверждение."), answer);
        verify(concreteItemRepository).save(item);
        verify(personalReminderService).deleteSystemReminderBySource(
                eq(worker),
                eq(ManagerControlWorkerTaskTelegramCallbackService.SOURCE_WORKER_TASK_REQUEST),
                eq(30L)
        );
        verify(telegramService, never()).sendForceReplyMessage(anyLong(), any());
    }

    private CallbackQuery callbackFromGroup(long groupChatId, long actorTelegramId, String data) {
        Chat chat = new Chat();
        chat.setId(groupChatId);
        chat.setType("supergroup");

        Message message = new Message();
        message.setChat(chat);
        message.setMessageId(12);

        org.telegram.telegrambots.meta.api.objects.User from = new org.telegram.telegrambots.meta.api.objects.User();
        from.setId(actorTelegramId);

        CallbackQuery callbackQuery = new CallbackQuery();
        callbackQuery.setMessage(message);
        callbackQuery.setFrom(from);
        callbackQuery.setData(data);
        return callbackQuery;
    }

    private User worker() {
        User worker = new User();
        worker.setId(2L);
        worker.setUsername("worker");
        worker.setActive(true);
        worker.setWorkerTelegramGroupChatId(-100123L);
        return worker;
    }

    private WorkerRiskIncident riskIncident() {
        WorkerRiskIncident incident = new WorkerRiskIncident();
        incident.setId(77L);
        incident.setStatus(WorkerRiskIncidentStatus.OPEN);
        incident.setWorkerUserId(2L);
        incident.setWorkerUsername("worker");
        incident.setOrderId(100L);
        incident.setReviewId(200L);
        incident.setTitle("Подозрительное действие");
        return incident;
    }

    private ManagerDailyControlConcreteItem riskConcreteItem() {
        ManagerDailyControlConcreteItem item = new ManagerDailyControlConcreteItem();
        item.setId(30L);
        item.setEntityType("RISK");
        item.setEntityId(77L);
        item.setTitle("Риск специалиста");
        item.setReason("Нужно пояснение");
        return item;
    }

    private ManagerDailyControlConcreteItem generalConcreteItem() {
        ManagerDailyControlConcreteItem item = new ManagerDailyControlConcreteItem();
        item.setId(31L);
        item.setEntityType("PUBLISH_REVIEW");
        item.setEntityId(88L);
        item.setTitle("Публикация просрочена");
        item.setWorkerNotificationUserId(2L);
        return item;
    }
}
