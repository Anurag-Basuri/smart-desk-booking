package com.anurag.smartdesk.controller;

import com.anurag.smartdesk.dto.request.LoginRequest;
import com.anurag.smartdesk.dto.request.RegisterRequest;
import com.anurag.smartdesk.dto.response.ApiResponse;
import com.anurag.smartdesk.dto.response.AuthResponse;
import com.anurag.smartdesk.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Authentication endpoints — these are PUBLIC (no JWT required).
// See SecurityConfig: requestMatchers("/api/auth/**").permitAll()
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    // POST /api/auth/register
    // Body: { "name": "Alice", "email": "alice@co.com",
    //         "password": "secret123", "teamName": "Engineering" }
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request) {

        AuthResponse response = authService.register(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response,
                        "Registration successful"));
    }

    // POST /api/auth/login
    // Body: { "email": "alice@co.com", "password": "secret123" }
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request) {

        AuthResponse response = authService.login(request);

        return ResponseEntity.ok(
                ApiResponse.success(response,
                        "Login successful"));
    }
}
