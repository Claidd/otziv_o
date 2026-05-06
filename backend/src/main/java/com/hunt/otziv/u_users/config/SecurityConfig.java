package com.hunt.otziv.u_users.config;

import com.hunt.otziv.config.jwt.service.JwtAuthFilter;
import com.hunt.otziv.u_users.services.UserServiceImpl;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RequiredArgsConstructor
@EnableWebSecurity
@Configuration
@EnableMethodSecurity(securedEnabled = true, jsr250Enabled = true)
public class SecurityConfig {

    private final UserServiceImpl userService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final RequestValidationFilter requestValidationFilter;
    private final JwtAuthFilter jwtAuthFilter;

//    private final JwtRequestFilter jwtRequestFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        HttpSessionCsrfTokenRepository csrfTokenRepository = new HttpSessionCsrfTokenRepository();

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .ignoringRequestMatchers("/api/**", "/webhook", "/webhook/**", "/api/leads/**", "/health")
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/css/**", "/font/**", "/images/**", "/js/**", "/webjars/**", "/static/**").permitAll()
                        .requestMatchers("/auth", "/login", "/register").permitAll()
                        .requestMatchers("/kvesty", "/lasertag", "/nerf", "/api/index", "/favicon.ico", "/error").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/api/auth", "/api/auth/**").permitAll()
                        .requestMatchers("/api/me").authenticated()
                        .requestMatchers("/api/personal-reminders", "/api/personal-reminders/**").authenticated()
                        .requestMatchers("/api/cabinet/profile").authenticated()
                        .requestMatchers("/api/cabinet/user-info", "/api/cabinet/analyse").hasAnyRole("ADMIN", "OWNER")
                        .requestMatchers("/api/cabinet/team").hasAnyRole("ADMIN", "OWNER", "MANAGER")
                        .requestMatchers("/api/cabinet/score").hasAnyRole("ADMIN", "OWNER", "MANAGER", "WORKER", "OPERATOR", "MARKETOLOG")
                        .requestMatchers("/api/admin/categories", "/api/admin/categories/**", "/api/admin/subcategories", "/api/admin/subcategories/**").hasAnyRole("ADMIN", "OWNER", "MANAGER")
                        .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "OWNER")
                        .requestMatchers("/api/operator/**").hasAnyRole("ADMIN", "OWNER", "OPERATOR")
                        .requestMatchers("/api/companies/**").hasAnyRole("ADMIN", "OWNER", "MANAGER", "OPERATOR")
                        .requestMatchers(HttpMethod.GET, "/api/leads/board").hasAnyRole("ADMIN", "OWNER", "MANAGER", "MARKETOLOG")
                        .requestMatchers(HttpMethod.GET, "/api/leads/edit-options").hasAnyRole("ADMIN", "OWNER", "MANAGER", "MARKETOLOG")
                        .requestMatchers(HttpMethod.POST, "/api/leads").hasAnyRole("ADMIN", "OWNER", "MANAGER", "OPERATOR", "MARKETOLOG")
                        .requestMatchers(HttpMethod.PUT, "/api/leads/*").hasAnyRole("ADMIN", "OWNER", "MANAGER")
                        .requestMatchers(HttpMethod.DELETE, "/api/leads/*").hasAnyRole("ADMIN", "OWNER")
                        .requestMatchers(HttpMethod.POST, "/api/leads/*/status/**").hasAnyRole("ADMIN", "OWNER", "MANAGER", "MARKETOLOG")
                        .requestMatchers("/api/review-check/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/manager/orders/*/edit", "/api/manager/orders/*/details").hasAnyRole("ADMIN", "OWNER", "MANAGER", "WORKER")
                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/manager/orders/*",
                                "/api/manager/orders/*/note",
                                "/api/manager/orders/*/company-note",
                                "/api/manager/orders/*/reviews/*",
                                "/api/manager/orders/*/reviews/*/text",
                                "/api/manager/orders/*/reviews/*/answer",
                                "/api/manager/orders/*/reviews/*/note"
                        ).hasAnyRole("ADMIN", "OWNER", "MANAGER", "WORKER")
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/manager/orders/*/status",
                                "/api/manager/orders/*/reviews"
                        ).hasAnyRole("ADMIN", "OWNER", "MANAGER", "WORKER")
                        .requestMatchers(HttpMethod.POST, "/api/manager/orders/*/reviews/*/photo").hasAnyRole("ADMIN", "OWNER", "MANAGER", "WORKER")
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/manager/orders/*/reviews/*/change-bot",
                                "/api/manager/orders/*/reviews/*/bots/*/deactivate"
                        ).hasAnyRole("ADMIN", "OWNER", "MANAGER", "WORKER")
                        .requestMatchers(HttpMethod.DELETE, "/api/manager/orders/*/reviews/*").hasAnyRole("ADMIN", "OWNER", "MANAGER")
                        .requestMatchers("/api/manager/**").hasAnyRole("ADMIN", "OWNER", "MANAGER")
                        .requestMatchers("/api/worker/**").hasAnyRole("ADMIN", "OWNER", "MANAGER", "WORKER")
                        .requestMatchers("/api/bots/**").hasAnyRole("ADMIN", "OWNER", "MANAGER", "WORKER")
                        .requestMatchers("/api/review").hasAnyRole("ADMIN", "OWNER")
                        .requestMatchers("/admin/cities/**").hasAnyRole("ADMIN", "OWNER")
                        .requestMatchers("/.well-known/acme-challenge/**").permitAll()
                        .requestMatchers("/access-denied").permitAll()
                        .requestMatchers("/lead/new_lead").hasAnyRole("ADMIN", "OWNER", "OPERATOR", "MARKETOLOG")
                        .requestMatchers("/").permitAll()
                        .requestMatchers("/send-message", "/sendEmail").hasAnyRole("ADMIN", "OWNER")
                        .requestMatchers("/webhook", "/webhook/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/leads/import").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/leads/sync").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/leads/update").permitAll()
                        .requestMatchers("/api/leads/modified").permitAll()
                        .requestMatchers("/api/dispatch-settings/cron").permitAll()
                        .requestMatchers("/admin/dispatch/**").hasAnyRole("ADMIN", "OWNER")
                        .requestMatchers("/admin/**").authenticated()
                        .requestMatchers("/allUsers/**").hasAnyRole("ADMIN", "OWNER")
                        .requestMatchers("/logs", "/logs/**").hasAnyRole("ADMIN", "OWNER")
                        .requestMatchers("/lead/**").hasAnyRole("ADMIN", "OWNER", "MANAGER", "MARKETOLOG")
                        .requestMatchers("/bots/**").hasAnyRole("ADMIN", "OWNER", "WORKER")
                        .requestMatchers("/categories/**").hasAnyRole("ADMIN", "OWNER", "MANAGER", "OPERATOR")
                        .requestMatchers("/subcategories/**").hasAnyRole("ADMIN", "OWNER", "MANAGER", "OPERATOR")
                        .requestMatchers("/operator/**", "/operators", "/operators/**").hasAnyRole("ADMIN", "OWNER", "OPERATOR", "MARKETOLOG")
                        .requestMatchers("/whatsapp", "/whatsapp/**").hasAnyRole("ADMIN", "OWNER", "OPERATOR", "MARKETOLOG")
                        .requestMatchers("/telephone/**").hasAnyRole("ADMIN", "OWNER", "OPERATOR", "MARKETOLOG")
                        .requestMatchers("/companies/**").hasAnyRole("ADMIN", "OWNER", "MANAGER", "OPERATOR")
                        .requestMatchers("/products", "/products/**").hasAnyRole("ADMIN", "OWNER", "MANAGER")
                        .requestMatchers("/ordersCompany/**", "/ordersDetails/**", "/filial/**").hasAnyRole("ADMIN", "OWNER", "MANAGER", "WORKER")
                        .requestMatchers("/review", "/review/editReview/**", "/review/addReviews/**", "/review/deleteReviews/**").hasAnyRole("ADMIN", "OWNER", "MANAGER", "WORKER")
                        .requestMatchers("/reviews/**").hasAnyRole("ADMIN", "OWNER", "MANAGER", "WORKER", "OPERATOR")
                        .requestMatchers("/review/editReviews/**", "/review/editReviewses/**").permitAll()
                        .requestMatchers("/zp/**", "/payment_check/**", "/orders/**").hasAnyRole("ADMIN", "OWNER", "MANAGER")
                        .requestMatchers("/worker/**").hasAnyRole("ADMIN", "OWNER", "WORKER", "MANAGER")
                        .requestMatchers("/cities/**").hasAnyRole("ADMIN", "OWNER", "MANAGER")
                        .requestMatchers("/phone", "/phone/**").hasAnyRole("ADMIN", "OWNER")
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/process-login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/login?error")
                )
                .logout(logout -> logout
                        .logoutUrl("/custom-logout")
                        .logoutSuccessUrl("/login")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                )
//                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            if (request.getRequestURI().startsWith("/api/")) {
                                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                                response.setContentType("application/json;charset=UTF-8");
                                response.getWriter().write("{\"message\":\"Сессия закончилась. Войдите в систему заново.\"}");
                                return;
                            }

                            response.sendRedirect(request.getContextPath() + "/login");
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            if (request.getRequestURI().startsWith("/api/")) {
                                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                                response.setContentType("application/json;charset=UTF-8");
                                response.getWriter().write("{\"message\":\"У вас нет доступа к этому действию.\"}");
                                return;
                            }

                            response.sendRedirect(request.getContextPath() + "/access-denied");
                        })
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakJwtAuthenticationConverter()))
                )

                .cors(AbstractHttpConfigurer::disable);

        // 🧩 фильтр — только ПОСЛЕ всей основной конфигурации
        http.addFilterBefore(requestValidationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }



//    3
    @Bean
    public DaoAuthenticationProvider daoAuthenticationProvider(){
        DaoAuthenticationProvider daoAuthenticationProvider = new DaoAuthenticationProvider(userService);
        daoAuthenticationProvider.setPasswordEncoder(passwordEncoder);
        return daoAuthenticationProvider;
    }


    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    private Converter<Jwt, AbstractAuthenticationToken> keycloakJwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();

        return jwt -> {
            Set<GrantedAuthority> authorities = new LinkedHashSet<>();

            extractKeycloakRoles(jwt).stream()
                    .map(this::toRoleAuthority)
                    .forEach(authorities::add);

            Collection<GrantedAuthority> scopeAuthorities = scopeConverter.convert(jwt);
            if (scopeAuthorities != null) {
                authorities.addAll(scopeAuthorities);
            }

            String principalName = Optional
                    .ofNullable(jwt.getClaimAsString("preferred_username"))
                    .orElse(jwt.getSubject());

            return new JwtAuthenticationToken(jwt, authorities, principalName);
        };
    }

    private Set<String> extractKeycloakRoles(Jwt jwt) {
        Set<String> roles = new LinkedHashSet<>();

        Collection<String> flatRoles = jwt.getClaimAsStringList("roles");
        if (flatRoles != null) {
            roles.addAll(flatRoles);
        }

        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null) {
            addRolesFromObject(roles, realmAccess.get("roles"));
        }

        Map<String, Object> resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess != null) {
            resourceAccess.values().stream()
                    .filter(Map.class::isInstance)
                    .map(clientAccess -> ((Map<?, ?>) clientAccess).get("roles"))
                    .forEach(clientRoles -> addRolesFromObject(roles, clientRoles));
        }

        return roles;
    }

    private void addRolesFromObject(Set<String> roles, Object rolesObject) {
        if (rolesObject instanceof Collection<?> values) {
            values.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .forEach(roles::add);
        }
    }

    private GrantedAuthority toRoleAuthority(String role) {
        String authority = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return new SimpleGrantedAuthority(authority);
    }

}
