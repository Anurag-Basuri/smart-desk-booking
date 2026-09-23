package com.anurag.smartdesk.service;

import com.anurag.smartdesk.model.Floor;

import java.util.List;

// Service for floor-related operations.
public interface FloorService {

    Floor getFloorById(Long id);

    List<Floor> getAllActiveFloors();
}
