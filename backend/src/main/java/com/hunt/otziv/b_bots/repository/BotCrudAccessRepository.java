package com.hunt.otziv.b_bots.repository;

import com.hunt.otziv.b_bots.model.Bot;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Fresh, password-free authorization projections for bot CRUD.
 *
 * <p>CRUD ownership is deliberately narrower than browser access: a worker may
 * manage only a bot that is currently owned by that worker. Temporary review,
 * bad-review and recovery-task relationships grant browser access only.</p>
 */
public interface BotCrudAccessRepository extends Repository<Bot, Long> {

    interface ActiveCrudPrincipalRow {
        Long getUserId();

        String getRoleName();
    }

    interface CrudBotRow {
        Long getBotId();

        Long getWorkerId();
    }

    @Query(value = """
            SELECT access_user.id AS userId,
                   access_role.name AS roleName
            FROM users access_user
            JOIN users_roles access_user_role
              ON access_user_role.user_id = access_user.id
            JOIN roles access_role
              ON access_role.id = access_user_role.role_id
            WHERE access_user.username = :username
              AND access_user.active = 1
              AND access_role.name IN (:roles)
            ORDER BY CASE
                         WHEN access_role.name IN ('ROLE_ADMIN', 'ROLE_OWNER') THEN 0
                         ELSE 1
                     END,
                     access_role.name
            LIMIT 1
            FOR UPDATE
            """, nativeQuery = true)
    Optional<ActiveCrudPrincipalRow> findActiveCrudPrincipalForUpdate(
            @Param("username") String username,
            @Param("roles") Collection<String> roles
    );

    @Query(value = """
            SELECT bot.bot_id AS botId,
                   bot.bot_worker AS workerId
            FROM bots bot
            WHERE bot.bot_id = :botId
              AND EXISTS (
                    SELECT 1
                    FROM users access_user
                    JOIN users_roles access_user_role
                      ON access_user_role.user_id = access_user.id
                    JOIN roles access_role
                      ON access_role.id = access_user_role.role_id
                    WHERE access_user.username = :username
                      AND access_user.active = 1
                      AND access_role.name IN ('ROLE_ADMIN', 'ROLE_OWNER')
              )
            """, nativeQuery = true)
    Optional<CrudBotRow> findGloballyManageableBot(
            @Param("botId") long botId,
            @Param("username") String username
    );

    @Query(value = """
            SELECT bot.bot_id AS botId,
                   bot.bot_worker AS workerId
            FROM bots bot
            JOIN workers bot_owner
              ON bot_owner.worker_id = bot.bot_worker
            JOIN users owner_user
              ON owner_user.id = bot_owner.user_id
            JOIN users_roles owner_user_role
              ON owner_user_role.user_id = owner_user.id
            JOIN roles owner_role
              ON owner_role.id = owner_user_role.role_id
            WHERE bot.bot_id = :botId
              AND owner_user.username = :username
              AND owner_user.active = 1
              AND owner_role.name = 'ROLE_WORKER'
            """, nativeQuery = true)
    Optional<CrudBotRow> findWorkerOwnedBot(
            @Param("botId") long botId,
            @Param("username") String username
    );
}
