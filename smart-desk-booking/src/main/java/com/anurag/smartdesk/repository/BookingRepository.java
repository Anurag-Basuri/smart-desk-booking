package com.anurag.smartdesk.repository;

import com.anurag.smartdesk.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    // Check if employee already has an active booking on this date
    @Query("""
        SELECT b FROM Booking b
        WHERE b.employee.id = :employeeId
        AND b.bookingDate = :date
        AND b.status IN (
            com.anurag.smartdesk.model.BookingStatus.BOOKED,
            com.anurag.smartdesk.model.BookingStatus.CHECKED_IN
        )
    """)
    Optional<Booking> findActiveByEmployeeAndDate(Long employeeId, LocalDate date);

    // Count active HOT bookings for a team on a floor on a date (for quota check)
    @Query("""
        SELECT COUNT(b) FROM Booking b
        WHERE b.team.id = :teamId
        AND b.floor.id = :floorId
        AND b.bookingDate = :date
        AND b.isOwnerBooking = false
        AND b.status IN (
            com.anurag.smartdesk.model.BookingStatus.BOOKED,
            com.anurag.smartdesk.model.BookingStatus.CHECKED_IN
        )
    """)
    long countActiveHotBookingsByTeamAndFloor(Long teamId, Long floorId, LocalDate date);

    // Count active HOT bookings on a floor on a date (for floor capacity headroom)
    @Query("""
        SELECT COUNT(b) FROM Booking b
        WHERE b.floor.id = :floorId
        AND b.bookingDate = :date
        AND b.isOwnerBooking = false
        AND b.status IN (
            com.anurag.smartdesk.model.BookingStatus.BOOKED,
            com.anurag.smartdesk.model.BookingStatus.CHECKED_IN
        )
    """)
    long countActiveHotBookingsByFloor(Long floorId, LocalDate date);

    // Find all active bookings for a team on a floor on a date
    // (used to locate where teammates are sitting for neighbourhood placement)
    @Query("""
        SELECT b FROM Booking b
        WHERE b.team.id = :teamId
        AND b.floor.id = :floorId
        AND b.bookingDate = :date
        AND b.status IN (
            com.anurag.smartdesk.model.BookingStatus.BOOKED,
            com.anurag.smartdesk.model.BookingStatus.CHECKED_IN
        )
    """)
    List<Booking> findActiveByTeamAndFloorAndDate(Long teamId, Long floorId, LocalDate date);

    // Count fixed desks on a floor whose owners cancelled or no-showed today
    // (these are "released" fixed desks that free up headroom for hot bookings)
    @Query("""
        SELECT COUNT(b) FROM Booking b
        WHERE b.floor.id = :floorId
        AND b.bookingDate = :date
        AND b.isOwnerBooking = true
        AND b.status IN (
            com.anurag.smartdesk.model.BookingStatus.CANCELLED,
            com.anurag.smartdesk.model.BookingStatus.NO_SHOW
        )
    """)
    long countReleasedFixedDesks(Long floorId, LocalDate date);

    // Employee's booking history (most recent first)
    List<Booking> findByEmployeeIdOrderByBookingDateDesc(Long employeeId);

    // No-show auto-release sweeper.
    // Marks all BOOKED bookings whose check-in deadline has passed as NO_SHOW.
    @Modifying
    @Query("""
        UPDATE Booking b
        SET b.status = com.anurag.smartdesk.model.BookingStatus.NO_SHOW,
            b.updatedAt = :now
        WHERE b.status = com.anurag.smartdesk.model.BookingStatus.BOOKED
          AND b.checkInDeadline <= :now
    """)
    int markNoShowBookings(Instant now);
}
