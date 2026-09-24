package com.anurag.smartdesk.controller;

import com.anurag.smartdesk.dto.response.ApiResponse;
import com.anurag.smartdesk.dto.response.DeskResponse;
import com.anurag.smartdesk.model.Desk;
import com.anurag.smartdesk.service.DeskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/desks")
public class DeskController {
    
    private final DeskService deskService;

    public DeskController(DeskService deskService) {
        this.deskService = deskService;
    }

    // GET /api/desks/1
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DeskResponse>> getDeskById(@PathVariable Long id) {
        Desk desk = deskService.getDeskById(id);
        DeskResponse response = DeskResponse.fromEntity(desk);
        return ResponseEntity.ok(ApiResponse.success(response, "Desk retrieved successfully"));
    }
}
