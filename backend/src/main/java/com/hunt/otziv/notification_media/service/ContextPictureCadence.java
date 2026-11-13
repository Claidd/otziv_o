package com.hunt.otziv.notification_media.service;

import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContextPictureCadence {
    private final JdbcTemplate jdbc;

    /** A chat skips one or two eligible notices between illustrated ones, including after restart. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean takeSlot(long chatId) {
        jdbc.update("INSERT INTO staff_picture_cadence(chat_id, skips_left) VALUES (?, ?) "
                + "ON DUPLICATE KEY UPDATE chat_id=VALUES(chat_id)", chatId, gap());
        int skips = jdbc.queryForObject("SELECT skips_left FROM staff_picture_cadence WHERE chat_id=? FOR UPDATE",
                Integer.class, chatId);
        jdbc.update("UPDATE staff_picture_cadence SET skips_left=? WHERE chat_id=?", skips == 0 ? gap() : skips - 1, chatId);
        return skips == 0;
    }

    private static int gap() {
        return ThreadLocalRandom.current().nextInt(1, 3);
    }
}
