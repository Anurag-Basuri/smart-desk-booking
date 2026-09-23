package com.anurag.smartdesk.strategy;

import com.anurag.smartdesk.model.Desk;

import java.time.LocalDate;
import java.util.List;

/**
 * Strategy Pattern interface for desk allocation.
 *
 * Why Strategy Pattern?
 * Different scenarios need different placement logic:
 *   - When teammates are already sitting on the floor → place new person NEAR them
 *   - When nobody from the team is on the floor yet → place person NEAR the center
 *
 * By coding to this interface (not a concrete class), the BookingService never
 * needs to know which algorithm is running. We can swap strategies or add new
 * ones without changing any booking logic.
 *
 * Interview Justification:
 *   This is the Strategy design pattern (GoF). It demonstrates polymorphism
 *   and the Open/Closed Principle — the system is open for new allocation
 *   algorithms but closed for modification of existing booking logic.
 */
public interface DeskAllocationStrategy {

    // Pick the best desk from a list of available candidates.
    Desk allocate(List<Desk> availableDesks, Long floorId,
                  Long teamId, LocalDate bookingDate);
}
