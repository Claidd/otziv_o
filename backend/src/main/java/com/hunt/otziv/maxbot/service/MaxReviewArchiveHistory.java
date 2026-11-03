package com.hunt.otziv.maxbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hunt.otziv.client_chat_control.api.ReviewArchiveHistory;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** GET only. MAX returns newest messages first; from is the upper time bound in milliseconds. */
@Service @RequiredArgsConstructor
public class MaxReviewArchiveHistory implements ReviewArchiveHistory.Reader {
    private final MaxBotClient client;
    @Override public String platform() { return "MAX"; }

    @Override public ReviewArchiveHistory.Check read(ReviewArchiveHistory.Chat chat,LocalDateTime since) {
        LocalDateTime checkedAt=LocalDateTime.now();
        if(!client.isConfigured())
            return result(ReviewArchiveHistory.Outcome.HISTORY_MISSING,"MAX не подключён для чтения истории. Система повторит проверку после восстановления подключения.",since,checkedAt,0);
        long lower=since.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long upper=checkedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long chatId=Long.parseLong(chat.chatId());
        int total=0;
        var seen=new HashSet<String>();
        for(int page=0;page<20;page++) {
            JsonNode response=client.archiveHistoryPage(chatId,upper);
            JsonNode rows=response.path("messages");
            if(!rows.isArray() || rows.size()>100 || response.hasNonNull("code") || response.hasNonNull("error")
                    || response.has("success") && (!response.path("success").isBoolean() || !response.path("success").booleanValue()))
                return result(ReviewArchiveHistory.Outcome.HISTORY_UNAVAILABLE,"MAX не подтвердил чтение истории. Система повторит проверку.",since,checkedAt,total);
            long oldest=Long.MAX_VALUE;
            int added=0;
            boolean reply=false;
            for(JsonNode message:rows) {
                var time=message.path("timestamp");
                var destination=message.path("recipient").path("chat_id");
                String id=message.path("body").path("mid").asText("");
                var sender=message.path("sender");
                if(!time.isIntegralNumber() || time.asLong()<=0 || time.asLong()>upper
                        || !destination.isIntegralNumber() || destination.asLong()!=chatId || id.isBlank()
                        || !sender.path("is_bot").isBoolean())
                    return result(ReviewArchiveHistory.Outcome.HISTORY_UNAVAILABLE,"MAX вернул неполные данные переписки. Система повторит проверку.",since,checkedAt,total);
                oldest=Math.min(oldest,time.asLong());
                if(seen.add(id)) {total++;added++;}
                if(time.asLong()>=lower && !sender.path("is_bot").booleanValue()) reply=true;
            }
            if(reply) return result(ReviewArchiveHistory.Outcome.REPLY_FOUND,"В MAX найден ответ после последнего напоминания. Автоархив приостановлен.",since,checkedAt,total);
            if(rows.size()<100 || oldest<lower)
                return result(ReviewArchiveHistory.Outcome.CLEAR,"История MAX проверена; ответов после последнего напоминания нет.",since,checkedAt,total);
            // Inclusive overlap avoids losing a client message sharing a timestamp with the boundary.
            // A saturated boundary or a provider ignoring pagination cannot prove complete history.
            if(oldest>=upper || added==0)
                return result(ReviewArchiveHistory.Outcome.HISTORY_UNAVAILABLE,"MAX не подтвердил полную страницу истории. Система повторит проверку.",since,checkedAt,total);
            upper=oldest;
        }
        return result(ReviewArchiveHistory.Outcome.HISTORY_UNAVAILABLE,"История MAX превышает предел одной проверки. Автоархив приостановлен до полной проверки.",since,checkedAt,total);
    }
    private ReviewArchiveHistory.Check result(ReviewArchiveHistory.Outcome outcome,String reason,LocalDateTime since,LocalDateTime checkedAt,int messages) {
        return new ReviewArchiveHistory.Check(outcome,reason,since,checkedAt,messages,"max_history");
    }
}
