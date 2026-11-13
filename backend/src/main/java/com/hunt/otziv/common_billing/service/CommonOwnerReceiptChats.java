package com.hunt.otziv.common_billing.service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class CommonOwnerReceiptChats {
    private final JdbcTemplate jdbc;

    record Route(long ownerId, String ownerLogin, Long groupChat, Long adminChat) {
        List<Long> destinations() {
            return Stream.of(groupChat, adminChat).filter(java.util.Objects::nonNull).distinct().toList();
        }
    }

    Optional<Route> forInvoice(long invoiceId) {
        // The account manager (or the sole manager of its orders) determines the owner. The reporting
        // user may be a substitute. A manager linked to several owners cannot identify the payee.
        return jdbc.query("""
                WITH responsible AS (
                    SELECT COALESCE(a.manager_id,
                        (SELECT MIN(o.order_manager) FROM common_invoice_orders item
                         JOIN orders o ON o.order_id=item.order_id
                         WHERE item.invoice_id=i.invoice_id AND item.active_membership=1
                         HAVING COUNT(DISTINCT o.order_manager)=1)) AS manager_id
                    FROM common_invoices i JOIN common_billing_accounts a ON a.account_id=i.account_id
                    WHERE i.invoice_id=?
                ), payee AS (
                    SELECT MIN(u.id) AS owner_id, MIN(u.username) AS login
                    FROM responsible r JOIN managers_users link ON link.manager_id=r.manager_id
                    JOIN users u ON u.id=link.user_id AND u.active=1
                    JOIN users_roles ur ON ur.user_id=u.id JOIN roles role ON role.id=ur.role_id
                    WHERE role.name='ROLE_OWNER' HAVING COUNT(DISTINCT u.id)=1
                )
                SELECT p.owner_id, p.login,
                    (SELECT m.audit_telegram_group_chat_id FROM managers m JOIN users u ON u.id=m.user_id
                     JOIN managers_users membership ON membership.manager_id=m.manager_id AND membership.user_id=p.owner_id
                     WHERE u.active=1 AND m.audit_telegram_group_chat_id<0
                       AND ((route.owner_login IS NULL AND m.manager_id=r.manager_id)
                            OR u.username=route.group_manager_login)) AS group_chat,
                    (SELECT u.telegram_chat_id FROM users u JOIN users_roles ur ON ur.user_id=u.id
                     JOIN roles role ON role.id=ur.role_id AND role.name='ROLE_ADMIN'
                     WHERE u.username=route.copy_admin_login AND u.active=1 AND u.telegram_chat_id>0) AS admin_chat
                FROM payee p CROSS JOIN responsible r
                LEFT JOIN common_owner_receipt_chat_route route ON route.owner_login=p.login
                """, (rs, row) -> new Route(rs.getLong("owner_id"), rs.getString("login"),
                        rs.getObject("group_chat", Long.class), rs.getObject("admin_chat", Long.class)), invoiceId)
                .stream().findFirst();
    }
}
