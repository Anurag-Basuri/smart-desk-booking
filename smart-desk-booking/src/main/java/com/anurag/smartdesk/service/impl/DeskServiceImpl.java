package com.anurag.smartdesk.service.impl;

import com.anurag.smartdesk.exception.ResourceNotFoundException;
import com.anurag.smartdesk.model.Desk;
import com.anurag.smartdesk.repository.DeskRepository;
import com.anurag.smartdesk.service.DeskService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class DeskServiceImpl implements DeskService {

    private final DeskRepository deskRepository;

    public DeskServiceImpl(DeskRepository deskRepository) {
        this.deskRepository = deskRepository;
    }

    @Override
    public Desk getDeskById(Long id) {
        return deskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Desk not found with ID: " + id));
    }

    // NOT cached! Availability changes every second as people book/cancel.
    // Caching this would cause double-bookings.
    @Override
    public List<Desk> getAvailableHotDesks(Long floorId, LocalDate date) {
        return deskRepository.findAvailableHotDesks(floorId, date);
    }

    // Cached because desk layout (positions, types) only changes when
    // an admin adds or removes physical desks — very rare.
    // We cache the LAYOUT, not the AVAILABILITY.
    @Override
    @Cacheable(value = "floor-desks", key = "#floorId")
    public List<Desk> getDesksOnFloor(Long floorId) {
        return deskRepository.findByFloorIdAndIsActiveTrue(floorId);
    }
}
