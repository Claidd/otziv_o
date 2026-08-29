package com.hunt.otziv.payments.tochka.service;

import com.hunt.otziv.payments.tochka.config.TochkaPaymentProperties;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.CreatePaymentRequest;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.CreatePaymentRequestData;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.CreatePaymentResponse;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.PaymentInfoResponse;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.PaymentOperation;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.ReceiptClient;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.ReceiptItem;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.RefundRequest;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.RefundRequestData;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.RefundResponse;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.Retailer;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.RetailerListResponse;
import com.hunt.otziv.payments.tochka.dto.TochkaCreatePaymentCommand;
import com.hunt.otziv.payments.tochka.dto.TochkaPaymentProfile;
import com.hunt.otziv.payments.tochka.dto.TochkaRefundCommand;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentMode;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class TochkaClient {

    private static final String ACQUIRING_PATH = "/acquiring/v1.0";
    private static final long MAX_TTL_MINUTES = 44_640;
    private static final int RECONCILIATION_PAGE_SIZE = 100;
    private static final int MAX_RECONCILIATION_PAGES = 100;
    private static final long MAX_RECONCILIATION_DAYS = 7;
    private static final int MAX_REDIRECT_URL_LENGTH = 2_083;
    private static final Pattern CUSTOMER_CODE_PATTERN = Pattern.compile("[A-Za-z0-9]{9}");
    private static final Pattern MERCHANT_ID_PATTERN = Pattern.compile("[0-9]{15}");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Set<String> PAYMENT_STATUSES = Set.of(
            "CREATED",
            "APPROVED",
            "ON-REFUND",
            "REFUNDED",
            "EXPIRED",
            "REFUNDED_PARTIALLY",
            "AUTHORIZED",
            "WAIT_FULL_PAYMENT"
    );

    private final RestTemplate restTemplate;
    private final TochkaPaymentProperties properties;

    public TochkaClient(
            @Qualifier("tochkaRestTemplate") RestTemplate restTemplate,
            TochkaPaymentProperties properties
    ) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public RetailerListResponse getRetailers() {
        return getRetailers(properties.defaultProfile());
    }

    public RetailerListResponse getRetailers(TochkaPaymentProfile profile) {
        validateAuthentication(profile);
        URI uri = UriComponentsBuilder
                .fromUriString(baseUrl(profile) + ACQUIRING_PATH + "/retailers")
                .queryParam("customerCode", profile.customerCode())
                .build()
                .encode()
                .toUri();
        return exchange(profile, uri, HttpMethod.GET, null, RetailerListResponse.class, "Get Retailers");
    }

    public Retailer verifyRetailerReadiness(TochkaPaymentProfile profile) {
        validateAuthentication(profile);
        if (profile.requiredCashbox().isBlank()) {
            throw conflict(
                    "Для боевого профиля Точки не задано ожидаемое имя кассы из Get Retailers"
            );
        }
        return verifyRetailerReadiness(profile, profile.paymentModes());
    }

    public CreatePaymentResponse createPaymentWithReceipt(TochkaCreatePaymentCommand command) {
        return createPaymentWithReceipt(properties.defaultProfile(), command);
    }

    public CreatePaymentResponse createPaymentWithReceipt(
            TochkaPaymentProfile profile,
            TochkaCreatePaymentCommand command
    ) {
        validateCreate(profile, command);
        if (!profile.testMode()) {
            verifyRetailerReadiness(profile, requestedPaymentModes(profile, command));
        }
        URI uri = URI.create(baseUrl(profile) + ACQUIRING_PATH + "/payments_with_receipt");
        CreatePaymentResponse response = exchange(
                profile,
                uri,
                HttpMethod.POST,
                createRequest(profile, command),
                CreatePaymentResponse.class,
                "Create Payment Operation With Receipt"
        );
        validateCreateResponse(profile, command, response);
        return response;
    }

    public PaymentInfoResponse getPaymentInfo(String operationId) {
        return getPaymentInfo(properties.defaultProfile(), operationId);
    }

    public PaymentInfoResponse getPaymentInfo(TochkaPaymentProfile profile, String operationId) {
        validateAuthentication(profile);
        String cleanOperationId = required(operationId, "operationId");
        URI uri = UriComponentsBuilder
                .fromUriString(baseUrl(profile) + ACQUIRING_PATH + "/payments")
                .pathSegment(cleanOperationId)
                .build()
                .encode()
                .toUri();
        PaymentInfoResponse response = exchange(
                profile,
                uri,
                HttpMethod.GET,
                null,
                PaymentInfoResponse.class,
                "Get Payment Operation Info"
        );
        validatePaymentInfoResponse(profile, cleanOperationId, response);
        return response;
    }

    /**
     * Resolves an ambiguous create result without repeating the POST request. The Tochka API does
     * not offer a paymentLinkId filter, so reconciliation has to scan a deliberately short date
     * interval and match the merchant-generated id locally.
     */
    public Optional<PaymentOperation> findPaymentByPaymentLinkId(
            TochkaPaymentProfile profile,
            String paymentLinkId,
            long expectedAmountKopecks,
            LocalDate fromDate,
            LocalDate toDate
    ) {
        validateAuthentication(profile);
        String cleanPaymentLinkId = required(paymentLinkId, "paymentLinkId");
        BigDecimal expectedAmount = rubles(expectedAmountKopecks);
        validateReconciliationWindow(fromDate, toDate);

        List<PaymentOperation> matches = new ArrayList<>();
        for (int page = 1; page <= MAX_RECONCILIATION_PAGES; page++) {
            URI uri = UriComponentsBuilder
                    .fromUriString(baseUrl(profile) + ACQUIRING_PATH + "/payments")
                    .queryParam("customerCode", profile.customerCode())
                    .queryParam("fromDate", fromDate)
                    .queryParam("toDate", toDate)
                    .queryParam("page", page)
                    .queryParam("perPage", RECONCILIATION_PAGE_SIZE)
                    .build(true)
                    .toUri();
            PaymentInfoResponse response = exchange(
                    profile,
                    uri,
                    HttpMethod.GET,
                    null,
                    PaymentInfoResponse.class,
                    "Get Payment Operation List"
            );
            validatePaymentListResponse(response, page);
            List<PaymentOperation> operations = operations(response);
            operations.stream()
                    .filter(operation -> cleanPaymentLinkId.equals(operation.paymentLinkId()))
                    .filter(operation -> belongsToProfile(profile, operation))
                    .forEach(matches::add);

            if (!hasNextPage(response, page)) {
                break;
            }
            if (page == MAX_RECONCILIATION_PAGES) {
                throw new TochkaProviderException(
                        "Список операций Точки слишком велик для безопасной сверки paymentLinkId",
                        false,
                        null
                );
            }
        }
        if (matches.size() > 1) {
            throw new TochkaProviderException(
                    "Точка API вернула несколько операций с одним paymentLinkId",
                    false,
                    null
            );
        }
        Optional<PaymentOperation> match = matches.stream().findFirst();
        match.ifPresent(operation -> validateRecoveredPayment(
                profile,
                cleanPaymentLinkId,
                expectedAmount,
                operation
        ));
        return match;
    }

    public RefundResponse refund(TochkaRefundCommand command) {
        return refund(properties.defaultProfile(), command);
    }

    public RefundResponse refund(TochkaPaymentProfile profile, TochkaRefundCommand command) {
        validateAuthentication(profile);
        if (command == null) {
            throw badRequest("Не заданы параметры возврата Точки");
        }
        String operationId = required(command.operationId(), "operationId");
        BigDecimal amount = rubles(command.amountKopecks());
        URI uri = UriComponentsBuilder
                .fromUriString(baseUrl(profile) + ACQUIRING_PATH + "/payments")
                .pathSegment(operationId, "refund")
                .build()
                .encode()
                .toUri();
        RefundResponse response = exchange(
                profile,
                uri,
                HttpMethod.POST,
                new RefundRequest(new RefundRequestData(amount)),
                RefundResponse.class,
                "Refund Payment Operation"
        );
        validateRefundResponse(operationId, amount, response);
        return response;
    }

    private CreatePaymentRequest createRequest(
            TochkaPaymentProfile profile,
            TochkaCreatePaymentCommand command
    ) {
        BigDecimal amount = rubles(command.amountKopecks());
        List<TochkaPaymentMode> paymentModes = requestedPaymentModes(profile, command);
        int ttlMinutes = Math.toIntExact(profile.linkTtl().toMinutes());
        ReceiptItem item = new ReceiptItem(
                profile.vatType(),
                profile.receiptItemName(),
                amount,
                BigDecimal.ONE,
                profile.paymentMethod(),
                profile.paymentObject(),
                profile.measure()
        );
        return new CreatePaymentRequest(new CreatePaymentRequestData(
                profile.customerCode(),
                amount,
                command.purpose().trim(),
                command.redirectUrl().trim(),
                command.failRedirectUrl().trim(),
                paymentModes,
                profile.merchantId(),
                false,
                ttlMinutes,
                command.paymentLinkId().trim(),
                profile.taxSystemCode(),
                new ReceiptClient(command.email().trim()),
                List.of(item)
        ));
    }

    private <T> T exchange(
            TochkaPaymentProfile profile,
            URI uri,
            HttpMethod method,
            Object body,
            Class<T> responseType,
            String operation
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(profile.jwtToken());
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        try {
            ResponseEntity<T> response = restTemplate.exchange(
                    uri,
                    method,
                    new HttpEntity<>(body, headers),
                    responseType
            );
            T responseBody = response.getBody();
            if (responseBody == null) {
                throw new TochkaProviderException(
                        "Точка API вернула пустой ответ на " + operation,
                        isMutation(method),
                        null
                );
            }
            return responseBody;
        } catch (RestClientResponseException e) {
            boolean outcomeUnknown = isMutation(method)
                    && (e.getStatusCode().is5xxServerError() || e.getStatusCode().value() == 424);
            throw new TochkaProviderException(
                    "Точка API вернула HTTP " + e.getStatusCode().value() + " на " + operation,
                    outcomeUnknown,
                    e
            );
        } catch (RestClientException e) {
            throw new TochkaProviderException(
                    "Не удалось выполнить запрос " + operation + " в Точка API",
                    isMutation(method),
                    e
            );
        }
    }

    private void validateCreateResponse(
            TochkaPaymentProfile profile,
            TochkaCreatePaymentCommand command,
            CreatePaymentResponse response
    ) {
        if (response == null || response.data() == null) {
            throw new TochkaProviderException(
                    "Точка API не вернула данные созданной платежной ссылки",
                    true,
                    null
            );
        }
        if (!"CREATED".equals(response.data().status())) {
            throw ambiguousResponse("Точка API вернула неожиданный статус созданной платежной ссылки");
        }
        if (response.data().operationId() == null || response.data().operationId().isBlank()) {
            throw new TochkaProviderException(
                    "Точка API не вернула operationId созданной платежной ссылки",
                    true,
                null
            );
        }
        if (!profile.testMode()) {
            BigDecimal expectedAmount = rubles(command.amountKopecks());
            if (!sameMoney(expectedAmount, response.data().amount())) {
                throw ambiguousResponse("Точка API вернула другую сумму созданной платежной ссылки");
            }
            if (!profile.customerCode().equals(response.data().customerCode())) {
                throw ambiguousResponse("Точка API вернула другой customerCode созданной платежной ссылки");
            }
            if (!profile.merchantId().equals(response.data().merchantId())) {
                throw ambiguousResponse("Точка API вернула другой merchantId созданной платежной ссылки");
            }
            if (!command.paymentLinkId().trim().equals(response.data().paymentLinkId())) {
                throw ambiguousResponse("Точка API вернула другой paymentLinkId созданной платежной ссылки");
            }
        }
        String paymentLink = response.data().paymentLink();
        if (!isValidHttpsPaymentLink(paymentLink)) {
            throw new TochkaProviderException(
                    "Точка API вернула некорректную ссылку на оплату",
                    true,
                    null
            );
        }
    }

    private void validatePaymentInfoResponse(
            TochkaPaymentProfile profile,
            String operationId,
            PaymentInfoResponse response
    ) {
        List<PaymentOperation> operations = operations(response);
        if (operations.size() != 1) {
            throw new TochkaProviderException(
                    "Точка API должна вернуть ровно одну запрошенную платежную операцию",
                    false,
                    null
            );
        }
        if (profile.testMode()) {
            return;
        }
        PaymentOperation operation = operations.getFirst();
        if (!operationId.equals(operation.operationId()) || !belongsToProfile(profile, operation)) {
            throw new TochkaProviderException(
                    "Точка API вернула данные другой платежной операции",
                    false,
                    null
            );
        }
    }

    private void validatePaymentListResponse(PaymentInfoResponse response, int requestedPage) {
        if (response == null
                || response.data() == null
                || response.data().operations() == null
                || response.meta() == null
                || response.meta().totalPages() == null) {
            throw new TochkaProviderException(
                    "Точка API вернула некорректный список платежных операций",
                    false,
                    null
            );
        }

        List<PaymentOperation> operations = response.data().operations();
        int totalPages = response.meta().totalPages();
        boolean invalidPagination = totalPages < 0
                || (totalPages == 0 && (!operations.isEmpty() || requestedPage != 1))
                || (totalPages > 0 && (requestedPage > totalPages || operations.isEmpty()));
        if (invalidPagination || operations.stream().anyMatch(Objects::isNull)) {
            throw new TochkaProviderException(
                    "Точка API вернула несогласованные данные списка платежных операций",
                    false,
                    null
            );
        }
    }

    private void validateRecoveredPayment(
            TochkaPaymentProfile profile,
            String paymentLinkId,
            BigDecimal expectedAmount,
            PaymentOperation operation
    ) {
        if (!belongsToProfile(profile, operation)
                || !paymentLinkId.equals(operation.paymentLinkId())) {
            throw ambiguousResponse("Сверка Точки вернула платеж другого профиля или заказа");
        }
        if (!sameMoney(expectedAmount, operation.amount())) {
            throw ambiguousResponse("Сверка Точки вернула платеж с другой суммой");
        }
        if (operation.operationId() == null || operation.operationId().isBlank()) {
            throw ambiguousResponse("Сверка Точки не вернула operationId платежа");
        }
        if (!PAYMENT_STATUSES.contains(operation.status())) {
            throw ambiguousResponse("Сверка Точки вернула неизвестный статус платежа");
        }
        if (!isValidHttpsPaymentLink(operation.paymentLink())) {
            throw ambiguousResponse("Сверка Точки вернула некорректную ссылку на оплату");
        }
    }

    private void validateRefundResponse(
            String operationId,
            BigDecimal amount,
            RefundResponse response
    ) {
        if (response == null || response.data() == null) {
            throw ambiguousResponse("Точка API не вернула данные созданного возврата");
        }
        if (response.data().isRefund() == null) {
            throw ambiguousResponse("Точка API не вернула признак создания возврата");
        }
        if (!response.data().isRefund()) {
            throw new TochkaProviderException(
                    "Точка API не подтвердила создание возврата",
                    false,
                    null
            );
        }
        if (!operationId.equals(response.data().operationId())) {
            throw ambiguousResponse("Точка API вернула возврат для другой операции");
        }
        if (!sameMoney(amount, response.data().amount())) {
            throw ambiguousResponse("Точка API вернула другую сумму возврата");
        }
        if (response.data().orderId() == null || response.data().orderId().isBlank()) {
            throw ambiguousResponse("Точка API не вернула orderId созданного возврата");
        }
    }

    private List<PaymentOperation> operations(PaymentInfoResponse response) {
        if (response == null || response.data() == null || response.data().operations() == null) {
            return List.of();
        }
        return response.data().operations().stream()
                .filter(operation -> operation != null)
                .toList();
    }

    private Retailer verifyRetailerReadiness(
            TochkaPaymentProfile profile,
            List<TochkaPaymentMode> requestedModes
    ) {
        if (profile.requiredCashbox().isBlank()) {
            throw conflict(
                    "Для боевого профиля Точки не задано ожидаемое имя кассы из Get Retailers"
            );
        }
        RetailerListResponse response = getRetailers(profile);
        List<Retailer> retailers = response == null
                || response.data() == null
                || response.data().retailers() == null
                ? List.of()
                : response.data().retailers().stream()
                        .filter(retailer -> retailer != null)
                        .filter(retailer -> profile.merchantId().equals(retailer.merchantId()))
                        .toList();
        if (retailers.size() != 1) {
            throw conflict("Get Retailers не подтвердил ровно один настроенный merchantId Точки");
        }

        Retailer retailer = retailers.getFirst();
        if (!"REG".equalsIgnoreCase(retailer.status()) || !retailer.isActive()) {
            throw conflict("Магазин Точки не находится в активном статусе REG");
        }
        List<String> supportedModes = retailer.paymentModes() == null
                ? List.of()
                : retailer.paymentModes();
        boolean modesReady = requestedModes.stream()
                .allMatch(mode -> supportedModes.contains(mode.code()));
        if (!modesReady) {
            throw conflict("Магазин Точки не поддерживает все запрошенные способы оплаты");
        }
        if (!profile.requiredCashbox().equalsIgnoreCase(safe(retailer.cashbox()))) {
            throw conflict("Get Retailers не подтвердил ожидаемую кассу CloudKassir");
        }
        return retailer;
    }

    private List<TochkaPaymentMode> requestedPaymentModes(
            TochkaPaymentProfile profile,
            TochkaCreatePaymentCommand command
    ) {
        return command.paymentModes().isEmpty() ? profile.paymentModes() : command.paymentModes();
    }

    private boolean belongsToProfile(TochkaPaymentProfile profile, PaymentOperation operation) {
        return profile.customerCode().equals(operation.customerCode())
                && profile.merchantId().equals(operation.merchantId());
    }

    private boolean hasNextPage(PaymentInfoResponse response, int page) {
        return page < response.meta().totalPages();
    }

    private void validateReconciliationWindow(LocalDate fromDate, LocalDate toDate) {
        if (fromDate == null || toDate == null) {
            throw badRequest("Для сверки платежа Точки нужно задать fromDate и toDate");
        }
        long days = ChronoUnit.DAYS.between(fromDate, toDate);
        if (days < 0 || days > MAX_RECONCILIATION_DAYS) {
            throw badRequest("Интервал сверки платежа Точки должен быть от 0 до 7 дней");
        }
    }

    private void validateCreate(TochkaPaymentProfile profile, TochkaCreatePaymentCommand command) {
        validateAuthentication(profile);
        if (profile.taxSystemCode() == null) {
            throw conflict(
                    "Для профиля Точки не задан taxSystemCode. Для АУСН укажите объект: "
                            + "usn_income или usn_income_outcome"
            );
        }
        if (profile.vatType() == null || profile.paymentMethod() == null || profile.paymentObject() == null) {
            throw conflict("Не полностью настроены фискальные параметры профиля Точки");
        }
        if (profile.receiptItemName().isBlank() || profile.receiptItemName().length() > 256) {
            throw conflict("Название позиции чека Точки должно содержать от 1 до 256 символов");
        }
        if (!"шт.".equals(profile.measure())) {
            throw conflict("Для позиции услуги Точки единица измерения должна быть «шт.»");
        }
        validateTtl(profile.linkTtl());
        if (command == null) {
            throw badRequest("Не заданы параметры платежной ссылки Точки");
        }
        String paymentLinkId = required(command.paymentLinkId(), "paymentLinkId");
        if (paymentLinkId.length() > 45) {
            throw badRequest("paymentLinkId Точки не должен превышать 45 символов");
        }
        String purpose = required(command.purpose(), "назначение платежа");
        if (purpose.length() > 140) {
            throw badRequest("Назначение платежа Точки не должно превышать 140 символов");
        }
        String email = required(command.email(), "email покупателя");
        if (email.length() > 254 || !EMAIL_PATTERN.matcher(email).matches()) {
            throw badRequest("Некорректный email покупателя для чека Точки");
        }
        validateHttpsUrl(command.redirectUrl(), "redirectUrl");
        validateHttpsUrl(command.failRedirectUrl(), "failRedirectUrl");
        rubles(command.amountKopecks());
        List<TochkaPaymentMode> modes = requestedPaymentModes(profile, command);
        if (modes.isEmpty()) {
            throw conflict("Для профиля Точки не задан ни один способ оплаты");
        }
    }

    private void validateAuthentication(TochkaPaymentProfile profile) {
        if (!properties.isEnabled()) {
            throw conflict("Интернет-эквайринг Точки выключен в настройках");
        }
        if (profile == null || profile.code().isBlank()) {
            throw conflict("Не выбран платежный профиль Точки");
        }
        if (!profile.enabled()) {
            throw conflict("Платежный профиль Точки «" + profile.displayName() + "» выключен");
        }
        if (!CUSTOMER_CODE_PATTERN.matcher(profile.customerCode()).matches()) {
            throw conflict("Для профиля Точки customerCode должен состоять из 9 букв или цифр");
        }
        if (!MERCHANT_ID_PATTERN.matcher(profile.merchantId()).matches()) {
            throw conflict("Для профиля Точки merchantId должен состоять из 15 цифр");
        }
        if (profile.jwtToken().isBlank()) {
            throw conflict("Для профиля Точки не задан JWT-токен");
        }
        baseUrl(profile);
    }

    private void validateTtl(Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw conflict("Срок жизни платежной ссылки Точки должен быть положительным");
        }
        long minutes = ttl.toMinutes();
        if (minutes < 1 || minutes > MAX_TTL_MINUTES) {
            throw conflict("Срок жизни платежной ссылки Точки должен быть от 1 до 44640 минут");
        }
    }

    private void validateHttpsUrl(String value, String field) {
        String clean = required(value, field);
        if (clean.length() > MAX_REDIRECT_URL_LENGTH) {
            throw badRequest(field + " Точки не должен превышать 2083 символа");
        }
        try {
            URI uri = URI.create(clean);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                throw badRequest(field + " Точки должен быть абсолютным HTTPS URL");
            }
        } catch (IllegalArgumentException e) {
            throw badRequest(field + " Точки содержит некорректный URL");
        }
    }

    private BigDecimal rubles(long amountKopecks) {
        if (amountKopecks <= 0) {
            throw badRequest("Сумма платежа Точки должна быть больше нуля");
        }
        return BigDecimal.valueOf(amountKopecks, 2);
    }

    private String baseUrl(TochkaPaymentProfile profile) {
        boolean sandbox = profile != null && profile.testMode();
        String expectedPath = sandbox ? "/sandbox/v2" : "/uapi";
        String configured = properties.baseUrlFor(sandbox);
        try {
            URI uri = URI.create(configured);
            boolean officialEndpoint = "https".equalsIgnoreCase(uri.getScheme())
                    && "enter.tochka.com".equalsIgnoreCase(uri.getHost())
                    && uri.getPort() == -1
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && expectedPath.equals(uri.getPath());
            if (!officialEndpoint) {
                throw new IllegalArgumentException("Unexpected Tochka endpoint");
            }
            return "https://enter.tochka.com" + expectedPath;
        } catch (IllegalArgumentException e) {
            throw conflict(
                    "Некорректный базовый URL Точка API: разрешен только официальный адрес "
                            + "https://enter.tochka.com" + expectedPath
            );
        }
    }

    private boolean sameMoney(BigDecimal expected, BigDecimal actual) {
        return expected != null && actual != null && expected.compareTo(actual) == 0;
    }

    private boolean isValidHttpsPaymentLink(String value) {
        try {
            URI uri = URI.create(safe(value));
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getUserInfo() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private TochkaProviderException ambiguousResponse(String message) {
        return new TochkaProviderException(message, true, null);
    }

    private boolean isMutation(HttpMethod method) {
        return method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH;
    }

    private String required(String value, String field) {
        String clean = safe(value);
        if (clean.isBlank()) {
            throw badRequest("Не задан " + field + " для Точки");
        }
        return clean;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
