package com.hunt.otziv.manager_daily_summary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZonedDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RecordedCommunicationEvidenceTest {
    private static final ZonedDateTime ANSWER = ZonedDateTime.parse("2026-11-19T13:00:00+08:00");

    static String message(String id, String author, String at, String edits) {
        var values = new LinkedHashMap<String,String>();
        values.put("author_is_audited_manager",author); values.put("sender_role","STAFF");
        values.put("actor_user_id","30"); values.put("company_id","221");
        values.put("direction","INCOMING"); values.put("matched_company_count","5");
        values.put("communication_recorded","true"); values.put("edit_count",edits);
        values.put("message_at",at); values.put("created_at","2026-11-19T12:05:48");
        values.put("recorded_text","Здравствуйте, оплатите, пожалуйста");
        var result = new StringBuilder(); var mapper = new ObjectMapper();
        values.forEach((field,value) -> {
            try { result.append(mapper.writeValueAsString(Map.of("id","message:"+id+":"+field,
                    "field",field,"value",value,"source",field.equals("recorded_text")?"recorded_chat_message":"client_chat_message",
                    "sourceTaskId",10217,"observedAt","2026-11-20T10:00:00+08:00"))).append('\n'); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
        return result.toString();
    }

    @Test void laterDiscoveryPreservesAuthorshipAndHistoricalTimeWithoutAddingCurrentFinances() {
        String fresh = message("57822","1","2026-11-19T12:05:46","0")
                + "{\"id\":\"invoice:322:paid_kopecks\",\"source\":\"invoice\",\"field\":\"paid_kopecks\",\"value\":120000}";
        String added = RecordedCommunicationEvidence.supplement(fresh,"",ANSWER);
        assertThat(added).contains("historical_client_chat_message","historical_recorded_chat_message","57822","INCOMING");
        assertThat(added).doesNotContain("paid_kopecks");
        assertThat(RecordedCommunicationEvidence.fingerprint(fresh,ANSWER)).hasSize(64);
        assertThat(RecordedCommunicationEvidence.fingerprint(fresh.replace("2026-11-20T10:00:00+08:00","2026-11-21T10:00:00+08:00"),ANSWER))
                .isEqualTo(RecordedCommunicationEvidence.fingerprint(fresh,ANSWER));
    }

    @Test void laterMessageOtherAuthorEditedOrUnknownDateCannotProveAnEarlierAnswer() {
        for (String wrong : List.of(message("1","0","2026-11-19T12:05:46","0"),
                message("2","1","2026-11-19T14:05:46","0"),message("3","1","2026-11-19T12:05:46","1"),
                message("4","1","unknown","0"))) {
            assertThat(RecordedCommunicationEvidence.supplement(wrong,"",ANSWER)).isEmpty();
            assertThat(RecordedCommunicationEvidence.fingerprint(wrong,ANSWER)).isEmpty();
        }
    }

    @Test void originalSnapshotIsNeverRewrittenAndDuplicateDiscoveryDoesNotAddFacts() {
        String original = message("57822","1","2026-11-19T12:05:46","0");
        assertThat(RecordedCommunicationEvidence.supplement(original,original,ANSWER)).isEmpty();
        assertThat(RecordedCommunicationEvidence.supplement(original.replace("оплатите","оплачено"),original,ANSWER)).isEmpty();
        assertThat(RecordedCommunicationEvidence.fingerprint(original,ANSWER))
                .isNotEqualTo(RecordedCommunicationEvidence.fingerprint(original + message("57823","1","2026-11-19T12:06:46","0"),ANSWER));
    }
}
