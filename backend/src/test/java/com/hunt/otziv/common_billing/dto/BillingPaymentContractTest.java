package com.hunt.otziv.common_billing.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hunt.otziv.payments.dto.PublicPaymentLinkResponse;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class BillingPaymentContractTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void realBillingAccountSerializationMatchesSharedRuntimeFixture() throws Exception {
        assertContract(account(), "CommonBillingAccountResponse", "billing-account-current");
    }

    @Test
    void realPublicPaymentSerializationMatchesSharedRuntimeFixture() throws Exception {
        assertContract(payment(), "PublicPaymentLinkResponse", "public-payment-current");
    }

    private void assertContract(Object response, String schemaName, String fixtureName) throws Exception {
        JsonNode actual = mapper.readTree(mapper.writeValueAsString(response));
        assertEquals(mapper.readTree(contractPath("fixtures/" + fixtureName + ".json").toFile()), actual);
        JsonNode schemas = mapper.readTree(contractPath("generated/billing-payments.openapi.json").toFile())
                .path("components").path("schemas");
        assertSchema(actual, schemas.path(schemaName), schemas);
    }

    private CommonInvoiceSummaryResponse invoice() {
        return new CommonInvoiceSummaryResponse(
                301L /* id */,
                41L /* accountId */,
                "Общий счет" /* accountName */,
                "Счет 301" /* title */,
                "invoice-token" /* token */,
                "/pay/group/invoice-token" /* publicUrl */,
                "PARTIALLY_PAID" /* status */,
                2 /* totalOrders */,
                2 /* readyOrders */,
                1 /* paidOrders */,
                new BigDecimal("1250.5") /* amount */,
                new BigDecimal("250.5") /* paid */,
                new BigDecimal("1000") /* remaining */,
                125050L /* amountKopecks */,
                25050L /* paidKopecks */,
                100000L /* remainingKopecks */,
                LocalDateTime.parse("2026-09-07T12:30:00") /* sentAt */,
                null /* lastReminderAt */,
                null /* nextReminderAt */,
                null /* closedAt */,
                null /* closedBy */,
                null /* closeReason */,
                null /* lastError */,
                null /* paymentSuccessNotificationError */,
                null /* tbankOrderId */,
                null /* tbankPaymentId */,
                null /* tbankPaymentAmountKopecks */,
                null /* tbankTerminalLabel */,
                null /* tbankTerminalKey */,
                "EMPLOYEE_REQUISITES" /* paymentRouteType */,
                "MANUAL" /* paymentRouteProvider */,
                "Реквизиты сотрудника" /* paymentRouteProfileName */,
                "Получатель" /* paymentRouteRecipient */,
                null /* paymentRouteManualTaskId */,
                true /* contractorPaymentRoute */,
                LocalDateTime.parse("2026-09-07T12:00:00") /* paymentRouteSelectedAt */,
                null /* invoicePurpose */,
                null /* supersedesInvoiceId */,
                "EMPLOYEE_REQUISITES" /* invoicePaymentMode */,
                null /* paperInvoiceIssuedAt */);
    }

    private CommonBillingAccountResponse account() {
        return new CommonBillingAccountResponse(
                41L /* id */,
                "Общий счет" /* name */,
                true /* enabled */,
                true /* autoRepeatOrders */,
                17L /* managerId */,
                "Менеджер" /* managerName */,
                91L /* invoiceCompanyId */,
                "Компания" /* invoiceCompanyTitle */,
                List.of(new CommonBillingCompanyResponse(91L, "Компания", true)) /* companies */,
                invoice() /* currentInvoice */,
                "EMPLOYEE_REQUISITES" /* invoicePaymentMode */);
    }

    private PublicPaymentLinkResponse payment() {
        return new PublicPaymentLinkResponse(
                "payment-token" /* token */,
                202L /* orderId */,
                "Компания" /* companyTitle */,
                "Филиал" /* filialTitle */,
                "Отзывы" /* serviceTitle */,
                new BigDecimal("1250.5") /* amount */,
                125050L /* amountKopecks */,
                "Заказ 202" /* description */,
                "client@example.test" /* payerEmail */,
                "CREATED" /* status */,
                "BANK_FORM" /* paymentMethod */,
                "T_BANK" /* provider */,
                LocalDateTime.parse("2026-09-08T12:30:00") /* expiresAt */,
                true /* payable */,
                "SBP_PRIMARY" /* paymentPageMode */,
                true /* sbpBankSelectionSupported */,
                true /* tpayEnabled */,
                false /* sberpayEnabled */,
                false /* mirpayEnabled */,
                null /* manualPaymentType */,
                null /* manualPhone */,
                null /* manualRecipientName */,
                null /* manualBankName */,
                null /* manualPaymentUrl */,
                null /* manualPaymentButtonLabel */,
                null /* manualComment */,
                null /* receiptStatus */);
    }

    private void assertSchema(JsonNode value, JsonNode schema, JsonNode schemas) {
        assertFalse(schema.isMissingNode(), "Generated schema is missing");
        if (schema.has("$ref")) {
            String reference = schema.path("$ref").asText();
            assertSchema(value, schemas.path(reference.substring(reference.lastIndexOf('/') + 1)), schemas);
            return;
        }
        if (schema.has("anyOf")) {
            for (JsonNode candidate : schema.path("anyOf")) {
                if ("null".equals(candidate.path("type").asText()) && value.isNull()) return;
                if (!"null".equals(candidate.path("type").asText()) && !value.isNull()) {
                    assertSchema(value, candidate, schemas);
                    return;
                }
            }
            fail("Value does not match nullable schema: " + value);
        }
        switch (schema.path("type").asText()) {
            case "object" -> {
                assertTrue(value.isObject());
                Set<String> actualFields = new HashSet<>();
                value.fieldNames().forEachRemaining(actualFields::add);
                Set<String> contractFields = new HashSet<>();
                schema.path("properties").fieldNames().forEachRemaining(contractFields::add);
                assertEquals(contractFields, actualFields, "Java serialization and generated fields differ");
                for (JsonNode field : schema.path("required")) assertTrue(value.has(field.asText()), field.asText());
                schema.path("properties").fields().forEachRemaining(entry ->
                        assertSchema(value.path(entry.getKey()), entry.getValue(), schemas));
            }
            case "array" -> {
                assertTrue(value.isArray());
                value.forEach(item -> assertSchema(item, schema.path("items"), schemas));
            }
            case "string" -> assertTrue(value.isTextual(), value.toString());
            case "boolean" -> assertTrue(value.isBoolean(), value.toString());
            case "integer" -> assertTrue(value.isIntegralNumber(), value.toString());
            case "number" -> assertTrue(value.isNumber(), value.toString());
            default -> fail("Unsupported contract schema: " + schema);
        }
    }

    private Path contractPath(String relative) {
        Path root = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(root.resolve("contracts"))) root = root.getParent();
        Path file = root.resolve("contracts").resolve(relative);
        assertTrue(Files.isRegularFile(file), "Missing committed contract: " + file);
        return file;
    }
}
