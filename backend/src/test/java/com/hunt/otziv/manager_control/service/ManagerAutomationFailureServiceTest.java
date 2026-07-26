package com.hunt.otziv.manager_control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ClientMessageTargetType;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.u_users.model.Manager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ManagerAutomationFailureServiceTest {

    @Mock
    private ScheduledClientMessageStateRepository stateRepository;
    @Spy
    private ManagerAutomationFailurePolicy policy = new ManagerAutomationFailurePolicy();
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private CommonInvoiceOrderRepository commonInvoiceOrderRepository;
    @InjectMocks
    private ManagerAutomationFailureService service;

    @BeforeEach
    void enableFeature() {
        when(appSettingService.getBoolean(
                AppSettingService.MANAGER_CONTROL_AUTOMATION_FAILURES_ENABLED,
                true
        )).thenReturn(true);
        when(appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_MANUAL_CONTROL_FAILURE_THRESHOLD,
                3
        )).thenReturn(3);
        when(appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_MANUAL_CONTROL_AFTER_MINUTES,
                60
        )).thenReturn(60);
    }

    @Test
    void exposesPaymentFailureForOwningManager() {
        Manager manager = Manager.builder().id(7L).build();
        Company company = Company.builder().id(20L).title("Калибр").manager(manager).urlChat("https://chat.example").build();
        Order order = Order.builder().id(24129L).manager(manager).company(company).build();
        ScheduledClientMessageState state = state(11L, 24129L, ClientMessageScenario.PAYMENT_REMINDER, 1);
        when(stateRepository.findManagerControlCandidateIds(eq(7L), any(Pageable.class))).thenReturn(List.of(11L));
        when(stateRepository.findAllById(List.of(11L))).thenReturn(List.of(state));
        when(orderRepository.findByIdForOrderDto(24129L)).thenReturn(Optional.of(order));
        when(commonInvoiceOrderRepository.findByOrderIdWithInvoice(24129L)).thenReturn(Optional.empty());

        List<ManagerAutomationFailureService.AutomationFailureIssue> issues = service.issues(manager, 10);

        assertEquals(1, issues.size());
        assertEquals(ManagerAutomationFailureService.ENTITY_AUTOMATION_FAILURE, issues.getFirst().entityType());
        assertEquals(11L, issues.getFirst().entityId());
        assertTrue(issues.getFirst().reason().contains("payment_instruction_failed"));
    }

    @Test
    void collapsesSeveralFailuresOfOneCommonInvoice() {
        Manager manager = Manager.builder().id(7L).build();
        Company company = Company.builder().id(20L).title("Компания").manager(manager).build();
        Order firstOrder = Order.builder().id(100L).manager(manager).company(company).build();
        Order secondOrder = Order.builder().id(101L).manager(manager).company(company).build();
        ScheduledClientMessageState first = state(11L, 100L, ClientMessageScenario.PAYMENT_REMINDER, 3);
        ScheduledClientMessageState second = state(12L, 101L, ClientMessageScenario.BAD_REVIEW_INVOICE, 5);
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(115L);
        invoice.setTitle("Общий счет 115");
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        CommonInvoiceOrder firstLink = new CommonInvoiceOrder();
        firstLink.setInvoice(invoice);
        CommonInvoiceOrder secondLink = new CommonInvoiceOrder();
        secondLink.setInvoice(invoice);

        when(stateRepository.findManagerControlCandidateIds(eq(7L), any(Pageable.class))).thenReturn(List.of(11L, 12L));
        when(stateRepository.findAllById(List.of(11L, 12L))).thenReturn(List.of(first, second));
        when(orderRepository.findByIdForOrderDto(100L)).thenReturn(Optional.of(firstOrder));
        when(orderRepository.findByIdForOrderDto(101L)).thenReturn(Optional.of(secondOrder));
        when(commonInvoiceOrderRepository.findByOrderIdWithInvoice(100L)).thenReturn(Optional.of(firstLink));
        when(commonInvoiceOrderRepository.findByOrderIdWithInvoice(101L)).thenReturn(Optional.of(secondLink));

        List<ManagerAutomationFailureService.AutomationFailureIssue> issues = service.issues(manager, 10);

        assertEquals(1, issues.size());
        assertEquals(ManagerAutomationFailureService.ENTITY_COMMON_INVOICE_AUTOMATION, issues.getFirst().entityType());
        assertEquals(115L, issues.getFirst().entityId());
        assertEquals(12L, issues.getFirst().stateId());
    }

    private ScheduledClientMessageState state(
            Long id,
            Long orderId,
            ClientMessageScenario scenario,
            int failures
    ) {
        LocalDateTime now = LocalDateTime.now();
        return ScheduledClientMessageState.builder()
                .id(id)
                .scenario(scenario)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:" + orderId + ":" + id)
                .orderId(orderId)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .lastErrorCode("payment_instruction_failed")
                .lastErrorMessage("Не удалось получить платежную инструкцию")
                .consecutiveFailures(failures)
                .lastAttemptAt(now.minusMinutes(5))
                .createdAt(now.minusDays(1))
                .updatedAt(now.minusMinutes(5))
                .build();
    }
}
