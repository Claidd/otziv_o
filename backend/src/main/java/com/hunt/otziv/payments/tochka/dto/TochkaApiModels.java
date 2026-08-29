package com.hunt.otziv.payments.tochka.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentMethod;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentMode;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentObject;
import com.hunt.otziv.payments.tochka.model.TochkaTaxSystemCode;
import com.hunt.otziv.payments.tochka.model.TochkaVatType;
import java.math.BigDecimal;
import java.util.List;

public final class TochkaApiModels {

    private TochkaApiModels() {
    }

    public record CreatePaymentRequest(
            @JsonProperty("Data") CreatePaymentRequestData data
    ) {
    }

    public record CreatePaymentRequestData(
            String customerCode,
            BigDecimal amount,
            String purpose,
            String redirectUrl,
            String failRedirectUrl,
            List<TochkaPaymentMode> paymentMode,
            String merchantId,
            boolean preAuthorization,
            int ttl,
            String paymentLinkId,
            TochkaTaxSystemCode taxSystemCode,
            @JsonProperty("Client") ReceiptClient client,
            @JsonProperty("Items") List<ReceiptItem> items
    ) {
    }

    public record ReceiptClient(
            String email
    ) {
    }

    public record ReceiptItem(
            TochkaVatType vatType,
            String name,
            BigDecimal amount,
            BigDecimal quantity,
            TochkaPaymentMethod paymentMethod,
            TochkaPaymentObject paymentObject,
            String measure
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreatePaymentResponse(
            @JsonProperty("Data") CreatePaymentResponseData data
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreatePaymentResponseData(
            String purpose,
            String status,
            BigDecimal amount,
            String operationId,
            String paymentLink,
            String merchantId,
            String paymentLinkId,
            List<String> paymentMode,
            String customerCode
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RetailerListResponse(
            @JsonProperty("Data") RetailerListData data
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RetailerListData(
            @JsonProperty("Retailer") List<Retailer> retailers
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Retailer(
            String status,
            boolean isActive,
            String name,
            String merchantId,
            String terminalId,
            List<String> paymentModes,
            String cashbox
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentInfoResponse(
            @JsonProperty("Data") PaymentInfoData data,
            @JsonProperty("Meta") ApiMeta meta
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApiMeta(
            Integer totalPages
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentInfoData(
            @JsonProperty("Operation") List<PaymentOperation> operations
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentOperation(
            String customerCode,
            String taxSystemCode,
            String paymentType,
            String paymentId,
            String transactionId,
            String createdAt,
            BigDecimal amount,
            String status,
            String operationId,
            String paymentLink,
            String merchantId,
            String paidAt,
            String paymentLinkId,
            @JsonProperty("Order") List<PaymentOrder> orders
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentOrder(
            String orderId,
            String type,
            BigDecimal amount,
            String time
    ) {
    }

    public record RefundRequest(
            @JsonProperty("Data") RefundRequestData data
    ) {
    }

    public record RefundRequestData(
            BigDecimal amount
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RefundResponse(
            @JsonProperty("Data") RefundResponseData data
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RefundResponseData(
            Boolean isRefund,
            String operationId,
            BigDecimal amount,
            String date,
            String orderId
    ) {
    }
}
