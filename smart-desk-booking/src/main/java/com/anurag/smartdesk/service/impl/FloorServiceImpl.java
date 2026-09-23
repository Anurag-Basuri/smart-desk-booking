package com.anurag.smartdesk.service.impl;

import com.anurag.smartdesk.exception.ResourceNotFoundException;
import com.anurag.smartdesk.model.Floor;
import com.anurag.smartdesk.repository.FloorRepository;
import com.anurag.smartdesk.service.FloorService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FloorServiceImpl implements FloorService {

    private final FloorRepository floorRepository;

    public FloorServiceImpl(FloorRepository floorRepository) {
        this.floorRepository = floorRepository;
    }

    @Override
    public Floor getFloorById(Long id) {
        return floorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Floor not found with ID: " + id));
    }

    @Override
    public List<Floor> getAllActiveFloors() {
        return floorRepository.findByIsActiveTrue();
    }
}
