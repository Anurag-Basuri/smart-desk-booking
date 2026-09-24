package com.anurag.smartdesk.service;

import com.anurag.smartdesk.dto.request.LoginRequest;
import com.anurag.smartdesk.dto.request.RegisterRequest;
import com.anurag.smartdesk.dto.response.AuthResponse;

// Service for authentication operations (register and login).
public interface AuthService {

    AuthResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);
}
