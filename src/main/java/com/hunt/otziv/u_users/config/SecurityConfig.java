package com.hunt.otziv.u_users.config;

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

//    private final JwtRequestFilter jwtRequestFilter;



    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception{
        HttpSessionCsrfTokenRepository csrfTokenRepository = new HttpSessionCsrfTokenRepository();

        http
                .authorizeHttpRequests((authorizeRequests) ->
                                authorizeRequests
                                        //    настройка доступа
                                        .requestMatchers("/auth","/login","/register").permitAll()
                                        .requestMatchers("/api/auth").permitAll()
                                        .requestMatchers("/api/review").hasAnyRole("ADMIN", "OWNER")
                                        .requestMatchers("/.well-known/acme-challenge/**").permitAll()
                                        .requestMatchers("/access-denied").permitAll()
                                        .requestMatchers("/lead/new_lead").hasAnyRole("ADMIN", "OWNER", "OPERATOR","MARKETOLOG")
                                        .requestMatchers("/").permitAll()
                                        .requestMatchers("/phpmyadmin").permitAll()
                                        .requestMatchers("/send-message").permitAll()
                                        .requestMatchers("/sendEmail").permitAll()
                                        .requestMatchers("/webhook").permitAll()
                                        .requestMatchers("/webhook/**").permitAll()
                                        .requestMatchers(HttpMethod.POST, "/api/leads/update").permitAll()
                                        .requestMatchers("/api/leads/modified").permitAll()
                                        .requestMatchers("/admin/**").authenticated()
                                        .requestMatchers("/allUsers/**").hasAnyRole("ADMIN", "OWNER")
                                        .requestMatchers("/logs").hasAnyRole("ADMIN", "OWNER")
                                        .requestMatchers("/logs/**").hasAnyRole("ADMIN", "OWNER")
                                        .requestMatchers("/lead/**").hasAnyRole("ADMIN", "OWNER", "MANAGER", "MARKETOLOG")
                                        .requestMatchers("/bots/**").hasAnyRole("ADMIN", "OWNER", "WORKER")
                                        .requestMatchers("/categories/**").hasAnyRole("ADMIN", "OWNER", "MANAGER", "OPERATOR")
                                        .requestMatchers("/subcategories/**").hasAnyRole("ADMIN", "OWNER", "MANAGER", "OPERATOR")
                                        .requestMatchers("/operator/**").hasAnyRole("ADMIN", "OWNER", "OPERATOR","MARKETOLOG")
                                        .requestMatchers("/operators").hasAnyRole("ADMIN", "OWNER", "OPERATOR","MARKETOLOG")
                                        .requestMatchers("/whatsapp").hasAnyRole("ADMIN", "OWNER", "OPERATOR","MARKETOLOG")
                                        .requestMatchers("/operators/**").hasAnyRole("ADMIN", "OWNER", "OPERATOR","MARKETOLOG")
                                        .requestMatchers("/whatsapp/**").hasAnyRole("ADMIN", "OWNER", "OPERATOR","MARKETOLOG")
                                        .requestMatchers("/telephone/**").hasAnyRole("ADMIN", "OWNER", "OPERATOR","MARKETOLOG")
                                        .requestMatchers("/companies/**").hasAnyRole("ADMIN", "OWNER", "MANAGER", "OPERATOR")
                                        .requestMatchers("/products/**").hasAnyRole("ADMIN", "OWNER", "MANAGER")
                                        .requestMatchers("/products").hasAnyRole("ADMIN", "OWNER", "MANAGER")
                                        .requestMatchers("/ordersCompany/**").hasAnyRole("ADMIN", "OWNER", "MANAGER","WORKER")
                                        .requestMatchers("/ordersDetails/**").hasAnyRole("ADMIN", "OWNER", "MANAGER","WORKER")
                                        .requestMatchers("/filial/**").hasAnyRole("ADMIN", "OWNER", "MANAGER","WORKER")
                                        .requestMatchers("/review").hasAnyRole("ADMIN", "OWNER", "MANAGER","WORKER")
                                        .requestMatchers("/reviews/**").hasAnyRole("ADMIN", "OWNER", "MANAGER","WORKER", "OPERATOR")
                                        .requestMatchers("/review/editReview/**").hasAnyRole("ADMIN", "OWNER", "MANAGER","WORKER")
                                        .requestMatchers("/review/addReviews/**").hasAnyRole("ADMIN", "OWNER", "MANAGER","WORKER")
                                        .requestMatchers("/review/deleteReviews/**").hasAnyRole("ADMIN", "OWNER", "MANAGER","WORKER")
                                        .requestMatchers("/review/editReviews/**").permitAll()
                                        .requestMatchers("/review/editReviewses/**").permitAll()
                                        .requestMatchers("/zp/**").hasAnyRole("ADMIN", "OWNER", "MANAGER")
                                        .requestMatchers("/payment_check/**").hasAnyRole("ADMIN", "OWNER", "MANAGER")
                                        .requestMatchers("/orders/**").hasAnyRole("ADMIN", "OWNER", "MANAGER")
                                        .requestMatchers("/worker/**").hasAnyRole("ADMIN", "OWNER", "WORKER","MANAGER")
                                        .requestMatchers("/cities/**").hasAnyRole("ADMIN", "OWNER","MANAGER")
                                        .requestMatchers("/phone").hasAnyRole("ADMIN", "OWNER")
                                        .requestMatchers("/phone/**").hasAnyRole("ADMIN", "OWNER")
//                                        .requestMatchers("/css/**", "/font/**", "/images/**", "/js/**", "/webjars/**").permitAll()
                )
                //    настройка логирования
                .formLogin((formLogin) ->
                                formLogin
                                        .usernameParameter("username")
                                        .passwordParameter("password")
                                        .loginPage("/login")
                                        .loginProcessingUrl("/process-login")
                                        .defaultSuccessUrl("/",true)
                                        .failureUrl("/login?error")
                )
                //    настройка раз логирования
                .logout((logout) ->
                        logout.deleteCookies("remove")
                                .invalidateHttpSession(false)
                                .logoutUrl("/custom-logout")
                                .logoutSuccessUrl("/login")
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)) // добавил недавно
                .exceptionHandling((exceptionHandling) ->
                             exceptionHandling.accessDeniedPage("/access-denied"))
//                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
//                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository) // ✅ используем CSRF-токен из сессии
                        .ignoringRequestMatchers("/api/**", "/webhook/**", "/api/leads/**") // ❌ отключаем для API и Webhook
                );
                // Добавляем наш фильтр перед другими фильтрами безопасности
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
