package com.anurag.smartdesk.controller;

import com.anurag.smartdesk.dto.request.BookingRequest;
import com.anurag.smartdesk.dto.response.ApiResponse;
import com.anurag.smartdesk.dto.response.BookingResponse;
import com.anurag.smartdesk.model.Booking;
import com.anurag.smartdesk.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// REST API for all booking operations.
//
// Notice how slim this controller is — it does three things only:
//   1. Accept and validate HTTP input (@Valid, @PathVariable)
//   2. Delegate to the BookingService
//   3. Convert entities to DTOs and wrap in ApiResponse
//
// The employee ID is extracted from the JWT token automatically.
// The JwtAuthFilter puts it in the Authentication principal.
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    // POST /api/bookings
    // Header: Authorization: Bearer <token>
    // Body: { "floorId": 1, "bookingDate": "2026-09-25" }
    @PostMapping
    public ResponseEntity<ApiResponse<BookingResponse>> bookDesk(
            Authentication auth,
            @Valid @RequestBody BookingRequest request) {

        Long employeeId = getEmployeeId(auth);

        Booking booking = bookingService.bookHotDesk(
                employeeId,
                request.getFloorId(),
                request.getBookingDate());

        BookingResponse response = BookingResponse.fromEntity(booking);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response,
                        "Desk booked successfully"));
    }

    // POST /api/bookings/5/cancel
    @PostMapping("/{bookingId}/cancel")
    public ResponseEntity<ApiResponse<BookingResponse>> cancelBooking(
            Authentication auth,
            @PathVariable Long bookingId) {

        Long employeeId = getEmployeeId(auth);

        Booking booking = bookingService.cancelBooking(
                bookingId, employeeId);

        BookingResponse response = BookingResponse.fromEntity(booking);

        return ResponseEntity.ok(
                ApiResponse.success(response,
                        "Booking cancelled successfully"));
    }

    // POST /api/bookings/5/check-in
    @PostMapping("/{bookingId}/check-in")
    public ResponseEntity<ApiResponse<BookingResponse>> checkIn(
            Authentication auth,
            @PathVariable Long bookingId) {

        Long employeeId = getEmployeeId(auth);

        Booking booking = bookingService.checkIn(
                bookingId, employeeId);

        BookingResponse response = BookingResponse.fromEntity(booking);

        return ResponseEntity.ok(
                ApiResponse.success(response,
                        "Checked in successfully"));
    }

    // GET /api/bookings/history
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<BookingResponse>>> getHistory(
            Authentication auth) {

        Long employeeId = getEmployeeId(auth);

        List<Booking> bookings = bookingService
                .getBookingHistory(employeeId);

        List<BookingResponse> responses = bookings.stream()
                .map(BookingResponse::fromEntity)
                .toList();

        return ResponseEntity.ok(
                ApiResponse.success(responses,
                        "Booking history retrieved"));
    }

    // Extracts the employee ID from the JWT token.
    // The JwtAuthFilter stores the employee ID as the Authentication principal.
    private Long getEmployeeId(Authentication auth) {
        return (Long) auth.getPrincipal();
    }
}
