package com.hunt.otziv.t_telegrambot.service;

import com.hunt.otziv.t_telegrambot.MyTelegramBot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationSchedulerToTelegramImpl implements NotificationSchedulerToTelegram{
    private final MyTelegramBot myTelegramBot;

    // каждый день в 9:25
    @Scheduled(cron = "0 23 10 * * *")
    public void sendDailyReport() {
        myTelegramBot.sendMessage(794146111,"Доброе утро! Отчёт за сегодня готов.");
    }
}
