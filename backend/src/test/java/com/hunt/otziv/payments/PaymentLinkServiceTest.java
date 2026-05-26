package com.hunt.otziv.payments;

import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderTransactionService;
import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.dto.PublicPaymentInitResponse;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

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
        verify(orderTransactionService).handlePaymentStatus(order);
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
    void initSbpCreatesQrAfterBankInitAndStoresPaymentMethod() {
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
                "<svg></svg>"
        ));

        PublicPaymentInitResponse response = service.initSbp(
                "token",
                "SBP@EXAMPLE.RU",
                true,
                true,
                true,
                "203.0.113.8",
                "JUnit UA"
        );

        ArgumentCaptor<TbankGetQrCommand> qrCaptor = ArgumentCaptor.forClass(TbankGetQrCommand.class);
        verify(tbankClient).getQr(any(TbankPaymentProfile.class), qrCaptor.capture());
        assertEquals("payment-sbp", qrCaptor.getValue().paymentId());
        assertEquals("IMAGE", qrCaptor.getValue().dataType());
        assertEquals(PaymentMethod.SBP_QR, link.getPaymentMethod());
        assertEquals("<svg></svg>", link.getSbpQrImage());
        assertEquals("IMAGE", link.getSbpQrDataType());
        assertNotNull(link.getSbpQrCreatedAt());
        assertEquals("SBP_QR", response.method());
        assertEquals("<svg></svg>", response.qrImage());
        assertEquals("sbp@example.ru", link.getPayerEmail());
        verify(paymentLinkRepository).save(link);
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

        when(paymentLinkRepository.findTop100ByOrderByCreatedAtDesc()).thenReturn(List.of(link));
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

        List<AdminPaymentLinkResponse> response = service.adminLinks();

        assertEquals(PaymentLinkStatus.TEST_CONFIRMED, link.getStatus());
        assertEquals("TEST_CONFIRMED", response.get(0).status());
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

    private PaymentLinkService service(TbankPaymentProperties properties) {
        return service(properties, new TbankTokenSigner());
    }

    private PaymentLinkService service(TbankPaymentProperties properties, TbankTokenSigner signer) {
        return new PaymentLinkService(
                paymentLinkRepository,
                orderRepository,
                badReviewTaskService,
                orderTransactionService,
                properties,
                runtimeSettingsService,
                paymentProfileService,
                tbankClient,
                signer
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
