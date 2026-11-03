package com.hunt.otziv.client_messages.service;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class ReviewArchiveCheckMonitor {
    private final JdbcTemplate jdbc;
    private final ClientMessageTime time;
    public record Row(long stateId,long orderId,String company,String channel,String outcome,
                      String reason,LocalDateTime checkedAt,LocalDateTime nextCheckAt) { }
    @Transactional(readOnly=true)
    public List<Row> snapshot() {
        return jdbc.query("""
            SELECT s.state_id,s.order_id,c.company_title,s.archive_check_channel,s.archive_check_status,
              s.archive_block_reason,s.archive_checked_at,s.archive_next_check_at
            FROM scheduled_client_message_state s LEFT JOIN companies c ON c.company_id=s.company_id
            WHERE s.scenario='REVIEW_CHECK_REMINDER' AND s.sent_count>=7
              AND (s.state_status='ACTIVE' OR s.archive_checked_at>DATE_SUB(NOW(6),INTERVAL 7 DAY))
            ORDER BY s.archive_checked_at DESC,s.state_id LIMIT 200
            """,(rs,n)->new Row(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),
                convert(rs.getTimestamp(7)),convert(rs.getTimestamp(8))));
    }
    private LocalDateTime convert(java.sql.Timestamp value) {return value==null?null:time.toIrkutskTime(value.toLocalDateTime());}
}
