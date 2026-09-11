package com.hunt.otziv.u_users.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hunt.otziv.u_users.dto.ChangeKeycloakPasswordRequest;
import com.hunt.otziv.u_users.keycloak.client.KeycloakAdminClient;
import com.hunt.otziv.u_users.keycloak.client.KeycloakAdminClient.SessionView;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.AuthSessionStateRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.mobile_push.repository.MobilePushTokenRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class UserSessionSecurityMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("auth_session_protocol").withUsername("root").withPassword("root");
    JdbcTemplate jdbc;
    DataSourceTransactionManager tm;
    TransactionTemplate tx;
    AuthSessionStateRepository state;
    KeycloakSessionAuthority authority;
    UserSessionSecurityService service;
    User user;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds); tm = new DataSourceTransactionManager(ds); tx = new TransactionTemplate(tm);
        for (String table : List.of("auth_revocation_sessions", "auth_security_mutations", "auth_session_bindings",
                "users_roles", "roles", "users")) jdbc.execute("DROP TABLE IF EXISTS " + table);
        jdbc.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, active BOOLEAN, keycloak_id VARCHAR(255), auth_epoch BIGINT)");
        jdbc.execute("CREATE TABLE roles(id INT PRIMARY KEY, name VARCHAR(255))");
        jdbc.execute("CREATE TABLE users_roles(user_id BIGINT, role_id INT)");
        jdbc.update("INSERT INTO users VALUES (1,true,'subject',1)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_294__durable_auth_session_revocation.sql")).execute(ds);
        state = new AuthSessionStateRepository(jdbc); authority = mock(KeycloakSessionAuthority.class);
        service = new UserSessionSecurityService(state, authority, tm);
        ReflectionTestUtils.setField(service, "mode", "enforce");
        user = User.builder().id(1L).username("local-test").keycloakId("subject").active(true).authEpoch(1L).roles(Set.of()).build();
    }

    void activeEvidence() {
        when(authority.verify(any(), eq("subject"), anyString(), eq(Set.of())))
                .thenReturn(new KeycloakSessionAuthority.Evidence(true,"active",1000,2000,false));
    }

    @Test void refreshedOldSessionCannotBindToNewEpochButFreshLoginCan() {
        activeEvidence();
        assertThat(service.verify(user, token("old-sid")).allowed()).isTrue();
        jdbc.update("UPDATE users SET auth_epoch=2 WHERE id=1"); user.setAuthEpoch(2);
        assertThat(service.verify(user, token("old-sid")).reason()).isEqualTo("session_generation_revoked");
        assertThat(service.verify(user, token("fresh-sid")).allowed()).isTrue();
        assertThat(state.binding("subject","old-sid").orElseThrow().epoch()).isEqualTo(1);
    }

    @Test void pendingBarrierWinsRaceAgainstLiveIssuerLookup() throws Exception {
        CountDownLatch reading = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        when(authority.verify(any(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            reading.countDown(); assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();
            return new KeycloakSessionAuthority.Evidence(true,"active",1000,2000,false);
        });
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<UserSessionSecurityService.Decision> check = pool.submit(() -> service.verify(user, token("sid")));
            assertThat(reading.await(10,TimeUnit.SECONDS)).isTrue();
            tx.executeWithoutResult(status -> { state.lockSnapshot(1); state.enqueue(user,"TEST"); });
            release.countDown();
            assertThat(check.get(10,TimeUnit.SECONDS).reason()).isEqualTo("security_mutation_pending");
            assertThat(state.binding("subject","sid")).isEmpty();
        }
    }

    @Test void outageIsUnavailableAndShadowDoesNotWriteBindings() {
        when(authority.verify(any(),anyString(),anyString(),any())).thenThrow(new IllegalStateException("timeout"));
        var decision = service.verify(user, token("sid"));
        assertThat(decision.allowed()).isFalse(); assertThat(decision.unavailable()).isTrue();
        reset(authority); activeEvidence(); ReflectionTestUtils.setField(service,"mode","shadow");
        assertThat(service.verify(user,token("sid")).allowed()).isTrue();
        assertThat(state.binding("subject","sid")).isEmpty();
    }

    @Test void rollbackDoesNotPersistRevocationIntent() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            state.enqueue(user,"TEST"); throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(state.pending(1)).isFalse();
        assertThat(state.operationalSnapshot().pending()).isZero();
    }

    @Test void partialRemoteDeletionRetainsTargetsAndRetriesOnlySameSessions() {
        tx.executeWithoutResult(status -> state.enqueue(user,"TEST"));
        String operation = state.generation(1,1).orElseThrow().id();
        KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
        SessionView online = new SessionView("online-old","subject",2000,Map.of("uuid","mobile"));
        SessionView offline = new SessionView("offline-old","subject",2000,Map.of("uuid","mobile"));
        when(keycloak.userSessions("subject")).thenReturn(List.of(online),List.of());
        when(keycloak.offlineClientUuids("subject")).thenReturn(List.of("uuid"));
        when(keycloak.offlineSessions("subject","uuid")).thenReturn(List.of(offline),List.of());
        doThrow(new IllegalStateException("lost response")).doNothing().when(keycloak).deleteSession("offline-old",true);
        var revocations = new UserSessionRevocationService(state,keycloak,tm);
        assertThatThrownBy(() -> revocations.reconcile(operation)).isInstanceOf(IllegalStateException.class);
        assertThat(state.mutation(operation).orElseThrow().phase()).isEqualTo("DELETE_REQUIRED");
        assertThat(state.targets(operation)).hasSize(2);
        revocations.reconcile(operation);
        assertThat(state.pending(1)).isFalse();
        verify(keycloak,atLeastOnce()).deleteSession("online-old",false);
        verify(keycloak,times(2)).deleteSession("offline-old",true);
        verify(keycloak,never()).logoutUserSessions(anyString());
    }

    @Test void passwordTimeoutCommitsBarrierAndNeverAutomaticallyResendsSecret() {
        UserRepository users = mock(UserRepository.class);
        when(users.lockById(1L)).thenReturn(Optional.of(user));
        doAnswer(invocation -> { jdbc.update("UPDATE users SET auth_epoch=? WHERE id=1", user.getAuthEpoch()); return null; })
                .when(users).flush();
        KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
        doThrow(new IllegalStateException("timeout")).when(keycloak).resetPassword(anyString(),anyString(),anyBoolean());
        var epochs = new UserAuthEpochService(users,mock(MobilePushTokenRepository.class),state);
        var revocations = new UserSessionRevocationService(state,keycloak,tm);
        var passwords = new UserPasswordMutationService(users,mock(EntityManager.class),epochs,state,keycloak,revocations);
        var request = new ChangeKeycloakPasswordRequest(); request.setPassword("test-only-not-a-secret");
        assertThatThrownBy(() -> passwords.change(1,request, target -> {}))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var mutation = state.generation(1,2).orElseThrow();
        assertThat(mutation.phase()).isEqualTo("PASSWORD_UNKNOWN");
        assertThat(state.operationalSnapshot().unknown()).isEqualTo(1);
        assertThat(state.operationalSnapshot().pending()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT auth_epoch FROM users WHERE id=1",Long.class)).isEqualTo(2);
        assertThat(state.dispatchable(20)).isEmpty();
        revocations.reconcile(mutation.id());
        verify(keycloak,times(1)).resetPassword(anyString(),anyString(),anyBoolean());
        assertThat(state.pending(1)).isTrue();
    }

    Jwt token(String sid) {
        return Jwt.withTokenValue("synthetic-token").header("alg","RS256").subject("subject")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).claim("sid",sid).claim("azp","mobile").build();
    }
}
