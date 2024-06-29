//package com.hunt.otziv.t_telegrambot;
//
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.telegram.telegrambots.meta.TelegramBotsApi;
//import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
//import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
//
//@Configuration
//public class TelegramBotConfig {
//
//    @Bean
//    public TelegramBotsApi telegramBotsApi(MyTelegramBot myTelegramBot) throws TelegramApiException {
//        TelegramBotsApi telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);
//        telegramBotsApi.registerBot(myTelegramBot);
//        return telegramBotsApi;
//    }
//}
