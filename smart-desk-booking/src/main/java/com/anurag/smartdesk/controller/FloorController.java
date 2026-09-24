package com.anurag.smartdesk.controller;

import com.anurag.smartdesk.dto.response.ApiResponse;
import com.anurag.smartdesk.dto.response.DeskRecommendationResponse;
import com.anurag.smartdesk.dto.response.DeskResponse;
import com.anurag.smartdesk.dto.response.FloorResponse;
import com.anurag.smartdesk.model.Desk;
import com.anurag.smartdesk.model.Floor;
import com.anurag.smartdesk.service.BookingService;
import com.anurag.smartdesk.service.DeskService;
import com.anurag.smartdesk.service.FloorService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

// REST API for floor and desk layout information.
@RestController
@RequestMapping("/api/floors")
public class FloorController {

    private final FloorService floorService;
    private final DeskService deskService;
    private final BookingService bookingService;

    public FloorController(FloorService floorService,
                           DeskService deskService,
                           BookingService bookingService) {
        this.floorService = floorService;
        this.deskService = deskService;
        this.bookingService = bookingService;
    }

    // GET /api/floors
    // Returns all active floors in the building.
    @GetMapping
    public ResponseEntity<ApiResponse<List<FloorResponse>>> getAllFloors() {

        List<Floor> floors = floorService.getAllActiveFloors();

        List<FloorResponse> responses = floors.stream()
                .map(FloorResponse::fromEntity)
                .toList();

        return ResponseEntity.ok(
                ApiResponse.success(responses,
                        "Floors retrieved successfully"));
    }

    // GET /api/floors/1
    // Returns details for a specific floor.
    @GetMapping("/{floorId}")
    public ResponseEntity<ApiResponse<FloorResponse>> getFloor(
            @PathVariable Long floorId) {

        Floor floor = floorService.getFloorById(floorId);
        FloorResponse response = FloorResponse.fromEntity(floor);

        return ResponseEntity.ok(
                ApiResponse.success(response,
                        "Floor retrieved successfully"));
    }

    // GET /api/floors/1/desks
    // Returns the desk layout (all active desks) on a floor.
    @GetMapping("/{floorId}/desks")
    public ResponseEntity<ApiResponse<List<DeskResponse>>> getDesks(
            @PathVariable Long floorId) {

        List<Desk> desks = deskService.getDesksOnFloor(floorId);

        List<DeskResponse> responses = desks.stream()
                .map(DeskResponse::fromEntity)
                .toList();

        return ResponseEntity.ok(
                ApiResponse.success(responses,
                        "Desks retrieved successfully"));
    }

    // GET /api/floors/1/available-desks?date=2026-09-25
    // Returns only the HOT desks that are free on the given date.
    @GetMapping("/{floorId}/available-desks")
    public ResponseEntity<ApiResponse<List<DeskResponse>>> getAvailableDesks(
            @PathVariable Long floorId,
            @RequestParam LocalDate date) {

        List<Desk> desks = deskService
                .getAvailableHotDesks(floorId, date);

        List<DeskResponse> responses = desks.stream()
                .map(DeskResponse::fromEntity)
                .toList();

        return ResponseEntity.ok(
                ApiResponse.success(responses,
                        "Available desks retrieved"));
    }

    // GET /api/floors/1/recommendations?date=2026-09-28&limit=10
    // Returns the best available seats ranked by proximity to teammates/centroid.
    @GetMapping("/{floorId}/recommendations")
    public ResponseEntity<ApiResponse<List<DeskRecommendationResponse>>> getRecommendations(
            Authentication auth,
            @PathVariable Long floorId,
            @RequestParam LocalDate date,
            @RequestParam(required = false, defaultValue = "10") Integer limit) {

        Long employeeId = (Long) auth.getPrincipal();
        List<DeskRecommendationResponse> recommendations = bookingService
                .getRecommendations(employeeId, floorId, date, limit);

        return ResponseEntity.ok(
                ApiResponse.success(recommendations,
                        "Seat recommendations retrieved successfully"));
    }
}
