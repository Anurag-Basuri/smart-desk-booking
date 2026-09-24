package com.anurag.smartdesk.service.impl;

import com.anurag.smartdesk.config.JwtService;
import com.anurag.smartdesk.dto.request.LoginRequest;
import com.anurag.smartdesk.dto.request.RegisterRequest;
import com.anurag.smartdesk.dto.response.AuthResponse;
import com.anurag.smartdesk.exception.AlreadyBookedException;
import com.anurag.smartdesk.exception.ResourceNotFoundException;
import com.anurag.smartdesk.model.Employee;
import com.anurag.smartdesk.model.Team;
import com.anurag.smartdesk.repository.EmployeeRepository;
import com.anurag.smartdesk.repository.TeamRepository;
import com.anurag.smartdesk.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(
            AuthServiceImpl.class);

    private final EmployeeRepository employeeRepository;
    private final TeamRepository teamRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthServiceImpl(EmployeeRepository employeeRepository,
                           TeamRepository teamRepository,
                           PasswordEncoder passwordEncoder,
                           JwtService jwtService) {
        this.employeeRepository = employeeRepository;
        this.teamRepository = teamRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /*
     * Registration flow:
     *   1. Check if email is already taken
     *   2. Find or create the team
     *   3. Hash the password (NEVER store plain text)
     *   4. Save the employee
     *   5. Generate a JWT token so the user is logged in immediately
     */
    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Check duplicate email
        if (employeeRepository.existsByEmail(request.getEmail())) {
            throw new AlreadyBookedException(
                    "Email already registered: " + request.getEmail());
        }

        // Find existing team or create a new one
        Team team = teamRepository.findByName(request.getTeamName())
                .orElseGet(() -> {
                    Team newTeam = new Team(request.getTeamName());
                    return teamRepository.save(newTeam);
                });

        // Create the employee with hashed password
        Employee employee = new Employee();
        employee.setName(request.getName());
        employee.setEmail(request.getEmail());
        employee.setPasswordHash(
                passwordEncoder.encode(request.getPassword()));
        employee.setTeam(team);
        employee.setCreatedAt(Instant.now());
        employee.setUpdatedAt(Instant.now());

        Employee saved = employeeRepository.save(employee);

        log.info("New employee registered: id={}, email={}",
                saved.getId(), saved.getEmail());

        // Generate token so user is logged in right after registration
        String token = jwtService.generateToken(
                saved.getId(),
                saved.getEmail(),
                saved.getRole().name());

        return new AuthResponse(
                token,
                saved.getId(),
                saved.getName(),
                saved.getEmail(),
                saved.getRole().name(),
                team.getName());
    }

    /*
     * Login flow:
     *   1. Find employee by email
     *   2. Verify password against stored hash
     *   3. Generate and return JWT token
     */
    @Override
    public AuthResponse login(LoginRequest request) {
        Employee employee = employeeRepository
                .findByEmail(request.getEmail())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Invalid email or password"));

        // Compare the plain-text password with the stored BCrypt hash
        if (!passwordEncoder.matches(request.getPassword(),
                employee.getPasswordHash())) {
            throw new ResourceNotFoundException(
                    "Invalid email or password");
        }

        log.info("Employee logged in: id={}, email={}",
                employee.getId(), employee.getEmail());

        String token = jwtService.generateToken(
                employee.getId(),
                employee.getEmail(),
                employee.getRole().name());

        return new AuthResponse(
                token,
                employee.getId(),
                employee.getName(),
                employee.getEmail(),
                employee.getRole().name(),
                employee.getTeam().getName());
    }
}
