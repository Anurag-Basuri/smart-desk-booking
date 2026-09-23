package com.anurag.smartdesk.strategy;

import com.anurag.smartdesk.model.Booking;
import com.anurag.smartdesk.model.Desk;
import com.anurag.smartdesk.model.DeskCoordinate;
import com.anurag.smartdesk.repository.BookingRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Allocates a desk as close as possible to where teammates are already sitting.
 *
 * === THE IDEA (simple version) ===
 *
 * Imagine your team has 3 people already booked on Floor 2 today:
 *   - Alice sits at row=2, col=3
 *   - Bob   sits at row=2, col=5
 *   - Carol sits at row=4, col=3
 *
 * You want a desk. Which free desk should we give you?
 * → The one closest to Alice, Bob, and Carol combined.
 *
 * We compute a "centroid" (the average position) of your teammates:
 *   centroidRow = (2 + 2 + 4) / 3 = 2.67
 *   centroidCol = (3 + 5 + 3) / 3 = 3.67
 *
 * Then we pick the available desk nearest to (2.67, 3.67).
 *
 * === WHY SQUARED DISTANCE? ===
 *
 * Normal distance formula: sqrt((x1-x2)² + (y1-y2)²)
 * Squared distance:        (x1-x2)² + (y1-y2)²
 *
 * We skip the sqrt() because:
 *   - We only need to COMPARE distances (which is closer?), not measure them
 *   - If A² < B², then A < B — the ordering is identical
 *   - Avoids expensive floating-point square root operations
 *
 * Time Complexity:  O(T + D) where T = teammate bookings, D = available desks
 * Space Complexity: O(1) — we only store the centroid and current best desk
 */
@Component
public class TeamNeighbourhoodStrategy implements DeskAllocationStrategy {

    private final BookingRepository bookingRepository;

    public TeamNeighbourhoodStrategy(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    @Override
    public Desk allocate(List<Desk> availableDesks, Long floorId,
                         Long teamId, LocalDate bookingDate) {

        // Step 1: Find where teammates are sitting today on this floor
        List<Booking> teammateBookings = bookingRepository
                .findActiveByTeamAndFloorAndDate(teamId, floorId, bookingDate);

        // Step 2: Compute the centroid (average position) of all teammates
        DeskCoordinate anchor = computeTeamCentroid(teammateBookings);

        // Step 3: Pick the available desk closest to that centroid
        return findClosestDesk(availableDesks, anchor);
    }

    /*
     * Calculates the average (row, col) of all active teammate desk positions.
     *
     * If no teammates are on the floor yet, returns (0, 0).
     * This is fine because CenterBasedStrategy is meant to handle the
     * "first person on the floor" case — the BookingService decides
     * which strategy to call based on teammate count.
     *
     * Using integer division intentionally: we're working on a grid (desks
     * are at whole-number positions), so fractional precision doesn't help.
     */
    private DeskCoordinate computeTeamCentroid(List<Booking> teammateBookings) {
        if (teammateBookings.isEmpty()) {
            return new DeskCoordinate(0, 0);
        }

        int totalRow = 0;
        int totalCol = 0;

        for (Booking booking : teammateBookings) {
            totalRow += booking.getDesk().getRowNumber();
            totalCol += booking.getDesk().getColumnNumber();
        }

        int avgRow = totalRow / teammateBookings.size();
        int avgCol = totalCol / teammateBookings.size();

        return new DeskCoordinate(avgRow, avgCol);
    }

    /*
     * Scans all available desks and returns the one with the smallest
     * squared distance to the anchor point.
     *
     * Tie-breaking: If two desks have the same distance, the one with
     * the smaller ID wins. Since availableDesks is already ordered by
     * d.id ASC (from the repository query), the first one we encounter
     * at a given distance is automatically the tie-breaker winner.
     *
     * Time:  O(D) — single pass through available desks
     * Space: O(1) — only tracking best candidate
     */
    private Desk findClosestDesk(List<Desk> availableDesks,
                                 DeskCoordinate anchor) {
        Desk bestDesk = null;
        int bestDistance = Integer.MAX_VALUE;

        for (Desk desk : availableDesks) {
            DeskCoordinate deskPosition = new DeskCoordinate(
                    desk.getRowNumber(), desk.getColumnNumber());

            int distance = anchor.squaredDistanceTo(deskPosition);

            // Strict less-than ensures first-encountered desk wins ties
            // (deterministic because availableDesks is sorted by ID ASC)
            if (distance < bestDistance) {
                bestDistance = distance;
                bestDesk = desk;
            }
        }

        return bestDesk;
    }
}
