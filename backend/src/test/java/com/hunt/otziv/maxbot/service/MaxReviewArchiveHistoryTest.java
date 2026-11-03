package com.hunt.otziv.maxbot.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.hunt.otziv.client_chat_control.api.ReviewArchiveHistory;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MaxReviewArchiveHistoryTest {
    final MaxBotClient client=mock(MaxBotClient.class);
    final MaxReviewArchiveHistory reader=new MaxReviewArchiveHistory(client);
    final ObjectMapper json=new ObjectMapper();
    final LocalDateTime since=LocalDateTime.now().minusDays(3);
    final long now=Instant.now().toEpochMilli()-1000;
    final ReviewArchiveHistory.Chat chat=new ReviewArchiveHistory.Chat("MAX",null,"-91");
    @BeforeEach void configure() {when(client.isConfigured()).thenReturn(true);}
    ObjectNode message(String id,long timestamp,boolean bot) {
        var m=json.createObjectNode();m.put("timestamp",timestamp);m.putObject("recipient").put("chat_id",-91);
        m.putObject("body").put("mid",id);m.putObject("sender").put("is_bot",bot);return m;
    }
    ObjectNode page(JsonNode... rows) {
        var body=json.createObjectNode();var messages=body.putArray("messages");for(var row:rows) messages.add(row);return body;
    }
    @Test void emptyAccessibleHistoryIsClearAndNeverSendsAnything() {
        when(client.archiveHistoryPage(eq(-91L),anyLong())).thenReturn(page());
        assertThat(reader.read(chat,since).outcome()).isEqualTo(ReviewArchiveHistory.Outcome.CLEAR);
        verify(client).archiveHistoryPage(eq(-91L),anyLong());verify(client).isConfigured();verifyNoMoreInteractions(client);
    }
    @Test void anyHumanMessageIncludingMediaWithoutTextBlocksArchival() {
        when(client.archiveHistoryPage(eq(-91L),anyLong())).thenReturn(page(message("mid.reply",now,false)));
        assertThat(reader.read(chat,since).outcome()).isEqualTo(ReviewArchiveHistory.Outcome.REPLY_FOUND);
    }
    @Test void secondPageAndInclusiveBoundaryAreReadBeforeAssertingSilence() {
        var first=json.createObjectNode();var rows=first.putArray("messages");
        for(int i=0;i<100;i++) rows.add(message("mid."+i,now-i*1000,true));
        when(client.archiveHistoryPage(eq(-91L),anyLong())).thenReturn(first,page(
                message("mid.99",now-99000,true),message("mid.reply",now-100000,false)));
        assertThat(reader.read(chat,since).outcome()).isEqualTo(ReviewArchiveHistory.Outcome.REPLY_FOUND);
        verify(client).archiveHistoryPage(-91L,now-99000);
    }
    @Test void saturatedTimestampBoundaryAndNonAdvancingPagesCannotProveSilence() {
        var first=json.createObjectNode();var rows=first.putArray("messages");
        for(int i=0;i<100;i++) rows.add(message("mid."+i,now,true));
        when(client.archiveHistoryPage(eq(-91L),anyLong())).thenReturn(first);
        assertThat(reader.read(chat,since).outcome()).isEqualTo(ReviewArchiveHistory.Outcome.HISTORY_UNAVAILABLE);
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"messages\":null}","{\"code\":\"access.denied\",\"messages\":[]}",
            "{\"messages\":[{}]}","{\"messages\":[{\"timestamp\":1,\"recipient\":{\"chat_id\":42}}]}"})
    void incompleteProviderDataCannotAuthorizeArchival(String body) throws Exception {
        when(client.archiveHistoryPage(eq(-91L),anyLong())).thenReturn(json.readTree(body));
        assertThat(reader.read(chat,since).outcome()).isEqualTo(ReviewArchiveHistory.Outcome.HISTORY_UNAVAILABLE);
    }
}
