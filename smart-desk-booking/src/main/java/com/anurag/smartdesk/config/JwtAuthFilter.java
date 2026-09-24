package com.anurag.smartdesk.config;

import com.anurag.smartdesk.model.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

// Intercepts every HTTP request and checks for a JWT token.
//
// HOW THIS FILTER WORKS:
//
//   1. Client sends: Authorization: Bearer eyJhbGci...
//   2. This filter extracts the token from the header
//   3. Validates the token (signature + expiry)
//   4. If valid, tells Spring Security: "This request is from employee #5
//      with role EMPLOYEE" — so the rest of the app can use it
//   5. If no token or invalid token, the filter does nothing
//      and Spring Security will reject the request (401 Unauthorized)
//
// WHY OncePerRequestFilter?
// Spring's filter chain can sometimes call a filter multiple times
// per request (e.g., during forwarding). OncePerRequestFilter guarantees
// this logic runs exactly once.
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // Step 1: Extract the Authorization header
        String authHeader = request.getHeader("Authorization");

        // No token? Let it pass — Spring Security will block if needed
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Step 2: Extract the token (everything after "Bearer ")
        String token = authHeader.substring(7);

        // Step 3: Validate and extract claims
        if (jwtService.isTokenValid(token)) {
            Long employeeId = jwtService.extractEmployeeId(token);
            String email = jwtService.extractEmail(token);
            String role = jwtService.extractRole(token);

            // Step 4: Tell Spring Security who this user is
            // The "principal" is the employee ID (we'll use this
            // in controllers to know who is making the request)
            UsernamePasswordAuthenticationToken authToken =
                    new UsernamePasswordAuthenticationToken(
                            employeeId,
                            null,
                            List.of(new SimpleGrantedAuthority(
                                    "ROLE_" + role)));

            SecurityContextHolder.getContext()
                    .setAuthentication(authToken);
        }

        // Step 5: Continue the filter chain
        filterChain.doFilter(request, response);
    }
}
