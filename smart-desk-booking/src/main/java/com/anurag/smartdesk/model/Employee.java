package com.anurag.smartdesk.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;

// Represents an employee in the organization who can book desks.
@Entity
@Table(name = "employees")
@Getter
@Setter
@NoArgsConstructor
public class Employee implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String email;
    private String passwordHash;

    @ManyToOne
    @JoinColumn(name = "team_id")
    private Team team;

    private String timezone = "Asia/Kolkata";

    @Enumerated(EnumType.STRING)
    private Role role = Role.EMPLOYEE;

    private boolean isActive = true;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
