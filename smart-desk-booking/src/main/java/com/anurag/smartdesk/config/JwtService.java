package com.anurag.smartdesk.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;

// Handles all JWT token operations: creation, parsing, validation.
//
// HOW JWT WORKS (simple version):
//
//   1. Employee logs in with email + password
//   2. Server verifies credentials, creates a JWT containing:
//        { "sub": "5", "email": "alice@company.com", "role": "EMPLOYEE", "exp": ... }
//   3. Server signs the JWT with a SECRET KEY (only server knows this)
//   4. Client stores the token and sends it with every request:
//        Authorization: Bearer eyJhbGciOiJIUzI1N...
//   5. Server reads the token, verifies the signature, extracts user info
//
// WHY IS THIS STATELESS?
// The server never stores sessions. All user info is inside the token.
// If the server restarts, all tokens still work because the secret key
// is configured in application.properties, not in memory.
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMs;
    private final Clock clock;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms:86400000}") long expirationMs,
            Clock clock) {

        // The secret must be at least 256 bits (32 characters) for HMAC-SHA256
        this.signingKey = Keys.hmacShaKeyFor(
                secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
        this.clock = clock;
    }

    // Creates a new JWT token for an authenticated employee.
    // The token contains: employeeId (subject), email, and role.
    public String generateToken(Long employeeId, String email,
                                String role) {
        Instant now = Instant.now(clock);
        Instant expiry = now.plusMillis(expirationMs);

        return Jwts.builder()
                .subject(String.valueOf(employeeId))
                .claim("email", email)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    // Extracts all claims (data) from a token.
    // If the token is expired or tampered with, this throws an exception.
    public Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    // Extracts the employee ID from the token's "subject" field.
    public Long extractEmployeeId(String token) {
        return Long.parseLong(extractClaims(token).getSubject());
    }

    // Extracts the email from the token.
    public String extractEmail(String token) {
        return extractClaims(token).get("email", String.class);
    }

    // Extracts the role from the token.
    public String extractRole(String token) {
        return extractClaims(token).get("role", String.class);
    }

    // Checks if a token is valid (signature OK + not expired).
    public boolean isTokenValid(String token) {
        try {
            extractClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
