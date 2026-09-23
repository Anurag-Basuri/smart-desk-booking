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

import java.time.Instant;

// Represents a physical desk in the office layout.
@Entity
@Table(name = "desks")
@Getter
@Setter
@NoArgsConstructor
public class Desk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "floor_id")
    private Floor floor;

    private int rowNumber;
    private int columnNumber;

    @Enumerated(EnumType.STRING)
    private DeskType deskType = DeskType.HOT;

    @ManyToOne
    @JoinColumn(name = "reserved_for_employee_id")
    private Employee reservedForEmployee;

    private boolean isActive = true;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public boolean isFixed() {
        return this.deskType == DeskType.FIXED;
    }
}
