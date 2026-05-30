package com.hunt.otziv.payments.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TbankGetQrBankListResponse(
        @JsonProperty("Success") boolean success,
        @JsonProperty("ErrorCode") String errorCode,
        @JsonProperty("Message") String message,
        @JsonProperty("Details") String details,
        @JsonProperty("TerminalKey") String terminalKey,
        @JsonProperty("BankList")
        @JsonAlias({"Banks", "banks", "bankList", "Members", "members", "BankMembers"})
        List<TbankSbpBank> banks
) {
    public List<TbankSbpBank> safeBanks() {
        return banks == null ? List.of() : banks;
    }

    public String errorText() {
        String cleanMessage = clean(message);
        String cleanDetails = clean(details);
        String cleanErrorCode = clean(errorCode);
        StringBuilder result = new StringBuilder();
        if (!cleanMessage.isBlank()) {
            result.append(cleanMessage);
        }
        if (!cleanDetails.isBlank() && !cleanDetails.equals(cleanMessage)) {
            appendPart(result, cleanDetails);
        }
        if (!cleanErrorCode.isBlank()) {
            appendPart(result, "ErrorCode: " + cleanErrorCode);
        }
        return result.isEmpty() ? "T-Bank GetQrBankList failed" : result.toString();
    }

    private static void appendPart(StringBuilder result, String value) {
        if (!result.isEmpty()) {
            result.append(" ");
        }
        result.append(value);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TbankSbpBank(
            @JsonProperty("BankId")
            @JsonAlias({"bankId", "Id", "id"})
            String bankId,
            @JsonProperty("NspkBankId")
            @JsonAlias({"nspkBankId", "NspkId", "MemberId"})
            String nspkBankId,
            @JsonProperty("BankName")
            @JsonAlias({"Name", "name"})
            String bankName,
            @JsonProperty("BankLogo")
            @JsonAlias({"Logo", "LogoUrl", "LogoURL", "logo", "logoUrl"})
            String bankLogo,
            @JsonProperty("BankOrder")
            @JsonAlias({"Order", "SortOrder", "order", "sortOrder"})
            Integer bankOrder
    ) {
    }
}
