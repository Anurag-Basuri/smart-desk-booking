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
import java.time.LocalDate;
import java.time.LocalTime;

// Represents a booking for a desk by an employee.
@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "desk_id")
    private Desk desk;

    @ManyToOne
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @ManyToOne
    @JoinColumn(name = "team_id")
    private Team team;

    @ManyToOne
    @JoinColumn(name = "floor_id")
    private Floor floor;

    private LocalDate bookingDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private Instant checkInDeadline;

    @Enumerated(EnumType.STRING)
    private BookingStatus status = BookingStatus.BOOKED;

    private boolean isOwnerBooking = false;

    private Instant checkedInAt;
    private Instant cancelledAt;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public boolean isActive() {
        return this.status == BookingStatus.BOOKED || this.status == BookingStatus.CHECKED_IN;
    }
}
