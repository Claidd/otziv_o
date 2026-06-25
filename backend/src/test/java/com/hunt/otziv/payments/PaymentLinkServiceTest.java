package com.hunt.otziv.payments;

import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.service.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderTransactionService;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import com.hunt.otziv.payments.dto.AdminPaymentLinksPageResponse;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.dto.PaymentLinkAdminSummary;
import com.hunt.otziv.payments.dto.PublicPaymentInitResponse;
import com.hunt.otziv.payments.dto.PublicSbpBankResponse;
import com.hunt.otziv.payments.dto.TbankCancelCommand;
import com.hunt.otziv.payments.dto.TbankCancelResponse;
import com.hunt.otziv.payments.dto.TbankGetQrBankListCommand;
import com.hunt.otziv.payments.dto.TbankGetQrBankListResponse;
import com.hunt.otziv.payments.dto.TbankGetQrCommand;
import com.hunt.otziv.payments.dto.TbankGetQrResponse;
import com.hunt.otziv.payments.dto.TbankGetStateResponse;
import com.hunt.otziv.payments.dto.TbankInitCommand;
import com.hunt.otziv.payments.dto.TbankInitResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.ManualPaymentTaskStatus;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentPolicy;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.model.PaymentReceiptStatus;
import com.hunt.otziv.payments.model.TbankRuntimeMode;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.ManualPaymentTaskService;
import com.hunt.otziv.payments.service.PaymentLinkArchiveService;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.PaymentSuccessClientNotifier;
import com.hunt.otziv.payments.service.TbankClient;
import com.hunt.otziv.payments.service.TbankRuntimeSettingsService;
import com.hunt.otziv.payments.service.TbankTokenSigner;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentLinkServiceTest {

    @Mock
    private PaymentLinkRepository paymentLinkRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private BadReviewTaskService badReviewTaskService;

    @Mock
    private OrderTransactionService orderTransactionService;

    @Mock
    private TbankClient tbankClient;

    @Mock
    private PaymentProfileService paymentProfileService;

    @Mock
    private TbankRuntimeSettingsService runtimeSettingsService;

    @Mock
    private PaymentSuccessClientNotifier paymentSuccessClientNotifier;

    @Mock
    private ManualPaymentTaskService manualPaymentTaskService;

    @Mock
    private PaymentInvoiceRetryScheduler paymentInvoiceRetryScheduler;

    @Mock
    private PaymentLinkArchiveService paymentLinkArchiveService;

    @Mock
    private AppSettingService appSettingService;

    @Test
    void createForOrderDoesNotMarkOuterClientMessageTransactionRollbackOnlyOnBusinessConflict() throws Exception {
        Transactional transactional = PaymentLinkService.class
                .getMethod("createForOrder", Long.class)
                .getAnnotation(Transactional.class);

        assertNotNull(transactional);
        assertTrue(List.of(transactional.noRollbackFor()).contains(ResponseStatusException.class));
    }

    @Test
    void createForOrderBuildsHiddenTokenizedLinkWithPayableAmount() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        Order order = order(10L, "ООО Ромашка", BigDecimal.valueOf(1000));
        order.getCompany().setLastPayerEmail(" LAST@EXAMPLE.RU ");

        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(10L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(orderRepository.findByIdForMutation(10L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(10L))
                .thenReturn(new BadReviewTaskSummary(1, 0, 1, 0, BigDecimal.valueOf(250.50), BigDecimal.ZERO));
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ManagerPaymentLinkResponse response = service.createForOrder(10L);

        assertNotNull(response.token());
        assertFalse(response.token().isBlank());
        assertEquals("https://example.ru/pay/" + response.token(), response.url());
        assertEquals(BigDecimal.valueOf(125050, 2), response.amount());
        assertEquals(125050L, response.amountKopecks());
        assertTrue(response.copyText().contains("Ссылка на оплату: https://example.ru/pay/"));

        ArgumentCaptor<PaymentLink> captor = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository).save(captor.capture());
        assertEquals("last@example.ru", captor.getValue().getPayerEmail());
        assertEquals(TbankPaymentProfile.PRIMARY_CODE, captor.getValue().getPaymentProfileCode());
        assertEquals("Основной магазин", captor.getValue().getPaymentProfileName());
    }

    @Test
    void createForOrderSelectsSecondaryProfileForConfiguredManager() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        PaymentProfile secondaryProfile = profile(2L, TbankPaymentProfile.SECONDARY_CODE, "Второй магазин", "secondary-terminal");
        when(paymentProfileService.selectForManager(any())).thenReturn(secondaryProfile);
        Order order = order(11L, "ООО Второй", BigDecimal.valueOf(500));
        order.setManager(manager("second-manager"));

        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(11L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(orderRepository.findByIdForMutation(11L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(11L)).thenReturn(null);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.createForOrder(11L);

        ArgumentCaptor<PaymentLink> captor = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository).save(captor.capture());
        assertEquals(TbankPaymentProfile.SECONDARY_CODE, captor.getValue().getPaymentProfileCode());
        assertEquals("Второй магазин", captor.getValue().getPaymentProfileName());
    }

    @Test
    void createForOrderRoutesToManualPaymentWhenPolicyAndMonthlyLimitAllowIt() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        PaymentProfile profile = profile(3L, "manual", "Ручной профиль", "manual-terminal");
        profile.setPaymentPolicy(PaymentPolicy.MANUAL_UNTIL_LIMIT_THEN_TBANK);
        profile.setManualPhone("+79990000000");
        profile.setManualRecipientName("Иван И.");
        profile.setManualComment("Оплата заказа №{orderId}");
        profile.setManualMonthlyHardLimitKopecks(100000L);
        when(paymentProfileService.selectForManager(any())).thenReturn(profile);
        when(paymentLinkRepository.sumManualReservedAndConfirmedForPeriod(
                eq(3L),
                anyCollection(),
                anyCollection(),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(PaymentLinkStatus.class),
                any()
        )).thenReturn(40000L);
        Order order = order(12L, "ООО Ручная", BigDecimal.valueOf(500));

        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(12L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(orderRepository.findByIdForMutation(12L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(12L)).thenReturn(null);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ManagerPaymentLinkResponse response = service.createForOrder(12L);

        ArgumentCaptor<PaymentLink> captor = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository).save(captor.capture());
        PaymentLink link = captor.getValue();
        assertEquals(PaymentMethod.MANUAL_MOBILE_BANK, link.getPaymentMethod());
        assertEquals(PaymentLinkStatus.WAITING_MANUAL_PAYMENT, link.getStatus());
        assertEquals(50000L, link.getReservedAmountKopecks());
        assertEquals("+79990000000", link.getManualPhone());
        assertEquals("Иван И.", link.getManualRecipientName());
        assertEquals("Оплата заказа №12", link.getManualComment());
        assertEquals(PaymentReceiptStatus.PENDING, link.getReceiptStatus());
        assertEquals("MANUAL_MOBILE_BANK", response.paymentMethod());
        assertTrue(response.instructionText().contains("Оплата по мобильному банку: +79990000000"));
        assertTrue(response.instructionText().contains("Получатель: Иван И."));
        assertTrue(response.instructionText().contains("Комментарий: Оплата заказа №12"));
        assertFalse(response.copyText().contains("https://example.ru/pay/"));
        assertTrue(response.copyText().contains("После оплаты отправьте чек в этот чат."));
    }

    @Test
    void createForOrderLabelsLongManualNumberAsCardPayment() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        PaymentProfile profile = profile(33L, "manual-card", "Ручной профиль", "manual-terminal");
        profile.setPaymentPolicy(PaymentPolicy.MANUAL_UNTIL_LIMIT_THEN_TBANK);
        profile.setManualPhone("2202201901120051");
        profile.setManualRecipientName("Мария Олеговна Р.");
        profile.setManualMonthlyHardLimitKopecks(100000L);
        when(paymentProfileService.selectForManager(any())).thenReturn(profile);
        when(paymentLinkRepository.sumManualReservedAndConfirmedForPeriod(
                eq(33L),
                anyCollection(),
                anyCollection(),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(PaymentLinkStatus.class),
                any()
        )).thenReturn(0L);
        Order order = order(33L, "ООО Карта", BigDecimal.valueOf(500));

        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(33L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(orderRepository.findByIdForMutation(33L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(33L)).thenReturn(null);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ManagerPaymentLinkResponse response = service.createForOrder(33L);

        assertTrue(response.instructionText().contains("Оплата по номеру карты: 2202201901120051"));
        assertTrue(response.instructionText().contains("Получатель: Мария Олеговна Р."));
    }

    @Test
    void createForOrderRoutesToExternalManualPaymentWhenProfileUsesPaymentLink() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        PaymentProfile profile = profile(4L, "manual-link", "Ручной профиль", "manual-terminal");
        profile.setPaymentPolicy(PaymentPolicy.MANUAL_UNTIL_LIMIT_THEN_TBANK);
        profile.setManualPaymentType(ManualPaymentType.EXTERNAL_LINK);
        profile.setManualPaymentUrl("https://pay.alfabank.ru/sc/EWwpfrArNZotkqOR");
        profile.setManualPaymentButtonLabel("Оплатить через Альфа-Банк");
        profile.setManualMonthlyHardLimitKopecks(100000L);
        when(paymentProfileService.selectForManager(any())).thenReturn(profile);
        when(paymentLinkRepository.sumManualReservedAndConfirmedForPeriod(
                eq(4L),
                anyCollection(),
                anyCollection(),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(PaymentLinkStatus.class),
                any()
        )).thenReturn(0L);
        Order order = order(17L, "ООО Альфа", BigDecimal.valueOf(500));

        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(17L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(orderRepository.findByIdForMutation(17L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(17L)).thenReturn(null);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ManagerPaymentLinkResponse response = service.createForOrder(17L);

        ArgumentCaptor<PaymentLink> captor = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository).save(captor.capture());
        PaymentLink link = captor.getValue();
        assertEquals(PaymentMethod.MANUAL_EXTERNAL_LINK, link.getPaymentMethod());
        assertEquals(ManualPaymentType.EXTERNAL_LINK, link.getManualPaymentType());
        assertEquals("https://pay.alfabank.ru/sc/EWwpfrArNZotkqOR", link.getManualPaymentUrl());
        assertEquals("Оплатить через Альфа-Банк", link.getManualPaymentButtonLabel());
        assertEquals("Сивохин И.И.", link.getManualRecipientName());
        assertEquals("MANUAL_EXTERNAL_LINK", response.paymentMethod());
        assertTrue(response.instructionText().contains("Ссылка на оплату: https://pay.alfabank.ru/sc/EWwpfrArNZotkqOR"));
        assertTrue(response.instructionText().contains("Получатель: Сивохин И.И."));
        assertFalse(response.instructionText().contains("Получатель: Оплатить через Альфа-Банк"));
        assertNull(link.getManualComment());
        assertFalse(response.instructionText().contains("Комментарий:"));
    }

    @Test
    void createForOrderUsesEditablePaymentLinkCopyTextTemplate() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        PaymentProfile profile = profile(5L, "manual-template", "Ручной профиль", "manual-terminal");
        profile.setPaymentPolicy(PaymentPolicy.MANUAL_UNTIL_LIMIT_THEN_TBANK);
        profile.setManualPaymentType(ManualPaymentType.EXTERNAL_LINK);
        profile.setManualPaymentUrl("https://pay.example/link");
        profile.setManualRecipientName("Получатель П.");
        profile.setManualMonthlyHardLimitKopecks(100000L);
        when(paymentProfileService.selectForManager(any())).thenReturn(profile);
        when(paymentLinkRepository.sumManualReservedAndConfirmedForPeriod(
                eq(5L),
                anyCollection(),
                anyCollection(),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(PaymentLinkStatus.class),
                any()
        )).thenReturn(0L);
        when(appSettingService.getString(
                eq(AppSettingService.CLIENT_MESSAGES_PAYMENT_LINK_COPY_TEXT),
                anyString()
        )).thenReturn("{companyAndFilial}\n\nИтого {sum}\n\n{paymentInstruction}\n\nФинал: {paymentAfterword}");
        Order order = order(24L, "ООО Шаблон", BigDecimal.valueOf(500));

        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(24L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(orderRepository.findByIdForMutation(24L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(24L)).thenReturn(null);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ManagerPaymentLinkResponse response = service.createForOrder(24L);

        assertEquals(
                "ООО Шаблон\n\nИтого 500\n\nСсылка на оплату: https://pay.example/link\nПолучатель: Получатель П.\n\nФинал: После оплаты отправьте чек в этот чат.",
                response.copyText()
        );
    }

    @Test
    void createForOrderFallsBackToTbankWhenManualMonthlyLimitIsExceeded() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        PaymentProfile profile = profile(3L, "manual", "Ручной профиль", "manual-terminal");
        profile.setPaymentPolicy(PaymentPolicy.MANUAL_UNTIL_LIMIT_THEN_TBANK);
        profile.setManualPhone("+79990000000");
        profile.setManualRecipientName("Иван И.");
        profile.setManualMonthlyHardLimitKopecks(100000L);
        when(paymentProfileService.selectForManager(any())).thenReturn(profile);
        when(paymentLinkRepository.sumManualReservedAndConfirmedForPeriod(
                eq(3L),
                anyCollection(),
                anyCollection(),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(PaymentLinkStatus.class),
                any()
        )).thenReturn(80000L);
        Order order = order(13L, "ООО Лимит", BigDecimal.valueOf(500));

        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(13L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(orderRepository.findByIdForMutation(13L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(13L)).thenReturn(null);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ManagerPaymentLinkResponse response = service.createForOrder(13L);

        ArgumentCaptor<PaymentLink> captor = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository).save(captor.capture());
        PaymentLink link = captor.getValue();
        assertEquals(PaymentMethod.BANK_FORM, link.getPaymentMethod());
        assertEquals(PaymentLinkStatus.CREATED, link.getStatus());
        assertEquals("BANK_FORM", response.paymentMethod());
    }

    @Test
    void createForOrderRoutesToManualTaskBeforeProfileMonthlyLimit() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        PaymentProfile profile = profile(3L, "manual", "Ручной профиль", "manual-terminal");
        profile.setPaymentPolicy(PaymentPolicy.T_BANK_ONLY);
        when(paymentProfileService.selectForManager(any())).thenReturn(profile);

        ManualPaymentTask task = new ManualPaymentTask();
        task.setId(77L);
        task.setPaymentProfile(profile);
        task.setStatus(ManualPaymentTaskStatus.ACTIVE);
        task.setManualPhone("+79001234567");
        task.setManualRecipientName("Петр П.");
        task.setComment("Задание на оплату №{orderId}");
        task.setTargetAmountKopecks(100000L);
        when(manualPaymentTaskService.findRoutableTask(any(), eq(profile), eq(50000L), any()))
                .thenReturn(Optional.of(task));

        Order order = order(16L, "ООО Задание", BigDecimal.valueOf(500));
        order.setManager(manager("manager-task"));
        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(16L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(orderRepository.findByIdForMutation(16L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(16L)).thenReturn(null);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ManagerPaymentLinkResponse response = service.createForOrder(16L);

        ArgumentCaptor<PaymentLink> captor = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository).save(captor.capture());
        PaymentLink link = captor.getValue();
        assertEquals(PaymentMethod.MANUAL_MOBILE_BANK, link.getPaymentMethod());
        assertEquals(ManualPaymentSource.MANUAL_TASK, link.getManualSource());
        assertSame(task, link.getManualPaymentTask());
        assertEquals("+79001234567", link.getManualPhone());
        assertEquals("Петр П.", link.getManualRecipientName());
        assertEquals("Задание на оплату №16", link.getManualComment());
        assertEquals("MANUAL_MOBILE_BANK", response.paymentMethod());
        verify(paymentLinkRepository, never()).sumManualReservedAndConfirmedForPeriod(
                any(),
                any(),
                anyCollection(),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(PaymentLinkStatus.class),
                any()
        );
    }

    @Test
    void createForOrderExpiresInitiatedLinkWhenPayableAmountChanged() {
        PaymentLinkService service = service(properties());
        Order order = order(18L, "ООО Доплата", BigDecimal.valueOf(1000));
        PaymentLink existing = new PaymentLink();
        existing.setId(18L);
        existing.setOrder(order);
        existing.setToken("old-token");
        existing.setAmountKopecks(100000L);
        existing.setReservedAmountKopecks(100000L);
        existing.setDescription("Оплата услуг");
        existing.setStatus(PaymentLinkStatus.INITIATED);
        existing.setPaymentMethod(PaymentMethod.BANK_FORM);
        existing.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(18L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.of(existing));
        when(orderRepository.findByIdForMutation(18L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(18L))
                .thenReturn(new BadReviewTaskSummary(1, 0, 1, 0, BigDecimal.valueOf(250), BigDecimal.ZERO));
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ManagerPaymentLinkResponse response = service.createForOrder(18L);

        assertEquals(PaymentLinkStatus.EXPIRED, existing.getStatus());
        assertEquals(125000L, response.amountKopecks());
        ArgumentCaptor<PaymentLink> captor = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository, times(2)).save(captor.capture());
        assertSame(existing, captor.getAllValues().get(0));
        assertEquals(125000L, captor.getAllValues().get(1).getAmountKopecks());
    }

    @Test
    void createForOrderBlocksInitiatedBankLinkWithPaymentIdWhenPayableAmountChanged() {
        PaymentLinkService service = service(properties());
        Order order = order(35L, "ООО Банк в процессе", BigDecimal.valueOf(1000));
        PaymentLink existing = new PaymentLink();
        existing.setId(35L);
        existing.setOrder(order);
        existing.setToken("old-bank-token");
        existing.setAmountKopecks(100000L);
        existing.setReservedAmountKopecks(100000L);
        existing.setDescription("Оплата услуг");
        existing.setStatus(PaymentLinkStatus.INITIATED);
        existing.setPaymentMethod(PaymentMethod.BANK_FORM);
        existing.setTbankPaymentId("8634010699");
        existing.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(35L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.of(existing));
        when(orderRepository.findByIdForMutation(35L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(35L))
                .thenReturn(new BadReviewTaskSummary(1, 0, 1, 0, BigDecimal.valueOf(250), BigDecimal.ZERO));

        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.createForOrder(35L));

        assertEquals(PaymentLinkStatus.INITIATED, existing.getStatus());
        verify(paymentLinkRepository, never()).save(any(PaymentLink.class));
    }

    @Test
    void createForOrderBlocksAuthorizedLinkWhenPayableAmountChanged() {
        PaymentLinkService service = service(properties());
        Order order = order(19L, "ООО Авторизация", BigDecimal.valueOf(1000));
        PaymentLink existing = new PaymentLink();
        existing.setId(19L);
        existing.setOrder(order);
        existing.setToken("authorized-token");
        existing.setAmountKopecks(100000L);
        existing.setReservedAmountKopecks(100000L);
        existing.setDescription("Оплата услуг");
        existing.setStatus(PaymentLinkStatus.AUTHORIZED);
        existing.setPaymentMethod(PaymentMethod.BANK_FORM);
        existing.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(19L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.of(existing));
        when(orderRepository.findByIdForMutation(19L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(19L))
                .thenReturn(new BadReviewTaskSummary(1, 0, 1, 0, BigDecimal.valueOf(250), BigDecimal.ZERO));

        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.createForOrder(19L));

        assertEquals(PaymentLinkStatus.AUTHORIZED, existing.getStatus());
        verify(paymentLinkRepository, never()).save(any(PaymentLink.class));
    }

    @Test
    void reportManualPaymentMarksLinkAsReportedWithoutConfirmingOrder() throws Exception {
        PaymentLinkService service = service(properties());
        Order order = order(14L, "ООО Оплатил", BigDecimal.valueOf(500));
        PaymentLink link = new PaymentLink();
        link.setOrder(order);
        link.setToken("manual-token");
        link.setAmountKopecks(50000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findByTokenWithOrder("manual-token")).thenReturn(Optional.of(link));

        service.reportManualPayment("manual-token");

        assertEquals(PaymentLinkStatus.MANUAL_REPORTED, link.getStatus());
        assertNotNull(link.getManualReportedAt());
        verify(orderTransactionService, never()).handlePaymentStatus(order);
        verify(paymentLinkRepository).save(link);
    }

    @Test
    void confirmManualPaymentAppliesOrderTransitionAndMarksReceiptPending() throws Exception {
        PaymentLinkService service = service(properties());
        Order order = order(15L, "ООО Сверка", BigDecimal.valueOf(500));
        PaymentLink link = new PaymentLink();
        link.setId(15L);
        link.setOrder(order);
        link.setToken("manual-token");
        link.setAmountKopecks(50000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.MANUAL_REPORTED);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findByIdWithOrder(15L)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderTransactionService.handlePaymentStatus(order)).thenReturn(true);

        service.confirmManual(15L, "admin@example.ru");

        assertEquals(PaymentLinkStatus.CONFIRMED, link.getStatus());
        assertEquals(50000L, link.getConfirmedAmountKopecks());
        assertEquals(PaymentReceiptStatus.PENDING, link.getReceiptStatus());
        assertEquals("admin@example.ru", link.getManualConfirmedBy());
        assertNotNull(link.getManualConfirmedAt());
        assertNotNull(link.getPaidAt());
        verify(orderTransactionService).handlePaymentStatus(order);
        verify(paymentInvoiceRetryScheduler).cancelBadReviewAutoBan(order, "Ручная оплата подтверждена");
        verify(paymentLinkRepository).save(link);
    }

    @Test
    void confirmedWebhookInTestModeDoesNotTouchOrderPaymentTransition() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setTerminalKey("terminal");
        properties.setPassword("password");
        TbankTokenSigner signer = new TbankTokenSigner();
        PaymentLinkService service = service(properties, signer);
        Order order = order(20L, "ООО Тест", BigDecimal.valueOf(11.11));
        PaymentLink link = new PaymentLink();
        link.setOrder(order);
        link.setToken("token");
        link.setTbankOrderId("o20-test");
        link.setAmountKopecks(1111L);
        link.setPayerEmail("PAYER@EXAMPLE.RU");
        link.setStatus(PaymentLinkStatus.INITIATED);
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("TerminalKey", "terminal");
        payload.put("OrderId", "o20-test");
        payload.put("Success", "true");
        payload.put("Status", "CONFIRMED");
        payload.put("PaymentId", "12345");
        payload.put("ErrorCode", "0");
        payload.put("Amount", "1111");
        payload.put("Token", signer.sign(payload, "password"));

        when(paymentLinkRepository.findByTbankOrderIdWithOrder("o20-test")).thenReturn(Optional.of(link));

        service.handleTbankWebhook(payload);

        assertEquals(PaymentLinkStatus.TEST_CONFIRMED, link.getStatus());
        assertEquals("12345", link.getTbankPaymentId());
        assertNotNull(link.getPaidAt());
        assertEquals("payer@example.ru", order.getCompany().getLastPayerEmail());
        assertNotNull(order.getCompany().getLastPayerEmailAt());
        verify(orderTransactionService, never()).handlePaymentStatus(order);
        verify(paymentLinkRepository).save(link);
    }

    @Test
    void confirmedWebhookAppliesOrderPaymentTransitionOnlyWhenEnabled() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setTerminalKey("terminal");
        properties.setPassword("password");
        properties.setApplyConfirmedPayments(true);
        TbankTokenSigner signer = new TbankTokenSigner();
        PaymentLinkService service = service(properties, signer);
        when(paymentProfileService.toRuntimeForTerminal(any(PaymentProfile.class), eq("terminal"))).thenReturn(new TbankPaymentProfile(
                1L,
                TbankPaymentProfile.PRIMARY_CODE,
                "Основной магазин",
                true,
                "terminal",
                "password",
                false
        ));
        when(paymentProfileService.isTestTerminal("terminal")).thenReturn(false);
        Order order = order(21L, "ООО Боевой тест", BigDecimal.valueOf(11.11));
        PaymentLink link = new PaymentLink();
        link.setOrder(order);
        link.setToken("token");
        link.setTbankOrderId("o21-test");
        link.setAmountKopecks(1111L);
        link.setPayerEmail("BOY@EXAMPLE.RU");
        link.setStatus(PaymentLinkStatus.INITIATED);
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("TerminalKey", "terminal");
        payload.put("OrderId", "o21-test");
        payload.put("Success", "true");
        payload.put("Status", "CONFIRMED");
        payload.put("PaymentId", "12346");
        payload.put("ErrorCode", "0");
        payload.put("Amount", "1111");
        payload.put("Token", signer.sign(payload, "password"));

        when(paymentLinkRepository.findByTbankOrderIdWithOrder("o21-test")).thenReturn(Optional.of(link));
        when(orderTransactionService.handlePaymentStatus(order)).thenReturn(true);

        service.handleTbankWebhook(payload);

        assertEquals(PaymentLinkStatus.CONFIRMED, link.getStatus());
        assertEquals("12346", link.getTbankPaymentId());
        assertNotNull(link.getPaidAt());
        assertEquals("boy@example.ru", order.getCompany().getLastPayerEmail());
        assertNotNull(order.getCompany().getLastPayerEmailAt());
        assertNotNull(link.getPaymentSuccessNotifiedAt());
        assertNull(link.getPaymentSuccessNotificationError());
        verify(orderTransactionService).handlePaymentStatus(order);
        verify(paymentSuccessClientNotifier).notifySuccess(link);
        verify(paymentInvoiceRetryScheduler).cancelBadReviewAutoBan(order, "T-Bank/SBP оплата подтверждена");
        verify(paymentLinkRepository).save(link);
    }

    @Test
    void publicLinkUsesCompanyLastPayerEmailWhenLinkEmailIsEmpty() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        Order order = order(22L, "ООО Автозаполнение", BigDecimal.valueOf(200));
        order.getCompany().setLastPayerEmail("Client@Example.Ru");
        PaymentLink link = new PaymentLink();
        link.setOrder(order);
        link.setToken("token");
        link.setAmountKopecks(20000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.CREATED);
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findByTokenWithOrder("token")).thenReturn(Optional.of(link));

        assertEquals("client@example.ru", service.publicLink("token").payerEmail());
    }

    @Test
    void createForOrderRemovesReceiptRequestFromTbankCopyText() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        PaymentProfile profile = profile(6L, TbankPaymentProfile.PRIMARY_CODE, "Основной магазин", "primary-terminal");
        when(paymentProfileService.selectForManager(any())).thenReturn(profile);
        when(appSettingService.getString(
                eq(AppSettingService.CLIENT_MESSAGES_PAYMENT_LINK_COPY_TEXT),
                anyString()
        )).thenReturn("{companyAndFilial}\n\n{paymentInstruction}\n\nПришлите чек, пожалуйста, как оплатите.");
        Order order = order(34L, "ООО T-Bank", BigDecimal.valueOf(500));

        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(34L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(orderRepository.findByIdForMutation(34L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(34L)).thenReturn(null);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ManagerPaymentLinkResponse response = service.createForOrder(34L);

        assertTrue(response.copyText().contains("Ссылка на оплату: https://example.ru/pay/"));
        assertFalse(response.copyText().toLowerCase(Locale.ROOT).contains("пришлите чек"));
    }

    @Test
    void publicLinkReturnsLatestActiveLinkWhenOldTokenWasRetired() {
        PaymentLinkService service = service(properties());
        Order order = order(22115L, "ООО Старая ссылка", BigDecimal.valueOf(3000));
        PaymentLink oldLink = new PaymentLink();
        oldLink.setId(1L);
        oldLink.setOrder(order);
        oldLink.setToken("old-token");
        oldLink.setAmountKopecks(300000L);
        oldLink.setDescription("Оплата услуг");
        oldLink.setStatus(PaymentLinkStatus.EXPIRED);
        oldLink.setExpiresAt(LocalDateTime.now().plusDays(80));

        PaymentLink newLink = new PaymentLink();
        newLink.setId(2L);
        newLink.setOrder(order);
        newLink.setToken("new-token");
        newLink.setAmountKopecks(300000L);
        newLink.setDescription("Оплата услуг");
        newLink.setStatus(PaymentLinkStatus.CREATED);
        newLink.setExpiresAt(LocalDateTime.now().plusDays(90));

        when(paymentLinkRepository.findByTokenWithOrder("old-token")).thenReturn(Optional.of(oldLink));
        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(22115L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.of(newLink));

        var response = service.publicLink("old-token");

        assertEquals("new-token", response.token());
        assertEquals("CREATED", response.status());
        assertTrue(response.payable());
    }

    @Test
    void publicLinkCreatesReplacementWhenOldTokenHasNoActiveSuccessor() {
        PaymentLinkService service = service(properties());
        Order order = order(22116L, "ООО Автозамена", BigDecimal.valueOf(3000));
        PaymentLink oldLink = new PaymentLink();
        oldLink.setId(1L);
        oldLink.setOrder(order);
        oldLink.setToken("old-token");
        oldLink.setAmountKopecks(300000L);
        oldLink.setDescription("Оплата услуг");
        oldLink.setStatus(PaymentLinkStatus.EXPIRED);
        oldLink.setExpiresAt(LocalDateTime.now().plusDays(80));

        PaymentLink newLink = new PaymentLink();
        newLink.setId(2L);
        newLink.setOrder(order);
        newLink.setToken("created-token");
        newLink.setAmountKopecks(300000L);
        newLink.setDescription("Оплата услуг");
        newLink.setStatus(PaymentLinkStatus.CREATED);
        newLink.setExpiresAt(LocalDateTime.now().plusDays(90));

        when(paymentLinkRepository.findByTokenWithOrder("old-token")).thenReturn(Optional.of(oldLink));
        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(22116L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty(), Optional.empty(), Optional.of(newLink));
        when(orderRepository.findByIdForMutation(22116L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(22116L)).thenReturn(null);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.publicLink("old-token");

        assertEquals("created-token", response.token());
        assertEquals("CREATED", response.status());
        assertTrue(response.payable());
        verify(paymentLinkRepository).save(any(PaymentLink.class));
    }

    @Test
    void publicLinkCreatesReplacementForManualPaidRetiredLinkWhenOrderReturnedToReminder() {
        PaymentLinkService service = service(properties());
        Order order = order(22671L, "КЛИМАТпроф", BigDecimal.valueOf(2500));
        order.setStatus(OrderStatus.builder().title("Напоминание").build());

        PaymentLink oldLink = new PaymentLink();
        oldLink.setId(800L);
        oldLink.setOrder(order);
        oldLink.setToken("old-token");
        oldLink.setAmountKopecks(250000L);
        oldLink.setDescription("Оплата услуг");
        oldLink.setStatus(PaymentLinkStatus.CANCELED);
        oldLink.setLastError("Заказ отмечен оплаченным вручную; старая ссылка закрыта");
        oldLink.setExpiresAt(LocalDateTime.now().plusDays(80));

        PaymentLink newLink = new PaymentLink();
        newLink.setId(801L);
        newLink.setOrder(order);
        newLink.setToken("new-token");
        newLink.setAmountKopecks(250000L);
        newLink.setDescription("Оплата услуг");
        newLink.setStatus(PaymentLinkStatus.CREATED);
        newLink.setExpiresAt(LocalDateTime.now().plusDays(90));

        when(paymentLinkRepository.findByTokenWithOrder("old-token")).thenReturn(Optional.of(oldLink));
        when(paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(22671L),
                anyCollection(),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty(), Optional.empty(), Optional.of(newLink));
        when(orderRepository.findByIdForMutation(22671L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(22671L)).thenReturn(null);
        when(paymentLinkRepository.save(any(PaymentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.publicLink("old-token");

        assertEquals("new-token", response.token());
        assertEquals("CREATED", response.status());
        assertTrue(response.payable());
        verify(paymentLinkRepository).save(any(PaymentLink.class));
    }

    @Test
    void publicLinkSynchronizesInitiatedPaymentFromTbankGetState() {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(23L, "ООО Возврат", BigDecimal.valueOf(250));
        PaymentLink link = new PaymentLink();
        link.setId(23L);
        link.setOrder(order);
        link.setToken("token");
        link.setAmountKopecks(25000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.INITIATED);
        link.setTbankPaymentId("payment-23");
        link.setTbankTerminalKey("terminal");
        link.setPayerEmail("RETURN@EXAMPLE.RU");
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findByTokenWithOrder("token")).thenReturn(Optional.of(link));
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-23"))).thenReturn(new TbankGetStateResponse(
                true,
                "0",
                null,
                null,
                "terminal",
                "CONFIRMED",
                "payment-23",
                "order-23",
                25000L
        ));

        var response = service.publicLink("token");

        assertEquals(PaymentLinkStatus.TEST_CONFIRMED, link.getStatus());
        assertEquals("TEST_CONFIRMED", response.status());
        assertFalse(response.payable());
        assertNotNull(link.getPaidAt());
        assertEquals("return@example.ru", order.getCompany().getLastPayerEmail());
        verify(tbankClient).getState(any(TbankPaymentProfile.class), eq("payment-23"));
        verify(paymentLinkRepository).save(link);
    }

    @Test
    void initUsesShortBankRedirectDueInsteadOfPublicLinkTtl() {
        TbankPaymentProperties properties = properties();
        properties.setLinkTtl(Duration.ofDays(90));
        properties.setRedirectDue(Duration.ofDays(7));
        PaymentLinkService service = service(properties);
        Order order = order(30L, "ООО Платеж", BigDecimal.valueOf(123.45));
        PaymentLink link = new PaymentLink();
        link.setOrder(order);
        link.setToken("token");
        link.setAmountKopecks(12345L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.CREATED);
        link.setExpiresAt(LocalDateTime.now().plusDays(90));

        when(paymentLinkRepository.findByTokenWithOrder("token")).thenReturn(Optional.of(link));
        when(tbankClient.init(any(TbankPaymentProfile.class), any(TbankInitCommand.class))).thenReturn(new TbankInitResponse(
                true,
                "0",
                null,
                null,
                "terminal",
                "NEW",
                "payment-1",
                "order-1",
                12345L,
                "https://securepay.tinkoff.ru/pay"
        ));

        OffsetDateTime minExpected = OffsetDateTime.now(ZoneId.of("Europe/Moscow")).plusDays(7).minusSeconds(2);
        service.init("token", "PAYER@EXAMPLE.RU", true, true, true, "203.0.113.7", "JUnit UA");

        ArgumentCaptor<TbankInitCommand> captor = ArgumentCaptor.forClass(TbankInitCommand.class);
        ArgumentCaptor<TbankPaymentProfile> profileCaptor = ArgumentCaptor.forClass(TbankPaymentProfile.class);
        verify(tbankClient).init(profileCaptor.capture(), captor.capture());
        TbankInitCommand command = captor.getValue();
        OffsetDateTime maxExpected = OffsetDateTime.now(ZoneId.of("Europe/Moscow")).plusDays(7).plusSeconds(2);

        assertEquals(TbankPaymentProfile.PRIMARY_CODE, profileCaptor.getValue().code());
        assertEquals("payer@example.ru", command.email());
        assertTrue(command.redirectDueDate().isAfter(minExpected));
        assertTrue(command.redirectDueDate().isBefore(maxExpected));
        assertEquals("203.0.113.7", link.getConsentIp());
        assertEquals("JUnit UA", link.getConsentUserAgent());
        assertEquals("https://example.ru/offer", link.getOfferDocumentUrl());
        assertEquals("https://example.ru/privacy", link.getPrivacyDocumentUrl());
        assertEquals("https://example.ru/receipt-consent", link.getReceiptConsentDocumentUrl());
        assertNotNull(link.getOfferConsentAt());
        assertNotNull(link.getPrivacyConsentAt());
        assertNotNull(link.getReceiptConsentAt());
        assertEquals(PaymentLinkStatus.INITIATED, link.getStatus());
        assertEquals("https://securepay.tinkoff.ru/pay", link.getPaymentUrl());
    }

    @Test
    void initRejectsPaymentWithoutRequiredConsents() {
        PaymentLinkService service = service(properties());
        Order order = order(31L, "ООО Без согласий", BigDecimal.valueOf(123.45));
        PaymentLink link = new PaymentLink();
        link.setOrder(order);
        link.setToken("token");
        link.setAmountKopecks(12345L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.CREATED);
        link.setExpiresAt(LocalDateTime.now().plusDays(90));

        when(paymentLinkRepository.findByTokenWithOrder("token")).thenReturn(Optional.of(link));

        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service.init("token", "PAYER@EXAMPLE.RU", true, false, true, "203.0.113.7", "JUnit UA")
        );
        verify(tbankClient, never()).init(any(TbankPaymentProfile.class), any(TbankInitCommand.class));
    }

    @Test
    void initSbpCreatesPaymentPayloadAfterBankInitAndStoresPaymentMethod() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        Order order = order(32L, "ООО СБП", BigDecimal.valueOf(321));
        PaymentLink link = new PaymentLink();
        link.setOrder(order);
        link.setToken("token");
        link.setAmountKopecks(32100L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.CREATED);
        link.setExpiresAt(LocalDateTime.now().plusDays(90));

        when(paymentLinkRepository.findByTokenWithOrder("token")).thenReturn(Optional.of(link));
        when(tbankClient.init(any(TbankPaymentProfile.class), any(TbankInitCommand.class))).thenReturn(new TbankInitResponse(
                true,
                "0",
                null,
                null,
                "terminal",
                "NEW",
                "payment-sbp",
                "order-sbp",
                32100L,
                "https://securepay.tinkoff.ru/pay"
        ));
        when(tbankClient.getQr(any(TbankPaymentProfile.class), any(TbankGetQrCommand.class))).thenReturn(new TbankGetQrResponse(
                true,
                "0",
                null,
                null,
                "terminal",
                "payment-sbp",
                "https://qr.nspk.ru/AS100000000111"
        ));

        PublicPaymentInitResponse response = service.initSbp(
                "token",
                "SBP@EXAMPLE.RU",
                true,
                true,
                true,
                null,
                "203.0.113.8",
                "JUnit UA"
        );

        ArgumentCaptor<TbankGetQrCommand> qrCaptor = ArgumentCaptor.forClass(TbankGetQrCommand.class);
        verify(tbankClient).getQr(any(TbankPaymentProfile.class), qrCaptor.capture());
        assertEquals("payment-sbp", qrCaptor.getValue().paymentId());
        assertEquals("PAYLOAD", qrCaptor.getValue().dataType());
        assertEquals(null, qrCaptor.getValue().bankId());
        assertEquals(PaymentMethod.SBP_QR, link.getPaymentMethod());
        assertEquals("https://qr.nspk.ru/AS100000000111", link.getSbpQrPayload());
        assertEquals("PAYLOAD", link.getSbpQrDataType());
        assertNotNull(link.getSbpQrCreatedAt());
        assertEquals("SBP_QR", response.method());
        assertEquals("https://qr.nspk.ru/AS100000000111", response.qrPayload());
        assertEquals("sbp@example.ru", link.getPayerEmail());
        verify(paymentLinkRepository).save(link);
    }

    @Test
    void initSbpWithBankIdRequestsBankDeeplink() {
        PaymentLinkService service = service(properties());
        Order order = order(33L, "ООО СБП Банк", BigDecimal.valueOf(321));
        PaymentLink link = new PaymentLink();
        link.setOrder(order);
        link.setToken("token-bank");
        link.setAmountKopecks(32100L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.INITIATED);
        link.setTbankPaymentId("payment-sbp-bank");
        link.setPaymentUrl("https://securepay.tinkoff.ru/pay");
        link.setExpiresAt(LocalDateTime.now().plusDays(90));

        when(paymentLinkRepository.findByTokenWithOrder("token-bank")).thenReturn(Optional.of(link));
        when(tbankClient.getQr(any(TbankPaymentProfile.class), any(TbankGetQrCommand.class))).thenReturn(new TbankGetQrResponse(
                true,
                "0",
                null,
                null,
                "terminal",
                "payment-sbp-bank",
                "bankapp://pay/payment-sbp-bank"
        ));

        PublicPaymentInitResponse response = service.initSbp(
                "token-bank",
                "SBP@EXAMPLE.RU",
                true,
                true,
                true,
                "bank-1",
                "203.0.113.8",
                "JUnit UA"
        );

        ArgumentCaptor<TbankGetQrCommand> qrCaptor = ArgumentCaptor.forClass(TbankGetQrCommand.class);
        verify(tbankClient).getQr(any(TbankPaymentProfile.class), qrCaptor.capture());
        assertEquals("payment-sbp-bank", qrCaptor.getValue().paymentId());
        assertEquals("PAYLOAD", qrCaptor.getValue().dataType());
        assertEquals("bank-1", qrCaptor.getValue().bankId());
        assertEquals("bankapp://pay/payment-sbp-bank", response.qrPayload());
        assertEquals("bankapp://pay/payment-sbp-bank", link.getSbpQrPayload());
    }

    @Test
    void publicSbpBanksLoadsBankListFromTbankAndMarksFeatured() {
        PaymentLinkService service = service(properties());
        Order order = order(34L, "ООО Банки СБП", BigDecimal.valueOf(321));
        PaymentLink link = new PaymentLink();
        link.setOrder(order);
        link.setToken("token-banks");
        link.setAmountKopecks(32100L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.CREATED);
        link.setExpiresAt(LocalDateTime.now().plusDays(90));

        when(paymentLinkRepository.findByTokenWithOrder("token-banks")).thenReturn(Optional.of(link));
        when(tbankClient.getQrBankList(any(TbankPaymentProfile.class), any(TbankGetQrBankListCommand.class)))
                .thenReturn(new TbankGetQrBankListResponse(
                        true,
                        "0",
                        null,
                        null,
                        "terminal",
                        List.of(
                                new TbankGetQrBankListResponse.TbankSbpBank(
                                        "bank-other",
                                        "100000000999",
                                        "Банк Зета",
                                        null,
                                        1
                                ),
                                new TbankGetQrBankListResponse.TbankSbpBank(
                                        "bank-sber",
                                        "100000000111",
                                        "СберБанк",
                                        "https://example.ru/sber.svg",
                                        2
                                )
                        )
                ));

        List<PublicSbpBankResponse> response = service.publicSbpBanks("token-banks", "desktop", "Windows");

        ArgumentCaptor<TbankGetQrBankListCommand> commandCaptor = ArgumentCaptor.forClass(TbankGetQrBankListCommand.class);
        verify(tbankClient).getQrBankList(any(TbankPaymentProfile.class), commandCaptor.capture());
        assertEquals("desktop", commandCaptor.getValue().deviceType());
        assertEquals("Windows", commandCaptor.getValue().os());
        assertEquals("bank-sber", response.get(0).bankId());
        assertTrue(response.get(0).featured());
        assertEquals("bank-other", response.get(1).bankId());
        assertFalse(response.get(1).featured());
    }

    @Test
    void cancelRefundablePaymentCallsTbankCancelAndStoresRefundedStatus() {
        TbankPaymentProperties properties = properties();
        PaymentLinkService service = service(properties);
        Order order = order(40L, "ООО Возврат", BigDecimal.valueOf(10));
        PaymentLink link = new PaymentLink();
        link.setId(1L);
        link.setOrder(order);
        link.setToken("token");
        link.setAmountKopecks(1000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.TEST_CONFIRMED);
        link.setTbankPaymentId("payment-1");
        link.setExpiresAt(LocalDateTime.now().plusDays(1));
        link.setCreatedAt(LocalDateTime.now());
        link.setUpdatedAt(LocalDateTime.now());

        when(paymentLinkRepository.findByIdWithOrder(1L)).thenReturn(Optional.of(link));
        when(tbankClient.cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class))).thenReturn(new TbankCancelResponse(
                true,
                "0",
                null,
                null,
                "terminal",
                "REFUNDED",
                "payment-1",
                "order-1",
                1000L,
                1000L,
                0L
        ));

        AdminPaymentLinkResponse response = service.cancel(1L);

        ArgumentCaptor<TbankCancelCommand> captor = ArgumentCaptor.forClass(TbankCancelCommand.class);
        ArgumentCaptor<TbankPaymentProfile> profileCaptor = ArgumentCaptor.forClass(TbankPaymentProfile.class);
        verify(tbankClient).cancel(profileCaptor.capture(), captor.capture());
        assertEquals(TbankPaymentProfile.PRIMARY_CODE, profileCaptor.getValue().code());
        assertEquals("payment-1", captor.getValue().paymentId());
        assertEquals(1000L, captor.getValue().amountKopecks());
        assertEquals(PaymentLinkStatus.REFUNDED, link.getStatus());
        assertEquals("REFUNDED", response.status());
        assertFalse(response.refundable());
        verify(paymentLinkRepository).save(link);
    }

    @Test
    void adminLinksSynchronizesAuthorizedPaymentFromTbankGetState() {
        TbankPaymentProperties properties = properties();
        properties.setEnabled(true);
        PaymentLinkService service = service(properties);
        Order order = order(50L, "ООО Синхронизация", BigDecimal.valueOf(900));
        PaymentProfile profile = profile(2L, TbankPaymentProfile.SECONDARY_CODE, "Второй магазин", "secondary-terminal");
        PaymentLink link = new PaymentLink();
        link.setId(5L);
        link.setOrder(order);
        link.setPaymentProfile(profile);
        link.setPaymentProfileCode(profile.getCode());
        link.setPaymentProfileName(profile.getName());
        link.setToken("token");
        link.setAmountKopecks(90000L);
        link.setDescription("Оплата услуг");
        link.setStatus(PaymentLinkStatus.AUTHORIZED);
        link.setTbankPaymentId("payment-50");
        link.setTbankTerminalKey("secondary-terminal");
        link.setPayerEmail("CLIENT@EXAMPLE.RU");
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(paymentLinkRepository.findAdminPage(
                anyString(),
                any(),
                any(),
                any(),
                any(),
                anyCollection(),
                anyCollection(),
                anyCollection(),
                anyCollection(),
                anyCollection(),
                any()
        )).thenReturn(new PageImpl<>(List.of(link)));
        when(paymentLinkRepository.summarizeAdminPage(
                anyString(),
                any(),
                any(),
                any(),
                any(),
                anyCollection(),
                anyCollection(),
                anyCollection(),
                anyCollection(),
                anyCollection(),
                anyCollection(),
                anyCollection(),
                anyCollection()
        )).thenReturn(new PaymentLinkAdminSummary(1L, 90000L, 1L, 0L, 1L, 0L, 0L, 1L, 0L, 0L));
        when(paymentProfileService.toRuntimeForTerminal(profile, "secondary-terminal")).thenReturn(new TbankPaymentProfile(
                2L,
                TbankPaymentProfile.SECONDARY_CODE,
                "Второй магазин",
                true,
                "secondary-terminal",
                "secondary-password",
                true
        ));
        when(tbankClient.getState(any(TbankPaymentProfile.class), eq("payment-50"))).thenReturn(new TbankGetStateResponse(
                true,
                "0",
                null,
                null,
                "secondary-terminal",
                "CONFIRMED",
                "payment-50",
                "order-50",
                90000L
        ));

        AdminPaymentLinksPageResponse response = service.adminLinks(0, 100, "all", null, null, null, "live");

        assertEquals(PaymentLinkStatus.TEST_CONFIRMED, link.getStatus());
        assertEquals("TEST_CONFIRMED", response.items().get(0).status());
        assertEquals("UNKNOWN", response.items().get(0).clientChatPlatform());
        assertFalse(response.items().get(0).clientChatReady());
        assertEquals("ссылка на чат не указана", response.items().get(0).clientChatWarning());
        assertNotNull(link.getPaidAt());
        assertEquals("client@example.ru", order.getCompany().getLastPayerEmail());
        verify(tbankClient).getState(any(TbankPaymentProfile.class), eq("payment-50"));
        verify(paymentLinkRepository).save(link);
    }

    @Test
    void lateAuthorizedWebhookDoesNotDowngradeTestConfirmedPayment() {
        TbankPaymentProperties properties = properties();
        properties.setTerminalKey("terminal");
        properties.setPassword("password");
        TbankTokenSigner signer = new TbankTokenSigner();
        PaymentLinkService service = service(properties, signer);
        Order order = order(51L, "ООО Не откатываем", BigDecimal.valueOf(11.11));
        PaymentLink link = new PaymentLink();
        link.setOrder(order);
        link.setToken("token");
        link.setTbankOrderId("o51-test");
        link.setAmountKopecks(1111L);
        link.setStatus(PaymentLinkStatus.TEST_CONFIRMED);
        link.setPaidAt(LocalDateTime.now());
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("TerminalKey", "terminal");
        payload.put("OrderId", "o51-test");
        payload.put("Success", "true");
        payload.put("Status", "AUTHORIZED");
        payload.put("PaymentId", "12347");
        payload.put("ErrorCode", "0");
        payload.put("Amount", "1111");
        payload.put("Token", signer.sign(payload, "password"));

        when(paymentLinkRepository.findByTbankOrderIdWithOrder("o51-test")).thenReturn(Optional.of(link));

        service.handleTbankWebhook(payload);

        assertEquals(PaymentLinkStatus.TEST_CONFIRMED, link.getStatus());
        verify(paymentLinkRepository).save(link);
    }

    @Test
    void confirmedWebhookWithStaleAmountDoesNotMarkOrderPaid() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setTerminalKey("terminal");
        properties.setPassword("password");
        TbankTokenSigner signer = new TbankTokenSigner();
        PaymentLinkService service = service(properties, signer);
        Order order = order(60L, "ООО Старая сумма", BigDecimal.valueOf(10));
        PaymentLink link = new PaymentLink();
        link.setOrder(order);
        link.setToken("token");
        link.setTbankOrderId("o60-test");
        link.setAmountKopecks(1000L);
        link.setStatus(PaymentLinkStatus.INITIATED);
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("TerminalKey", "terminal");
        payload.put("OrderId", "o60-test");
        payload.put("Success", "true");
        payload.put("Status", "CONFIRMED");
        payload.put("PaymentId", "12360");
        payload.put("ErrorCode", "0");
        payload.put("Amount", "1000");
        payload.put("Token", signer.sign(payload, "password"));

        when(paymentLinkRepository.findByTbankOrderIdWithOrder("o60-test")).thenReturn(Optional.of(link));
        when(badReviewTaskService.getSummaryForOrder(60L))
                .thenReturn(new BadReviewTaskSummary(1, 0, 1, 0, BigDecimal.valueOf(5), BigDecimal.ZERO));

        service.handleTbankWebhook(payload);

        assertEquals(PaymentLinkStatus.AMOUNT_MISMATCH, link.getStatus());
        assertEquals(1000L, link.getConfirmedAmountKopecks());
        assertTrue(link.getLastError().contains("устаревшей сумме"));
        verify(orderTransactionService, never()).handlePaymentStatus(order);
        verify(paymentLinkRepository).save(link);
    }

    @Test
    void confirmedWebhookForRetiredLinkDoesNotMarkOrderPaidAgain() throws Exception {
        TbankPaymentProperties properties = properties();
        properties.setTerminalKey("terminal");
        properties.setPassword("password");
        TbankTokenSigner signer = new TbankTokenSigner();
        PaymentLinkService service = service(properties, signer);
        Order order = order(61L, "ООО Закрытая ссылка", BigDecimal.valueOf(10));
        PaymentLink link = new PaymentLink();
        link.setOrder(order);
        link.setToken("token");
        link.setTbankOrderId("o61-test");
        link.setAmountKopecks(1000L);
        link.setStatus(PaymentLinkStatus.CANCELED);
        link.setExpiresAt(LocalDateTime.now().plusDays(1));

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("TerminalKey", "terminal");
        payload.put("OrderId", "o61-test");
        payload.put("Success", "true");
        payload.put("Status", "CONFIRMED");
        payload.put("PaymentId", "12361");
        payload.put("ErrorCode", "0");
        payload.put("Amount", "1000");
        payload.put("Token", signer.sign(payload, "password"));

        when(paymentLinkRepository.findByTbankOrderIdWithOrder("o61-test")).thenReturn(Optional.of(link));

        service.handleTbankWebhook(payload);

        assertEquals(PaymentLinkStatus.AMOUNT_MISMATCH, link.getStatus());
        assertEquals(1000L, link.getConfirmedAmountKopecks());
        assertTrue(link.getLastError().contains("закрытой ссылке"));
        verify(orderTransactionService, never()).handlePaymentStatus(order);
        verify(paymentLinkRepository).save(link);
    }

    private PaymentLinkService service(TbankPaymentProperties properties) {
        return service(properties, new TbankTokenSigner());
    }

    private PaymentLinkService service(TbankPaymentProperties properties, TbankTokenSigner signer) {
        @SuppressWarnings("unchecked")
        ObjectProvider<CommonBillingService> commonBillingServiceProvider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.lenient().when(commonBillingServiceProvider.getIfAvailable()).thenReturn(null);
        return new PaymentLinkService(
                paymentLinkRepository,
                orderRepository,
                badReviewTaskService,
                orderTransactionService,
                properties,
                runtimeSettingsService,
                paymentProfileService,
                tbankClient,
                signer,
                paymentSuccessClientNotifier,
                manualPaymentTaskService,
                paymentInvoiceRetryScheduler,
                paymentLinkArchiveService,
                appSettingService,
                commonBillingServiceProvider
        );
    }

    private TbankPaymentProperties properties() {
        TbankPaymentProperties properties = new TbankPaymentProperties();
        properties.setPublicBaseUrl("https://example.ru");
        PaymentProfile defaultProfile = profile(1L, TbankPaymentProfile.PRIMARY_CODE, "Основной магазин", "terminal");
        org.mockito.Mockito.lenient().when(runtimeSettingsService.runtimeMode()).thenReturn(TbankRuntimeMode.TEST);
        org.mockito.Mockito.lenient().when(runtimeSettingsService.isTbankEnabled()).thenAnswer(invocation -> properties.isEnabled());
        org.mockito.Mockito.lenient().when(runtimeSettingsService.isPaymentLinksEnabled()).thenAnswer(invocation -> properties.isPaymentLinksEnabled());
        org.mockito.Mockito.lenient().when(runtimeSettingsService.isManagerUiEnabled()).thenAnswer(invocation -> properties.isManagerUiEnabled());
        org.mockito.Mockito.lenient().when(runtimeSettingsService.isApplyConfirmedPayments()).thenAnswer(invocation -> properties.isApplyConfirmedPayments());
        org.mockito.Mockito.lenient().when(paymentProfileService.selectForManager(any())).thenReturn(defaultProfile);
        org.mockito.Mockito.lenient().when(paymentProfileService.lockForRouting(any())).thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.lenient().when(manualPaymentTaskService.findRoutableTask(
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any()
                ))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(paymentProfileService.findByTerminalKey("terminal")).thenReturn(Optional.of(defaultProfile));
        org.mockito.Mockito.lenient().when(paymentProfileService.findByCode(TbankPaymentProfile.PRIMARY_CODE)).thenReturn(Optional.of(defaultProfile));
        org.mockito.Mockito.lenient().when(paymentProfileService.toRuntime(defaultProfile)).thenReturn(new TbankPaymentProfile(
                1L,
                TbankPaymentProfile.PRIMARY_CODE,
                "Основной магазин",
                true,
                "terminal",
                "password",
                true
        ));
        org.mockito.Mockito.lenient().when(paymentProfileService.toRuntimeForTerminal(defaultProfile, "terminal")).thenReturn(new TbankPaymentProfile(
                1L,
                TbankPaymentProfile.PRIMARY_CODE,
                "Основной магазин",
                true,
                "terminal",
                "password",
                true
        ));
        org.mockito.Mockito.lenient().when(paymentProfileService.isTestTerminal("terminal")).thenReturn(true);
        org.mockito.Mockito.lenient().when(paymentSuccessClientNotifier.notifySuccess(any(PaymentLink.class)))
                .thenReturn(ClientMessageSendResult.sent("test"));
        org.mockito.Mockito.lenient().when(appSettingService.getString(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        return properties;
    }

    private Order order(Long id, String companyTitle, BigDecimal sum) {
        Company company = new Company();
        company.setTitle(companyTitle);

        Order order = new Order();
        order.setId(id);
        order.setCompany(company);
        order.setSum(sum);
        return order;
    }

    private Manager manager(String username) {
        User user = new User();
        user.setUsername(username);

        Manager manager = new Manager();
        manager.setUser(user);
        return manager;
    }

    private PaymentProfile profile(Long id, String code, String name, String terminalKey) {
        PaymentProfile profile = new PaymentProfile();
        profile.setId(id);
        profile.setCode(code);
        profile.setName(name);
        profile.setProvider(PaymentProfile.PROVIDER_TBANK);
        profile.setTerminalKey(terminalKey);
        profile.setPasswordEnvKey("OTZIV_PAYMENTS_TBANK_PASSWORD");
        profile.setEnabled(true);
        profile.setDefaultProfile(TbankPaymentProfile.PRIMARY_CODE.equals(code));
        profile.setTestMode(true);
        return profile;
    }
}
