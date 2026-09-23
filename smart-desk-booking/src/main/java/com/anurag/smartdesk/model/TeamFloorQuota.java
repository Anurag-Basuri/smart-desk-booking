package com.anurag.smartdesk.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

// Represents the maximum number of desks that a team can book on a specific floor.
@Entity
@Table(name = "team_floor_quotas")
@Getter
@Setter
@NoArgsConstructor
public class TeamFloorQuota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "team_id")
    private Team team;

    @ManyToOne
    @JoinColumn(name = "floor_id")
    private Floor floor;

    private int maxDesks;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
