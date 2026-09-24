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

// Represents a floor in the office building, which can contain multiple desks.
@Entity
@Table(name = "floors")
@Getter
@Setter
@NoArgsConstructor
public class Floor implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private int floorNumber;
    private String name;
    private int maxCapacity;
    private int centerRow;
    private int centerColumn;
    private String timezone = "Asia/Kolkata";
    private boolean isActive = true;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
