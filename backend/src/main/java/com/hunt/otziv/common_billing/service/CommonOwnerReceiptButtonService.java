package com.hunt.otziv.common_billing.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.common_billing.api.CommonOwnerReceiptConfirmation;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentAttributionRequest;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
class CommonOwnerReceiptButtonService implements CommonOwnerReceiptConfirmation {
    private final CommonInvoiceManualPaymentWorkflow payments;
    private final CommonOwnerReceiptChats chats;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    private record Notice(long invoiceId, long ownerId, String decision, boolean attention, String requestJson) {}

    @Override
    public Result confirm(String token, long chatId, Principal actor) {
        if (!(actor instanceof Authentication auth) || !auth.isAuthenticated()
                || token == null || !token.matches("[a-f0-9]{32}")) throw forbidden();
        Notice notice = jdbc.query("""
                SELECT r.invoice_id, n.owner_id, r.decision, r.attention_mode, r.request_json
                FROM common_owner_receipt_notice n JOIN common_owner_receipt_approval r ON r.id=n.report_id
                WHERE n.callback_token=? AND n.chat_id=? AND n.state IN ('SENT','UNCERTAIN','SENDING')
                """, (rs, row) -> new Notice(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getBoolean(4),
                        rs.getString(5)), token, chatId).stream().findFirst().orElseThrow(this::forbidden);
        var route = chats.forInvoice(notice.invoiceId()).orElseThrow(this::forbidden);
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        boolean owner = auth.getAuthorities().stream().anyMatch(a -> "ROLE_OWNER".equals(a.getAuthority()));
        if (route.ownerId() != notice.ownerId() || !route.destinations().contains(chatId)
                || !(admin || (owner && route.ownerLogin().equalsIgnoreCase(actor.getName())))) throw forbidden();
        if ("ACCEPTED".equals(notice.decision())) return new Result(notice.invoiceId(), true);
        if (notice.decision() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Запрос уже отклонён");
        CommonManualPaymentAttributionRequest request;
        try {
            request = json.readValue(notice.requestJson(), CommonManualPaymentAttributionRequest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось прочитать запрос подтверждения", e);
        }
        if (notice.attention()) {
            payments.reportPaidByManualCardTransferWithAttributions(notice.invoiceId(), request, actor);
        } else {
            payments.markPaidWithAttributions(notice.invoiceId(), request, actor);
        }
        return new Result(notice.invoiceId(), false);
    }

    private ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Кнопка недоступна в этом чате или для этого пользователя");
    }
}
