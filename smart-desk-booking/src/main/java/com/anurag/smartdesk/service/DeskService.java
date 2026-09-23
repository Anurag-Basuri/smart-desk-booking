package com.anurag.smartdesk.service;

import com.anurag.smartdesk.model.Desk;

import java.time.LocalDate;
import java.util.List;

// Service for desk-related operations.
public interface DeskService {

    Desk getDeskById(Long id);

    // Returns all HOT desks on a floor that are free on the given date.
    List<Desk> getAvailableHotDesks(Long floorId, LocalDate date);

    // Returns all active desks on a floor (for layout display).
    List<Desk> getDesksOnFloor(Long floorId);
}
