package com.anurag.smartdesk.repository;

import com.anurag.smartdesk.model.Desk;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DeskRepository extends JpaRepository<Desk, Long> {

    // All active desks on a floor (used for desk layout display)
    List<Desk> findByFloorIdAndIsActiveTrue(Long floorId);

    // Find a fixed desk assigned to a specific employee
    Optional<Desk> findByReservedForEmployeeId(Long employeeId);

    // Pessimistic lock on a specific desk row
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Desk d WHERE d.id = :id")
    Optional<Desk> findByIdForUpdate(Long id);

    /*
     * Find all HOT desks on a floor that are active AND not already booked
     * on the given date. These are the desks eligible for booking.
     *
     * How it works:
     * - Start with all active HOT desks on the floor.
     * - Exclude any desk that already has an active booking (BOOKED or CHECKED_IN)
     *   on the requested date.
     * - Order by desk ID for deterministic tie-breaking.
     */
    @Query("""
        SELECT d FROM Desk d
        WHERE d.floor.id = :floorId
            AND d.isActive = true
            AND d.deskType = com.anurag.smartdesk.model.DeskType.HOT
            AND d.id NOT IN (
                SELECT b.desk.id FROM Booking b
                WHERE b.floor.id = :floorId
                    AND b.bookingDate = :date
                    AND b.status IN (
                        com.anurag.smartdesk.model.BookingStatus.BOOKED,
                        com.anurag.smartdesk.model.BookingStatus.CHECKED_IN
                    )
                )
        ORDER BY d.id ASC
    """)
    List<Desk> findAvailableHotDesks(Long floorId, LocalDate date);
}
