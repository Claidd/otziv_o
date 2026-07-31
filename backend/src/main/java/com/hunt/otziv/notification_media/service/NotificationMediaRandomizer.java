package com.hunt.otziv.notification_media.service;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class NotificationMediaRandomizer {

    public boolean shouldAttach(int probabilityPercent) {
        return probabilityPercent >= 100
                || probabilityPercent > 0
                && ThreadLocalRandom.current().nextInt(100) < probabilityPercent;
    }

    public int index(int size) {
        if (size <= 1) {
            return 0;
        }
        return ThreadLocalRandom.current().nextInt(size);
    }
}
