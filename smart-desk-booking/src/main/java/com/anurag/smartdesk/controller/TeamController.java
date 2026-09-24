package com.anurag.smartdesk.controller;

import com.anurag.smartdesk.dto.response.ApiResponse;
import com.anurag.smartdesk.dto.response.BookingResponse;
import com.anurag.smartdesk.dto.response.TeamResponse;
import com.anurag.smartdesk.model.Booking;
import com.anurag.smartdesk.model.Team;
import com.anurag.smartdesk.repository.BookingRepository;
import com.anurag.smartdesk.service.TeamService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/teams")
public class TeamController {
    
    private final TeamService teamService;
    private final BookingRepository bookingRepository;

    public TeamController(TeamService teamService, BookingRepository bookingRepository) {
        this.teamService = teamService;
        this.bookingRepository = bookingRepository;
    }

    // GET /api/teams/1
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TeamResponse>> getTeamById(@PathVariable Long id) {
        Team team = teamService.getTeamById(id);
        TeamResponse response = TeamResponse.fromEntity(team);
        return ResponseEntity.ok(ApiResponse.success(response, "Team retrieved successfully"));
    }

    // GET /api/teams/1/bookings?floorId=2&date=2026-09-25
    @GetMapping("/{id}/bookings")
    public ResponseEntity<ApiResponse<List<BookingResponse>>> getTeamBookings(
            @PathVariable Long id,
            @RequestParam Long floorId,
            @RequestParam LocalDate date) {
        
        List<Booking> bookings = bookingRepository.findActiveByTeamAndFloorAndDate(id, floorId, date);
        List<BookingResponse> responses = bookings.stream()
                .map(BookingResponse::fromEntity)
                .toList();

        return ResponseEntity.ok(ApiResponse.success(responses, "Team bookings retrieved successfully"));
    }
}
