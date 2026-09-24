package com.anurag.smartdesk.service;

import com.anurag.smartdesk.model.Booking;

import java.time.LocalDate;
import java.util.List;

// Service for all booking operations — the heart of the application.
public interface BookingService {

    // Book a hot desk on a floor for a given date.
    // Internally selects the best desk using allocation strategies.
    Booking bookHotDesk(Long employeeId, Long floorId, LocalDate bookingDate);

    // Book hot desks for a team (multiple members).
    // Coordinator-only operation.
    List<Booking> bookTeam(Long coordinatorId, List<Long> employeeIds, Long floorId, LocalDate bookingDate);

    // Cancel an existing booking.
    Booking cancelBooking(Long bookingId, Long employeeId);

    // Check in to a booking (employee has arrived at their desk).
    Booking checkIn(Long bookingId, Long employeeId);

    // Get an employee's booking history, most recent first.
    List<Booking> getBookingHistory(Long employeeId);

    // Sweep expired bookings and mark them as NO_SHOW.
    // Called by a scheduled task, not by controllers.
    int sweepNoShows();
}
