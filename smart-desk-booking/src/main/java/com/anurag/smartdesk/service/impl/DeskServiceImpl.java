package com.anurag.smartdesk.service.impl;

import com.anurag.smartdesk.exception.ResourceNotFoundException;
import com.anurag.smartdesk.model.Desk;
import com.anurag.smartdesk.repository.DeskRepository;
import com.anurag.smartdesk.service.DeskService;
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

    @Override
    public List<Desk> getAvailableHotDesks(Long floorId, LocalDate date) {
        return deskRepository.findAvailableHotDesks(floorId, date);
    }

    @Override
    public List<Desk> getDesksOnFloor(Long floorId) {
        return deskRepository.findByFloorIdAndIsActiveTrue(floorId);
    }
}
