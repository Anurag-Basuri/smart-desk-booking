package com.anurag.smartdesk.dto.request;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

// What the client sends when booking a hot desk.
// Jakarta Bean Validation annotations enforce the rules
// BEFORE the request reaches our service layer.
@Getter
@Setter
public class BookingRequest {

    @NotNull(message = "Floor ID is required")
    private Long floorId;

    @NotNull(message = "Booking date is required")
    @FutureOrPresent(message = "Booking date cannot be in the past")
    private LocalDate bookingDate;

    // Optional manual desk selection. If omitted, smart spatial algorithm assigns the best desk.
    private Long deskId;
}
