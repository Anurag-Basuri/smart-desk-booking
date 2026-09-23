package com.anurag.smartdesk.strategy;

import com.anurag.smartdesk.model.Desk;
import com.anurag.smartdesk.model.DeskCoordinate;
import com.anurag.smartdesk.model.Floor;
import com.anurag.smartdesk.repository.FloorRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Allocates a desk near the center of the floor.
 *
 * === WHEN IS THIS USED? ===
 *
 * When the employee's team has NO teammates already sitting on this floor
 * for the requested date. Since there's nobody to sit "near", we place
 * them near the floor's center instead.
 *
 * === WHY THE CENTER? ===
 *
 * The center of the floor is the position that minimizes the average
 * distance to ALL other desks. This is a good default because:
 *   1. It keeps the new team member centrally located for collaboration
 *   2. As more teammates book later, they'll cluster around this seed
 *      position (TeamNeighbourhoodStrategy takes over for subsequent bookings)
 *   3. It avoids edge/corner placement that would waste walking distance
 *
 * === HOW DOES IT WORK? ===
 *
 * Each Floor stores a pre-configured (centerRow, centerColumn).
 * This strategy simply finds the available desk closest to that center.
 *
 * Time Complexity:  O(D) where D = number of available desks
 * Space Complexity: O(1) — only tracking the best candidate
 */
@Component
public class CenterBasedStrategy implements DeskAllocationStrategy {

    private final FloorRepository floorRepository;

    public CenterBasedStrategy(FloorRepository floorRepository) {
        this.floorRepository = floorRepository;
    }

    @Override
    public Desk allocate(List<Desk> availableDesks, Long floorId,
                         Long teamId, LocalDate bookingDate) {

        // Step 1: Look up where the center of this floor is
        Floor floor = floorRepository.findById(floorId)
                .orElseThrow(() -> new IllegalStateException(
                        "Floor not found: " + floorId));

        DeskCoordinate floorCenter = new DeskCoordinate(
                floor.getCenterRow(), floor.getCenterColumn());

        // Step 2: Find the desk closest to the floor center
        return findClosestDesk(availableDesks, floorCenter);
    }

    /*
     * Same logic as TeamNeighbourhoodStrategy.findClosestDesk() —
     * scans all candidates, picks the one with smallest squared distance
     * to the center, with deterministic tie-breaking via desk ID order.
     *
     * Note: We intentionally did NOT extract this into a shared utility class.
     * The method is only 10 lines and duplicating it keeps each strategy
     * completely self-contained and easy to read/modify independently.
     * If we add a third strategy later, we can refactor then (YAGNI principle).
     *
     * Time:  O(D) — single pass
     * Space: O(1)
     */
    private Desk findClosestDesk(List<Desk> availableDesks,
                                 DeskCoordinate center) {
        Desk bestDesk = null;
        int bestDistance = Integer.MAX_VALUE;

        for (Desk desk : availableDesks) {
            DeskCoordinate deskPosition = new DeskCoordinate(
                    desk.getRowNumber(), desk.getColumnNumber());

            int distance = center.squaredDistanceTo(deskPosition);

            if (distance < bestDistance) {
                bestDistance = distance;
                bestDesk = desk;
            }
        }

        return bestDesk;
    }
}
