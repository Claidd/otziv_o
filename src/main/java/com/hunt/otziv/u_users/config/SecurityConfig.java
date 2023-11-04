package com.hunt.otziv.u_users.config;

import com.hunt.otziv.u_users.services.UserServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
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
    private final JwtRequestFilter jwtRequestFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception{
        HttpSessionCsrfTokenRepository csrfTokenRepository = new HttpSessionCsrfTokenRepository();

        http
                .authorizeHttpRequests((authorizeRequests) ->
                                authorizeRequests
                                        //    настройка доступа
                                        .requestMatchers("/auth","/login","/register").permitAll()
                                        .requestMatchers("/api/auth").permitAll()
                                        .requestMatchers("/.well-known/acme-challenge/**").permitAll()
                                        .requestMatchers("/access-denied").permitAll()
                                        .requestMatchers("/lead/new_lead").hasAnyRole("ADMIN", "OPERATOR","MARKETOLOG")
                                        .requestMatchers("/").permitAll()
                                        .requestMatchers("/admin/**").authenticated()
                                        .requestMatchers("/allUsers/**").hasRole("ADMIN")
                                        .requestMatchers("/lead/**").hasAnyRole("ADMIN", "MANAGER", "MARKETOLOG")
                                        .requestMatchers("/bots/**").hasAnyRole("ADMIN","WORKER")
                                        .requestMatchers("/categories/**").hasAnyRole("ADMIN","MANAGER")
                                        .requestMatchers("/subcategories/**").hasAnyRole("ADMIN","MANAGER")
                                        .requestMatchers("/operator/**").hasAnyRole("ADMIN", "OPERATOR","MARKETOLOG")
                                        .requestMatchers("/companies/**").hasAnyRole("ADMIN","MANAGER")
                                        .requestMatchers("/products/**").hasAnyRole("ADMIN","MANAGER")
                                        .requestMatchers("/products").hasAnyRole("ADMIN","MANAGER")
                                        .requestMatchers("/ordersCompany/**").hasAnyRole("ADMIN","MANAGER","WORKER")
                                        .requestMatchers("/ordersDetails/**").hasAnyRole("ADMIN","MANAGER","WORKER")
                                        .requestMatchers("/filial/**").hasAnyRole("ADMIN","MANAGER","WORKER")
                                        .requestMatchers("/review").hasAnyRole("ADMIN","MANAGER","WORKER")
                                        .requestMatchers("/review/editReview/**").hasAnyRole("ADMIN","MANAGER","WORKER")
                                        .requestMatchers("/review/addReviews/**").hasAnyRole("ADMIN","MANAGER","WORKER")
                                        .requestMatchers("/review/deleteReviews/**").hasAnyRole("ADMIN","MANAGER","WORKER")
                                        .requestMatchers("/review/editReviews/**").permitAll()
                                        .requestMatchers("/review/editReviewses/**").permitAll()
                                        .requestMatchers("/zp/**").hasAnyRole("ADMIN","MANAGER")
                                        .requestMatchers("/payment_check/**").hasAnyRole("ADMIN","MANAGER")
                                        .requestMatchers("/orders/**").hasAnyRole("ADMIN","MANAGER")
                                        .requestMatchers("/worker/**").hasAnyRole("ADMIN","WORKER","MANAGER")

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
                .exceptionHandling((exceptionHandling) ->
                             exceptionHandling.accessDeniedPage("/access-denied"))
//                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
//                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .csrf((csrf) -> csrf
                        .csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler())
                        .sessionAuthenticationStrategy(new CsrfAuthenticationStrategy(csrfTokenRepository))
//                                .requireCsrfProtectionMatcher()
//                        .ignoringRequestMatchers("/**")
                )
                //добавляем передачу токена в контекст перед указанным фильтром
                .addFilterBefore(jwtRequestFilter, UsernamePasswordAuthenticationFilter.class);

                //обработчик ошибки ТОЛЬКО для режима отладки
//                .exceptionHandling(exceptionHandling -> exceptionHandling
//                        .accessDeniedHandler(((request, response, accessDeniedException) ->
//                                accessDeniedException.printStackTrace())));

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

//    @Bean
//    public PasswordEncoder passwordEncoder() {
//        return NoOpPasswordEncoder.getInstance();
//    }




















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
