package com.hunt.otziv.b_bots.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class BotBrowserAccessRepositoryContractTest {

    @Test
    void safeProjectionsNeverSelectTheBotPassword() throws Exception {
        assertPasswordFreeQuery(BotBrowserAccessRepository.class.getDeclaredMethod(
                "findGloballyAccessibleBrowserBot",
                long.class,
                String.class
        ));
        assertPasswordFreeQuery(BotBrowserAccessRepository.class.getDeclaredMethod(
                "findWorkerAccessibleBrowserBot",
                long.class,
                String.class
        ));
    }

    @Test
    void workerAccessCoversOnlyCurrentActionableRelationships() throws Exception {
        Method method = BotBrowserAccessRepository.class.getDeclaredMethod(
                "findWorkerAccessibleBrowserBot",
                long.class,
                String.class
        );
        Query query = method.getAnnotation(Query.class);
        String sql = normalized(query.value());

        assertThat(query.nativeQuery()).isTrue();
        assertThat(sql).contains(
                "access_user.username = :username",
                "access_user.active = 1",
                "access_role.name = 'role_worker'",
                "bot_owner.worker_id = bot.bot_worker",
                "owner_user.username = :username",
                "review.review_bot = bot.bot_id",
                "review_user.username = :username",
                "coalesce(review.review_publish, 0) = 0",
                "coalesce(review_order.order_complete, 0) = 0",
                "bad_task.bad_review_task_bot = bot.bot_id",
                "bad_task.bad_review_task_status = 'new'",
                "bad_order.order_worker = bad_task.bad_review_task_worker",
                "recovery_task.review_recovery_task_bot = bot.bot_id",
                "recovery_task.review_recovery_task_status = 'planned'",
                "recovery_batch.review_recovery_batch_status = 'open'",
                "recovery_order.order_id is null or recovery_order.order_worker = recovery_task.review_recovery_task_worker"
        );
    }

    @Test
    void globalAccessAlsoRequiresCurrentActiveLocalManagementRole() throws Exception {
        Method method = BotBrowserAccessRepository.class.getDeclaredMethod(
                "findGloballyAccessibleBrowserBot",
                long.class,
                String.class
        );
        String sql = normalized(method.getAnnotation(Query.class).value());

        assertThat(sql).contains(
                "access_user.username = :username",
                "access_user.active = 1",
                "access_role.name in ('role_admin', 'role_owner', 'role_manager')"
        );
    }

    private void assertPasswordFreeQuery(Method method) {
        Query query = method.getAnnotation(Query.class);
        assertThat(query).isNotNull();
        assertThat(query.nativeQuery()).isTrue();
        assertThat(normalized(query.value())).doesNotContain("bot_password", "password");
    }

    private String normalized(String sql) {
        return sql.replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
