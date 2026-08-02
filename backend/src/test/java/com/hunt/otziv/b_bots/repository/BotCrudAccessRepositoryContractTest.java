package com.hunt.otziv.b_bots.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class BotCrudAccessRepositoryContractTest {

    @Test
    void createCrudRequiresCurrentActiveRoleMatchingTheSession() throws Exception {
        Method method = BotCrudAccessRepository.class.getDeclaredMethod(
                "findActiveCrudPrincipalForUpdate",
                String.class,
                Collection.class
        );
        String sql = normalized(method.getAnnotation(Query.class).value());

        assertThat(sql).contains(
                "access_user.username = :username",
                "access_user.active = 1",
                "access_role.name in (:roles)",
                "access_user.id as userid",
                "access_role.name as rolename",
                "role_admin",
                "role_owner",
                "limit 1",
                "for update"
        );
        assertPasswordFree(method);
    }

    @Test
    void projectionsNeverReadBotSecrets() throws Exception {
        assertPasswordFree(BotCrudAccessRepository.class.getDeclaredMethod(
                "findGloballyManageableBot",
                long.class,
                String.class
        ));
        assertPasswordFree(BotCrudAccessRepository.class.getDeclaredMethod(
                "findWorkerOwnedBot",
                long.class,
                String.class
        ));
    }

    @Test
    void workerCrudRequiresCurrentActiveRoleAndDirectOwnership() throws Exception {
        Method method = BotCrudAccessRepository.class.getDeclaredMethod(
                "findWorkerOwnedBot",
                long.class,
                String.class
        );
        String sql = normalized(method.getAnnotation(Query.class).value());

        assertThat(sql).contains(
                "bot.bot_id = :botid",
                "bot_owner.worker_id = bot.bot_worker",
                "owner_user.id = bot_owner.user_id",
                "owner_user.username = :username",
                "owner_user_role.user_id = owner_user.id",
                "owner_user.active = 1",
                "owner_role.name = 'role_worker'"
        );
        assertThat(sql).doesNotContain(
                "reviews",
                "bad_review_tasks",
                "review_recovery_tasks"
        );
    }

    @Test
    void globalCrudIsLimitedToCurrentAdminAndOwnerRoles() throws Exception {
        Method method = BotCrudAccessRepository.class.getDeclaredMethod(
                "findGloballyManageableBot",
                long.class,
                String.class
        );
        String sql = normalized(method.getAnnotation(Query.class).value());

        assertThat(sql).contains(
                "access_user.username = :username",
                "access_user.active = 1",
                "access_role.name in ('role_admin', 'role_owner')"
        ).doesNotContain("role_manager", "role_worker");
    }

    private void assertPasswordFree(Method method) {
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
