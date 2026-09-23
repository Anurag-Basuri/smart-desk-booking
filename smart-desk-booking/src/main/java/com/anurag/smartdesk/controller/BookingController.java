package com.anurag.smartdesk.controller;

import com.anurag.smartdesk.dto.request.BookingRequest;
import com.anurag.smartdesk.dto.response.ApiResponse;
import com.anurag.smartdesk.dto.response.BookingResponse;
import com.anurag.smartdesk.model.Booking;
import com.anurag.smartdesk.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// REST API for all booking operations.
//
// Notice how slim this controller is — it does three things only:
//   1. Accept and validate HTTP input (@Valid, @PathVariable, @RequestParam)
//   2. Delegate to the BookingService
//   3. Convert entities to DTOs and wrap in ApiResponse
//
// All business logic (locks, quotas, strategies) lives in BookingServiceImpl.
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    // POST /api/bookings?employeeId=1
    // Body: { "floorId": 1, "bookingDate": "2026-09-25" }
    //
    // Why is employeeId a query param and not in the body?
    // In production, this would come from the JWT token (the logged-in user).
    // For now, we pass it as a query param so we can test without auth.
    // When we add Spring Security later, we'll extract it from the token.
    @PostMapping
    public ResponseEntity<ApiResponse<BookingResponse>> bookDesk(
            @RequestParam Long employeeId,
            @Valid @RequestBody BookingRequest request) {

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

    // POST /api/bookings/5/cancel?employeeId=1
    @PostMapping("/{bookingId}/cancel")
    public ResponseEntity<ApiResponse<BookingResponse>> cancelBooking(
            @PathVariable Long bookingId,
            @RequestParam Long employeeId) {

        Booking booking = bookingService.cancelBooking(
                bookingId, employeeId);

        BookingResponse response = BookingResponse.fromEntity(booking);

        return ResponseEntity.ok(
                ApiResponse.success(response,
                        "Booking cancelled successfully"));
    }

    // POST /api/bookings/5/check-in?employeeId=1
    @PostMapping("/{bookingId}/check-in")
    public ResponseEntity<ApiResponse<BookingResponse>> checkIn(
            @PathVariable Long bookingId,
            @RequestParam Long employeeId) {

        Booking booking = bookingService.checkIn(
                bookingId, employeeId);

        BookingResponse response = BookingResponse.fromEntity(booking);

        return ResponseEntity.ok(
                ApiResponse.success(response,
                        "Checked in successfully"));
    }

    // GET /api/bookings/history?employeeId=1
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<BookingResponse>>> getHistory(
            @RequestParam Long employeeId) {

        List<Booking> bookings = bookingService
                .getBookingHistory(employeeId);

        List<BookingResponse> responses = bookings.stream()
                .map(BookingResponse::fromEntity)
                .toList();

        return ResponseEntity.ok(
                ApiResponse.success(responses,
                        "Booking history retrieved"));
    }
}
