package com.anurag.smartdesk.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// Central Spring Security configuration.
//
// KEY DECISIONS EXPLAINED:
//
// 1. STATELESS sessions: We use JWT tokens, not server-side sessions.
//    The server doesn't remember who logged in — the token carries all info.
//
// 2. CSRF disabled: CSRF protection is for browser cookie-based sessions.
//    Since we use Bearer tokens (not cookies), CSRF attacks don't apply.
//
// 3. BCrypt password hashing: Passwords are never stored in plain text.
//    BCrypt is deliberately slow (strength=12 means ~250ms per hash),
//    making brute-force attacks impractical even if the database leaks.
//
// 4. @EnableMethodSecurity: Allows @PreAuthorize annotations on individual
//    controller methods for fine-grained role checks (e.g., admin-only endpoints).
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF (we use JWT, not cookies)
            .csrf(csrf -> csrf.disable())

            // Stateless sessions (no server-side session storage)
            .sessionManagement(session ->
                    session.sessionCreationPolicy(
                            SessionCreationPolicy.STATELESS))

            // Define which endpoints are public vs protected
            .authorizeHttpRequests(auth -> auth
                // Auth endpoints (login, register) are open to everyone
                .requestMatchers("/api/auth/**").permitAll()

                // Actuator health endpoint is public (for load balancers)
                .requestMatchers("/actuator/health").permitAll()

                // Everything else requires authentication
                .anyRequest().authenticated()
            )

            // Insert our JWT filter before Spring's default auth filter
            .addFilterBefore(jwtAuthFilter,
                    UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // BCrypt password encoder with strength 12.
    // Strength 12 means 2^12 = 4096 hashing rounds.
    // This makes each hash take ~250ms, which is imperceptible to users
    // but makes brute-force attacks extremely expensive.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
