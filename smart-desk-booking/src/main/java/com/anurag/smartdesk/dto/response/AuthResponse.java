package com.anurag.smartdesk.dto.response;

import lombok.Getter;
import lombok.Setter;

// What the client receives after login or registration.
// Contains the JWT token and basic user info.
@Getter
@Setter
public class AuthResponse {

    private String token;
    private Long employeeId;
    private String name;
    private String email;
    private String role;
    private String teamName;

    public AuthResponse(String token, Long employeeId, String name,
                        String email, String role, String teamName) {
        this.token = token;
        this.employeeId = employeeId;
        this.name = name;
        this.email = email;
        this.role = role;
        this.teamName = teamName;
    }
}
