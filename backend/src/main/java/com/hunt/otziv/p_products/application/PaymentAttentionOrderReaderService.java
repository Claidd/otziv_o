package com.hunt.otziv.p_products.application;

import com.hunt.otziv.p_products.api.PaymentAttentionOrderReader;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentAttentionOrderReaderService implements PaymentAttentionOrderReader {
    private final JdbcTemplate jdbc;

    @Override
    @Transactional(readOnly = true)
    public Optional<Contact> awaitingPayment(long orderId) {
        return jdbc.query("""
                SELECT recipient.id, company.company_title, company.company_url_chat
                FROM orders o
                JOIN order_statuses status ON status.order_status_id = o.order_status
                JOIN companies company ON company.company_id = o.order_company
                LEFT JOIN managers order_manager ON order_manager.manager_id = o.order_manager
                LEFT JOIN managers company_manager ON company_manager.manager_id = company.company_manager
                JOIN users recipient ON recipient.id = CASE WHEN o.order_manager IS NOT NULL
                    THEN order_manager.user_id ELSE company_manager.user_id END
                WHERE o.order_id = ? AND status.order_status_title IN ('Выставлен счет', 'Напоминание')
                  AND recipient.active = TRUE
                """, (row, index) -> new Contact(row.getLong(1), row.getString(2), row.getString(3)),
                orderId).stream().findFirst();
    }
}
