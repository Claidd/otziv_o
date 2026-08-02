package com.hunt.otziv.b_bots.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import jakarta.persistence.LockModeType;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;

class BotMutationLockContractTest {

    @Test
    void staleBotCommandsSerializeOnTheMutatedAggregate() throws Exception {
        assertPessimisticWrite(
                ReviewRepository.class.getMethod("findByIdForBotChange", Long.class)
        );
        assertPessimisticWrite(
                BadReviewTaskRepository.class.getMethod("findByIdForMutation", Long.class)
        );
    }

    private void assertPessimisticWrite(Method method) {
        Lock lock = method.getAnnotation(Lock.class);
        assertThat(lock)
                .as(method.getDeclaringClass().getSimpleName() + "." + method.getName())
                .isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }
}
