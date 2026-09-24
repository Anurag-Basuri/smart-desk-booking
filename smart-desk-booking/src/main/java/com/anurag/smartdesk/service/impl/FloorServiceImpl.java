package com.anurag.smartdesk.service.impl;

import com.anurag.smartdesk.exception.ResourceNotFoundException;
import com.anurag.smartdesk.model.Floor;
import com.anurag.smartdesk.repository.FloorRepository;
import com.anurag.smartdesk.service.FloorService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FloorServiceImpl implements FloorService {

    private final FloorRepository floorRepository;

    public FloorServiceImpl(FloorRepository floorRepository) {
        this.floorRepository = floorRepository;
    }

    // Cached because floor metadata (name, capacity, center position)
    // changes only when an admin updates the floor — very rare.
    // Cache key = floor ID, TTL = 30 minutes (configured in RedisConfig).
    @Override
    @Cacheable(value = "floors", key = "#id")
    public Floor getFloorById(Long id) {
        return floorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Floor not found with ID: " + id));
    }

    @Override
    @Cacheable(value = "floors", key = "'all-active'")
    public List<Floor> getAllActiveFloors() {
        return floorRepository.findByIsActiveTrue();
    }
}
