package com.anurag.smartdesk.dto.response;

import com.anurag.smartdesk.model.Booking;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

// What the client receives after booking, cancelling, or checking in.
// Notice: no entity objects, no database IDs of related tables leaking out.
// Only clean, meaningful fields that the frontend actually needs.
@Getter
@Setter
public class BookingResponse {

    private Long bookingId;
    private LocalDate bookingDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private String status;
    private Instant checkInDeadline;
    private Instant checkedInAt;
    private Instant cancelledAt;
    private Instant createdAt;

    // Desk info — flattened, not nested entity objects
    private Long deskId;
    private int deskRow;
    private int deskColumn;
    private String deskType;

    // Floor info
    private Long floorId;
    private String floorName;
    private int floorNumber;

    // Employee info
    private Long employeeId;
    private String employeeName;

    // Team info
    private String teamName;

    /*
     * Converts a Booking entity into a clean response DTO.
     *
     * Why a static factory method instead of a constructor?
     * It reads better at the call site:
     *   BookingResponse.fromEntity(booking)
     * vs:
     *   new BookingResponse(booking)
     *
     * Both work, but "fromEntity" makes the intent crystal clear.
     */
    public static BookingResponse fromEntity(Booking booking) {
        BookingResponse response = new BookingResponse();
        response.setBookingId(booking.getId());
        response.setBookingDate(booking.getBookingDate());
        response.setStartTime(booking.getStartTime());
        response.setEndTime(booking.getEndTime());
        response.setStatus(booking.getStatus().name());
        response.setCheckInDeadline(booking.getCheckInDeadline());
        response.setCheckedInAt(booking.getCheckedInAt());
        response.setCancelledAt(booking.getCancelledAt());
        response.setCreatedAt(booking.getCreatedAt());

        // Flatten desk info
        if (booking.getDesk() != null) {
            response.setDeskId(booking.getDesk().getId());
            response.setDeskRow(booking.getDesk().getRowNumber());
            response.setDeskColumn(booking.getDesk().getColumnNumber());
            response.setDeskType(
                    booking.getDesk().getDeskType().name());
        }

        // Flatten floor info
        if (booking.getFloor() != null) {
            response.setFloorId(booking.getFloor().getId());
            response.setFloorName(booking.getFloor().getName());
            response.setFloorNumber(booking.getFloor().getFloorNumber());
        }

        // Flatten employee info
        if (booking.getEmployee() != null) {
            response.setEmployeeId(booking.getEmployee().getId());
            response.setEmployeeName(booking.getEmployee().getName());
        }

        // Flatten team info
        if (booking.getTeam() != null) {
            response.setTeamName(booking.getTeam().getName());
        }

        return response;
    }
}
