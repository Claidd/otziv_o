package com.hunt.otziv.u_users.config;

import com.hunt.otziv.config.jwt.service.JwtAuthFilter;
import com.hunt.otziv.u_users.services.UserServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

@RequiredArgsConstructor
@EnableWebSecurity
@Configuration
@EnableMethodSecurity(securedEnabled = true, jsr250Enabled = true)
@EnableGlobalMethodSecurity(prePostEnabled = true)
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
                        .requestMatchers("/auth", "/login", "/register").permitAll()
                        .requestMatchers("/api/auth").permitAll()
                        .requestMatchers("/api/review").hasAnyRole("ADMIN", "OWNER")
                        .requestMatchers("/.well-known/acme-challenge/**").permitAll()
                        .requestMatchers("/access-denied").permitAll()
                        .requestMatchers("/lead/new_lead").hasAnyRole("ADMIN", "OWNER", "OPERATOR", "MARKETOLOG")
                        .requestMatchers("/").permitAll()
                        .requestMatchers("/phpmyadmin").permitAll()
                        .requestMatchers("/send-message", "/sendEmail").permitAll()
                        .requestMatchers("/webhook", "/webhook/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/leads/update").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/leads/sync").permitAll()
                        .requestMatchers("/api/leads/modified").permitAll()
                        .requestMatchers("/api/dispatch-settings/cron").permitAll()
                        .requestMatchers("/admin/**").authenticated()
                        .requestMatchers("/admin/dispatch/start").permitAll()
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
                        .accessDeniedPage("/access-denied")
                )

                .cors(AbstractHttpConfigurer::disable);

        // 🧩 фильтр — только ПОСЛЕ всей основной конфигурации
        http.addFilterBefore(requestValidationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }



//    настройка доступа к внутренним файлам
    @Bean
    public WebSecurityCustomizer webSecurityCustomizer(){
        return (web -> web.ignoring().requestMatchers("/css/**","/font/**","/images/**", "/js/**", "/webjars/**", "/static/**"));
    }
//    3
    @Bean
    public DaoAuthenticationProvider daoAuthenticationProvider(){
        DaoAuthenticationProvider daoAuthenticationProvider = new DaoAuthenticationProvider();
        daoAuthenticationProvider.setPasswordEncoder(passwordEncoder);
        daoAuthenticationProvider.setUserDetailsService(userService);
        return daoAuthenticationProvider;
    }


    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }


//                .csrf((csrf) -> csrf
//            .csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler())
//            .ignoringRequestMatchers("/api/**") // отключаем CSRF только для API
//                                .ignoringRequestMatchers("/webhook/**") // 💥 разрешить без CSRF
//                        .sessionAuthenticationStrategy(new CsrfAuthenticationStrategy(csrfTokenRepository))

//                                .requireCsrfProtectionMatcher()
//                        .ignoringRequestMatchers("/**")
//            );

//    @Bean
//    public PasswordEncoder passwordEncoder() {
//        return NoOpPasswordEncoder.getInstance();
//    }


//                .apply(jwtAuthenticationConfigurer);
    //добавляем передачу токена в контекст перед указанным фильтром
//                .addFilterBefore(jwtRequestFilter, UsernamePasswordAuthenticationFilter.class);

    //обработчик ошибки ТОЛЬКО для режима отладки
//                .exceptionHandling(exceptionHandling -> exceptionHandling
//                        .accessDeniedHandler(((request, response, accessDeniedException) ->
//                                accessDeniedException.printStackTrace())));

















//
//    @Bean
//    public AuthenticationProvider authenticationProvider(){
//        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
//        authProvider.setUserDetailsService(userDetailsService());
//        authProvider.setPasswordEncoder(passwordEncoder());
//        return authProvider;
//    }
//
//    @Bean
//    public UserDetailsService userDetailsService() throws UsernameNotFoundException {
//        return username -> userRepo.findByUsername(username)
//                .map(user -> new User(
//                        user.getUsername(),
//                        user.getPassword(),
//                        /*Здесь должна передавать роль или вариант ниже*/
//                        Collections.singletonList(new SimpleGrantedAuthority("USER"))
//                )).orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));
//    }


}
