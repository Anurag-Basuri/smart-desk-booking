package com.anurag.smartdesk.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;

// Represents a team within the organization, which can have multiple employees.
@Entity
@Table(name = "teams")
@Getter
@Setter
@NoArgsConstructor
public class Team implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public Team(String name) {
        this.name = name;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }
}
