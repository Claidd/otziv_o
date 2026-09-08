package com.hunt.otziv.u_users.service;

import static org.assertj.core.api.Assertions.*;
import com.hunt.otziv.u_users.keycloak.client.KeycloakAdminClient;
import com.hunt.otziv.u_users.keycloak.config.KeycloakAdminProperties;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.AuthSessionStateRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Opt-in local-only issuer/backend protocol proof; owns and removes only its random user/client. */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "OTZIV_AUTH_KC_RUNTIME", matches = "local-only")
class KeycloakSessionProtocolRuntimeIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("auth_issuer_runtime").withUsername("root").withPassword("root");
    static final String BASE = "http://keycloak:8080/keycloak";
    static final ParameterizedTypeReference<Map<String,Object>> MAP = new ParameterizedTypeReference<>() {};
    RestClient http = RestClient.create();

    @Test void pinnedIssuerOnlineOfflineExternalResetAndDurableRevocation() throws Exception {
        Map<String,String> env = localEnvironment();
        String realm = env.getOrDefault("KEYCLOAK_ADMIN_REALM","otziv");
        String masterToken = form(BASE + "/realms/master/protocol/openid-connect/token", Map.of(
                "grant_type","password","client_id","admin-cli", "username",env.get("KEYCLOAK_ADMIN"),
                "password",env.get("KEYCLOAK_ADMIN_PASSWORD"))).get("access_token").toString();
        String suffix = UUID.randomUUID().toString();
        String clientId = "otziv-session-proof-" + suffix;
        String username = "session-proof-" + suffix;
        String password = "LocalProof-" + UUID.randomUUID() + "!aA9";
        String userId = null; String clientUuid = null;
        String adminBase = BASE + "/admin/realms/" + realm;
        try {
            clientUuid = create(adminBase + "/clients", masterToken, Map.of(
                    "clientId",clientId,"enabled",true,"publicClient",true,"directAccessGrantsEnabled",true,
                    "standardFlowEnabled",false,"fullScopeAllowed",true,
                    "defaultClientScopes",List.of("basic","profile","email","roles"),"optionalClientScopes",List.of("offline_access")));
            userId = create(adminBase + "/users", masterToken, Map.of(
                    "username",username,"enabled",true,"emailVerified",true,"email",username+"@example.invalid",
                    "firstName","Local","lastName","ProtocolProof","credentials",List.of(Map.of(
                            "type","password","value",password,"temporary",false))));
            var properties = new KeycloakAdminProperties();
            properties.setServerUrl(BASE); properties.setRealm(realm);
            properties.setClientId(env.getOrDefault("KEYCLOAK_ADMIN_CLIENT_ID","otziv-backend"));
            properties.setClientSecret(env.get("KEYCLOAK_ADMIN_CLIENT_SECRET"));
            var keycloak = new KeycloakAdminClient(properties);
            var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,active BOOLEAN,keycloak_id VARCHAR(255),auth_epoch BIGINT)");
            jdbc.execute("CREATE TABLE roles(id INT PRIMARY KEY,name VARCHAR(255))");
            jdbc.execute("CREATE TABLE users_roles(user_id BIGINT,role_id INT)");
            jdbc.update("INSERT INTO users VALUES (1,true,?,1)",userId);
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_294__durable_auth_session_revocation.sql")).execute(ds);
            var tm = new DataSourceTransactionManager(ds); var tx = new TransactionTemplate(tm);
            var state = new AuthSessionStateRepository(jdbc);
            var security = new UserSessionSecurityService(state,new KeycloakSessionAuthority(keycloak),tm);
            ReflectionTestUtils.setField(security,"mode","enforce");
            var local = User.builder().id(1L).username(username).keycloakId(userId).authEpoch(1).active(true).roles(Set.of()).build();
            String tokenUrl = BASE + "/realms/" + realm + "/protocol/openid-connect/token";
            // Deliberately cross the issuer's one-second session timestamp resolution.
            Thread.sleep(1100);
            Map<String,Object> onlineLogin = form(tokenUrl,Map.of("grant_type","password","client_id",clientId,
                    "username",username,"password",password,"scope","openid"));
            Jwt onlineToken = jwt(onlineLogin.get("access_token").toString());
            assertThat(keycloak.userSessions(userId)).anyMatch(item -> onlineToken.getClaimAsString("sid").equals(item.id()));
            assertThat(security.verify(local,onlineToken).reason()).isEqualTo("accepted");
            keycloak.deleteSession(onlineToken.getClaimAsString("sid"),false);
            assertThat(security.verify(local,onlineToken).allowed()).isFalse();
            Map<String,Object> login = login(tokenUrl,clientId,username,password);
            Jwt first = jwt(login.get("access_token").toString());
            assertThat(keycloak.introspectAccessToken(first.getTokenValue()).get("active")).isEqualTo(true);
            assertThat(keycloak.offlineClientUuid(userId,clientId)).contains(clientUuid);
            assertThat(keycloak.offlineSessions(userId,clientUuid)).isNotEmpty();
            assertThat(keycloak.credentials(userId)).anyMatch(item -> "password".equals(item.type()));
            assertThat(keycloak.currentRealmRoleNames(userId)).isNotNull();
            long begin = System.nanoTime();
            var initial = security.verify(local,first);
            assertThat(initial.reason()).isEqualTo("accepted");
            System.out.println("AUTH_RUNTIME live_authority_ms=" + (System.nanoTime()-begin)/1_000_000);
            String sid = first.getClaimAsString("sid");
            // Remove only the online copy. The offline token must remain usable and be checked as offline.
            keycloak.deleteSession(sid,false);
            Map<String,Object> offlineRefresh = form(tokenUrl,Map.of("grant_type","refresh_token","client_id",clientId,
                    "refresh_token",login.get("refresh_token").toString()));
            Jwt offline = jwt(offlineRefresh.get("access_token").toString());
            assertThat(security.verify(local,offline).reason()).isEqualTo("accepted");
            assertThat(keycloak.offlineSessions(userId,clientUuid)).anyMatch(item -> sid.equals(item.id()));
            // External admin reset intentionally leaves local epoch untouched.
            String nextPassword = "NextProof-" + UUID.randomUUID() + "!bB7";
            keycloak.resetPassword(userId,nextPassword,false);
            assertThat(security.verify(local,offline).allowed()).isFalse();
            var refreshedOld = form(tokenUrl,Map.of("grant_type","refresh_token","client_id",clientId,
                    "refresh_token",offlineRefresh.get("refresh_token").toString()));
            assertThat(security.verify(local,jwt(refreshedOld.get("access_token").toString())).allowed()).isFalse();
            Thread.sleep(1100);
            Map<String,Object> freshLogin = login(tokenUrl,clientId,username,nextPassword);
            Jwt fresh = jwt(freshLogin.get("access_token").toString());
            assertThat(security.verify(local,fresh).reason()).isEqualTo("accepted");
            // Durable local generation mutation and actual exact online/offline session deletion.
            local.setAuthEpoch(2);
            tx.executeWithoutResult(status -> {
                state.lockSnapshot(1); jdbc.update("UPDATE users SET auth_epoch=2 WHERE id=1");
                state.enqueue(local,"RUNTIME_PROOF");
            });
            assertThat(security.verify(local,fresh).allowed()).isFalse();
            new UserSessionRevocationService(state,keycloak,tm).reconcile(state.generation(1,2).orElseThrow().id());
            assertThat(state.pending(1)).isFalse();
            assertThat(security.verify(local,fresh).allowed()).isFalse();
            assertThat(keycloak.offlineSessions(userId,clientUuid)).isEmpty();
            Map<String,Object> finalLogin = login(tokenUrl,clientId,username,nextPassword);
            Jwt finalToken = jwt(finalLogin.get("access_token").toString());
            assertThat(security.verify(local,finalToken).reason()).isEqualTo("accepted");
            // Exercise the actual password coordinator, including committed reserve and finalization.
            // This isolated fixture uses a JDBC-backed repository adapter for its minimal users table.
            var users = org.mockito.Mockito.mock(com.hunt.otziv.u_users.repository.UserRepository.class);
            org.mockito.Mockito.when(users.lockById(1L)).thenReturn(Optional.of(local));
            org.mockito.Mockito.doAnswer(invocation -> {
                jdbc.update("UPDATE users SET auth_epoch=? WHERE id=1",local.getAuthEpoch()); return null;
            }).when(users).flush();
            var epochs = new UserAuthEpochService(users,
                    org.mockito.Mockito.mock(com.hunt.otziv.mobile_push.repository.MobilePushTokenRepository.class),state);
            var passwordWorkflow = new UserPasswordMutationService(users,
                    org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class),epochs,state,keycloak,
                    new UserSessionRevocationService(state,keycloak,tm));
            var passwordRequest = new com.hunt.otziv.u_users.dto.ChangeKeycloakPasswordRequest();
            String finalPassword = "FinalProof-"+UUID.randomUUID()+"!cC8";
            passwordRequest.setPassword(finalPassword);
            passwordWorkflow.change(1,passwordRequest, target -> {});
            assertThat(state.pending(1)).isFalse();
            assertThat(security.verify(local,finalToken).allowed()).isFalse();
            Thread.sleep(1100);
            Jwt afterPassword = jwt(login(tokenUrl,clientId,username,finalPassword).get("access_token").toString());
            assertThat(security.verify(local,afterPassword).reason()).isEqualTo("accepted");
            keycloak.deleteSession(afterPassword.getClaimAsString("sid"),false);
            keycloak.deleteSession(afterPassword.getClaimAsString("sid"),true);
            assertThat(security.verify(local,afterPassword).allowed()).isFalse();
            System.out.println("AUTH_RUNTIME online_offline_external_reset_exact_revocation_fresh_login=PASS");
        } finally {
            if (userId != null) delete(adminBase + "/users/" + userId,masterToken);
            if (clientUuid != null) delete(adminBase + "/clients/" + clientUuid,masterToken);
        }
    }

    Map<String,Object> login(String url,String client,String user,String password) {
        return form(url,Map.of("grant_type","password","client_id",client,"username",user,
                "password",password,"scope","openid offline_access"));
    }
    Map<String,Object> form(String url,Map<String,String> values) {
        var body = new LinkedMultiValueMap<String,String>(); values.forEach(body::add);
        return http.post().uri(url).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(body).retrieve().body(MAP);
    }
    String create(String url,String token,Object body) {
        var location = http.post().uri(url).headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity().getHeaders().getLocation();
        assertThat(location).isNotNull(); String path=location.getPath(); return path.substring(path.lastIndexOf('/')+1);
    }
    void delete(String url,String token) {
        http.delete().uri(url).headers(headers -> headers.setBearerAuth(token)).retrieve().toBodilessEntity();
    }
    Jwt jwt(String token) throws Exception {
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]),StandardCharsets.UTF_8);
        Map<String,Object> claims = tools.jackson.databind.json.JsonMapper.builder().build()
                .readValue(payload,new tools.jackson.core.type.TypeReference<Map<String,Object>>() {});
        return Jwt.withTokenValue(token).header("alg","RS256").claims(map -> map.putAll(claims))
                .issuedAt(Instant.ofEpochSecond(((Number)claims.get("iat")).longValue()))
                .expiresAt(Instant.ofEpochSecond(((Number)claims.get("exp")).longValue())).build();
    }
    Map<String,String> localEnvironment() throws Exception {
        String path=System.getenv("OTZIV_AUTH_KC_ENV_FILE");
        if (path==null) throw new IllegalStateException("Local runtime environment mount required");
        Set<String> accepted=Set.of("KEYCLOAK_ADMIN","KEYCLOAK_ADMIN_PASSWORD","KEYCLOAK_ADMIN_REALM",
                "KEYCLOAK_ADMIN_CLIENT_ID","KEYCLOAK_ADMIN_CLIENT_SECRET");
        Map<String,String> env=new HashMap<>();
        for(String line:Files.readAllLines(Path.of(path))) {
            int index=line.indexOf('='); if(index<=0) continue;
            String key=line.substring(0,index).trim(); if(!accepted.contains(key)) continue;
            String value=line.substring(index+1).trim();
            if(value.length()>=2 && ((value.startsWith("\"")&&value.endsWith("\""))
                    ||(value.startsWith("'")&&value.endsWith("'")))) value=value.substring(1,value.length()-1);
            env.put(key,value);
        }
        return env;
    }
}
