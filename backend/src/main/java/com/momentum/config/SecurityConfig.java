package com.momentum.config;

import com.momentum.filter.AdminAuthFilter;
import com.momentum.filter.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final AdminAuthFilter adminAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, AdminAuthFilter adminAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.adminAuthFilter = adminAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // /admin/** is gated by AdminAuthFilter's X-Admin-Key check instead of Supabase
                // JWT auth — permitAll() here just means Spring Security itself doesn't also
                // demand a Supabase-authenticated principal for these paths.
                .requestMatchers("/admin/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(adminAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
