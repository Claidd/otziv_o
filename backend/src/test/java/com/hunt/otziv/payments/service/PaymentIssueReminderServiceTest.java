package com.hunt.otziv.payments.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.UserService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class PaymentIssueReminderServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private UserService userService;
    @Mock
    private PersonalReminderService personalReminderService;
    @Mock
    private PaymentIssueReminderTransactionExecutor transactionExecutor;
    @InjectMocks
    private PaymentIssueReminderService service;

    @Test
    void commonInvoiceCompletionTargetsOnlyItsOrderAndPreservesReminderRows() {
        service.completePaidCommonInvoiceOrderIssueInCurrentTransaction(182L, 25_686L);

        verify(personalReminderService).completeOpenSystemRemindersBySourceAndOrder(
                PaymentIssueReminderService.SOURCE_PAYMENT_FAIL_CLOSED, 182L, 25_686L);
        verifyNoMoreInteractions(personalReminderService);
    }

    @Test
    void createsOneSystemCardForResponsibleManagerOwnersAndAdmins() {
        User managerUser = user(10L, true);
        User owner = user(20L, true);
        User inactiveAdmin = user(30L, false);
        Order order = order(24378L, managerUser, null);
        when(orderRepository.findByIdForOrderDto(24378L)).thenReturn(Optional.of(order));
        when(userService.getAllOwners("ROLE_OWNER")).thenReturn(List.of(managerUser, owner));
        when(userService.getAllOwners("ROLE_ADMIN")).thenReturn(List.of(owner, inactiveAdmin));

        service.notifyOrderIssue(
                24378L,
                PaymentIssueReminderService.SOURCE_PAYMENT_FAIL_CLOSED,
                5208L,
                "Платёж требует внимания",
                "Проверьте оплату"
        );

        verify(personalReminderService).upsertSystemReminderDueNow(
                managerUser,
                "Платёж требует внимания",
                "Проверьте оплату",
                PaymentIssueReminderService.SOURCE_PAYMENT_FAIL_CLOSED,
                5208L,
                24378L
        );
        verify(personalReminderService).upsertSystemReminderDueNow(
                owner,
                "Платёж требует внимания",
                "Проверьте оплату",
                PaymentIssueReminderService.SOURCE_PAYMENT_FAIL_CLOSED,
                5208L,
                24378L
        );
        verifyNoMoreInteractions(personalReminderService);
    }

    @Test
    void fallsBackToCompanyManagerWhenOrderManagerMissing() {
        User companyManager = user(40L, true);
        Order order = order(25047L, null, companyManager);
        when(userService.getAllOwners("ROLE_OWNER")).thenReturn(List.of());
        when(userService.getAllOwners("ROLE_ADMIN")).thenReturn(List.of());

        service.notifyOrderIssue(order, "PAYMENT_FAIL_CLOSED", 25047L, "title", "text");

        verify(personalReminderService).upsertSystemReminderDueNow(
                companyManager, "title", "text", "PAYMENT_FAIL_CLOSED", 25047L, 25047L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAYMENT_FAIL_CLOSED", "PAYMENT_LIVE_ROUTING_FAIL_CLOSED"})
    void databaseDeadlockDetailsStayOutOfPersonalReminder(String sourceType) {
        User manager = user(41L, true);
        Order order = order(27_202L, manager, null);
        String technicalError = "JDBC exception executing SQL [Deadlock found when trying to get lock; "
                + "try restarting transaction] [ SELECT u.active FROM users u WHERE u.id = ? FOR UPDATE ]";
        ArgumentCaptor<String> reminderText = ArgumentCaptor.forClass(String.class);

        service.notifyOrderIssue(order, sourceType, order.getId(), "Платёж требует внимания", technicalError);

        verify(personalReminderService).upsertSystemReminderDueNow(
                eq(manager), eq("Платёж требует внимания"), reminderText.capture(),
                eq(sourceType), eq(order.getId()), eq(order.getId()));
        assertTrue(reminderText.getValue().contains("временной блокировки базы данных"));
        assertTrue(reminderText.getValue().contains("Проверьте текущий статус заказа и платёжную ссылку"));
        assertFalse(reminderText.getValue().contains("JDBC"));
        assertFalse(reminderText.getValue().contains("SELECT"));
    }

    @Test
    void ordinaryBusinessReasonRemainsVisible() {
        User manager = user(42L, true);
        Order order = order(27_203L, manager, null);
        String businessReason = "Платёжный профиль не настроен. Проверьте реквизиты получателя.";

        service.notifyOrderIssue(order, "PAYMENT_FAIL_CLOSED", order.getId(), "Платёж требует внимания", businessReason);

        verify(personalReminderService).upsertSystemReminderDueNow(
                manager, "Платёж требует внимания", businessReason,
                "PAYMENT_FAIL_CLOSED", order.getId(), order.getId());
    }

    @Test
    void ignoresMissingOrderId() {
        service.notifyOrderIssueAfterCommit(null, "PAYMENT_FAIL_CLOSED", 1L, "title", "text");

        verifyNoInteractions(orderRepository, userService, personalReminderService);
    }

    @Test
    void successfulRetryClearsRouteChangeIssueForEveryRecipient() {
        service.resolveOrderIssue(25_048L, "PAYMENT_ROUTE_CHANGE_DELIVERY", 7_262L);

        verify(personalReminderService).deleteSystemRemindersBySource(
                "PAYMENT_ROUTE_CHANGE_DELIVERY", 7_262L);
        verifyNoMoreInteractions(personalReminderService);
    }

    @Test
    void resolvedReserveWarningCompletesExistingCardsWithoutDeletingHistory() {
        when(personalReminderService.completeOpenSystemRemindersBySourceAndOrder(
                "CONTRACTOR_RESERVE_OVERRUN", 16L, 26649L)).thenReturn(2);

        assertEquals(2, service.completeOrderIssue(26649L, "CONTRACTOR_RESERVE_OVERRUN", 16L));

        verify(personalReminderService).completeOpenSystemRemindersBySourceAndOrder(
                "CONTRACTOR_RESERVE_OVERRUN", 16L, 26649L);
        verifyNoMoreInteractions(personalReminderService);
    }

    @Test
    void durablePathPropagatesReminderPersistenceFailure() {
        User managerUser = user(70L, true);
        Order order = order(25_049L, managerUser, null);
        when(userService.getAllOwners("ROLE_OWNER")).thenReturn(List.of());
        when(userService.getAllOwners("ROLE_ADMIN")).thenReturn(List.of());
        doThrow(new IllegalStateException("storage unavailable"))
                .when(personalReminderService)
                .upsertSystemReminderDueNow(
                        managerUser, "title", "text", "PAYMENT_RETURN_RECONCILIATION", 99L, 25_049L);

        assertThrows(IllegalStateException.class, () -> service.ensureOrderIssuePersisted(
                order, "PAYMENT_RETURN_RECONCILIATION", 99L, "title", "text"));
    }

    private Order order(Long id, User orderManagerUser, User companyManagerUser) {
        Order order = new Order();
        order.setId(id);
        if (orderManagerUser != null) {
            Manager manager = new Manager();
            manager.setUser(orderManagerUser);
            order.setManager(manager);
        }
        if (companyManagerUser != null) {
            Manager manager = new Manager();
            manager.setUser(companyManagerUser);
            Company company = new Company();
            company.setManager(manager);
            order.setCompany(company);
        }
        return order;
    }

    private User user(Long id, boolean active) {
        User user = new User();
        user.setId(id);
        user.setActive(active);
        return user;
    }
}
