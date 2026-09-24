package com.anurag.smartdesk.dto.request;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
public class TeamBookingRequest {

    @NotNull(message = "Floor ID is required")
    private Long floorId;

    @NotNull(message = "Booking date is required")
    @FutureOrPresent(message = "Booking date cannot be in the past")
    private LocalDate bookingDate;

    @NotEmpty(message = "Employee IDs list cannot be empty")
    private List<Long> employeeIds;
}
