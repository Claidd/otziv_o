package com.hunt.otziv.notification_media.service;

import static com.hunt.otziv.notification_media.service.NotificationMediaEventCatalog.*;
import static org.assertj.core.api.Assertions.assertThat;
import com.hunt.otziv.notification_media.api.StaffMediaSignal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextualMediaPolicyTest {
    private StaffMediaSignal signal(String action, String type, String section) {
        return new StaffMediaSignal(10, action, type, 20L, 20L, section);
    }
    @Test void neverCongratulatesUncommittedOrUnfinishedWork() {
        assertThat(ContextualMediaPolicy.resolve(signal("REVIEW_PUBLISH","review","publish"),Map.of("published",false),3)).isNull();
        assertThat(ContextualMediaPolicy.resolve(signal("REVIEW_NAGUL","review","nagul"),Map.of("walked",false),0)).isNull();
        assertThat(ContextualMediaPolicy.resolve(signal("BAD_TASK_COMPLETE","bad_review_task","bad"),Map.of("status","NEW"),0)).isNull();
        assertThat(ContextualMediaPolicy.resolve(signal("REVIEW_PUBLISH","review","publish"),Map.of(),3)).isNull();
    }
    @Test void thirdPublicationHasItsOwnExactMilestone() {
        var signal=signal("REVIEW_PUBLISH","review","publish");
        assertThat(ContextualMediaPolicy.resolve(signal,Map.of("published",true),2)).isEqualTo(WORKER_PUBLICATION_DONE);
        assertThat(ContextualMediaPolicy.resolve(signal,Map.of("published",true),3)).isEqualTo(WORKER_THREE_PUBLICATIONS);
        assertThat(ContextualMediaPolicy.resolve(signal,Map.of("published",true),4)).isEqualTo(WORKER_PUBLICATION_DONE);
    }
    @Test void freshAccountTipsRequirePendingWalkAndLowCounter() {
        var signal=signal("BOARD","review","nagul");
        assertThat(ContextualMediaPolicy.resolve(signal,Map.of("walked",false,"published",false,"account_count",1),0)).isEqualTo(WORKER_FRESH_ACCOUNT_GUIDE);
        assertThat(ContextualMediaPolicy.resolve(signal,Map.of("walked",false,"published",false,"account_count",2),0)).isEqualTo(WORKER_NAGUL_GUIDE);
        assertThat(ContextualMediaPolicy.resolve(signal,Map.of("walked",true,"published",false,"account_count",0),0)).isNull();
        assertThat(ContextualMediaPolicy.resolve(signal,Map.of("walked",false,"published",true,"account_count",0),0)).isNull();
    }
    @Test void recoveryAndRatingNeverLeakIntoGenericPublication() {
        assertThat(ContextualMediaPolicy.resolve(signal("BOARD","recovery_task","recovery"),Map.of("status","PLANNED"),0)).isEqualTo(WORKER_RECOVERY_GUIDE);
        assertThat(ContextualMediaPolicy.resolve(signal("BOARD","recovery_task","publish"),Map.of("status","PLANNED"),0)).isNull();
        assertThat(ContextualMediaPolicy.resolve(signal("BOARD","bad_review_task","bad"),Map.of("status","DONE"),0)).isNull();
        assertThat(ContextualMediaPolicy.resolve(signal("RECOVERY_TASK_UPDATE","recovery_task","recovery"),Map.of("status","DONE"),0)).isNull();
    }
    @Test void placeholderIsNotACompletedText() {
        for(String text: new String[]{"", "Текст отзыва", "Нужно подставить новый текст"}) {
            assertThat(ContextualMediaPolicy.resolve(signal("REVIEW_TEXT_UPDATE","review","review_text"),Map.of("text",text),0)).isNull();
        }
        assertThat(ContextualMediaPolicy.resolve(signal("REVIEW_TEXT_UPDATE","review","review_text"),Map.of("text","Мой текст"),0)).isEqualTo(WORKER_TEXT_SAVED);
    }
    @Test void cancelledOrCompletedTasksDoNotGetReminders() {
        assertThat(ContextualMediaPolicy.resolve(signal("BOARD","order","new"),Map.of("status","Оплата"),0)).isNull();
        assertThat(ContextualMediaPolicy.resolve(signal("BOARD","order","correct"),Map.of("status","Коррекция"),0)).isEqualTo(WORKER_TEXT_GUIDE);
        assertThat(ContextualMediaPolicy.resolve(signal("REVIEW_BOT_CHANGE","review",null),Map.of("published",true),0)).isNull();
    }
}
