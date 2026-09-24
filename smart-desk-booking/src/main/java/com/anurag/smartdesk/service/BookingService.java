package com.anurag.smartdesk.service;

import com.anurag.smartdesk.dto.response.DeskRecommendationResponse;
import com.anurag.smartdesk.model.Booking;

import java.time.LocalDate;
import java.util.List;

// Service for all booking operations — supports both intelligent auto-allocation and manual desk selection.
public interface BookingService {

    // Book a desk on a floor for a given date.
    // If deskId is null, selects the best desk using spatial allocation strategies.
    // If deskId is provided, validates and books that specific desk.
    Booking bookHotDesk(Long employeeId, Long floorId, LocalDate bookingDate, Long deskId);

    default Booking bookHotDesk(Long employeeId, Long floorId, LocalDate bookingDate) {
        return bookHotDesk(employeeId, floorId, bookingDate, null);
    }

    // Book desks for a team. Coordinator-only operation.
    // If deskIds is null or empty, uses anchor-and-expand spatial clustering.
    // If deskIds is provided, validates and books those specific desks.
    List<Booking> bookTeam(Long coordinatorId, List<Long> employeeIds, Long floorId, LocalDate bookingDate, List<Long> deskIds);

    default List<Booking> bookTeam(Long coordinatorId, List<Long> employeeIds, Long floorId, LocalDate bookingDate) {
        return bookTeam(coordinatorId, employeeIds, floorId, bookingDate, null);
    }

    // Returns ranked seat recommendations for an employee on a floor and date.
    List<DeskRecommendationResponse> getRecommendations(Long employeeId, Long floorId, LocalDate bookingDate, Integer limit);

    // Cancel an existing booking.
    Booking cancelBooking(Long bookingId, Long employeeId);

    // Check in to a booking (employee has arrived at their desk).
    Booking checkIn(Long bookingId, Long employeeId);

    // Get an employee's booking history, most recent first.
    List<Booking> getBookingHistory(Long employeeId);

    // Sweep expired bookings and mark them as NO_SHOW.
    int sweepNoShows();
}
