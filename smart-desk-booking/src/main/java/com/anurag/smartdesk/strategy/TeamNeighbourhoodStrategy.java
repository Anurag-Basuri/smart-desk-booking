package com.anurag.smartdesk.strategy;

import com.anurag.smartdesk.model.Booking;
import com.anurag.smartdesk.model.Desk;
import com.anurag.smartdesk.model.DeskCoordinate;
import com.anurag.smartdesk.repository.BookingRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Allocates a desk as close as possible to where teammates are already sitting.
 *
 * Time Complexity:  O(T + D log D) where T = teammate bookings, D = available desks
 * Space Complexity: O(D)
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
        List<DeskScore> ranked = rank(availableDesks, floorId, teamId, bookingDate);
        return ranked.isEmpty() ? null : ranked.get(0).desk();
    }

    @Override
    public List<DeskScore> rank(List<Desk> availableDesks, Long floorId,
                                Long teamId, LocalDate bookingDate) {
        if (availableDesks == null || availableDesks.isEmpty()) {
            return List.of();
        }

        // Step 1: Find where teammates are sitting today on this floor
        List<Booking> teammateBookings = bookingRepository
                .findActiveByTeamAndFloorAndDate(teamId, floorId, bookingDate);

        // Step 2: Compute centroid of teammates
        DeskCoordinate anchor = computeTeamCentroid(teammateBookings);
        String reason = "Clustered near " + teammateBookings.size() + " active teammate(s) (centroid: row "
                + anchor.row() + ", col " + anchor.column() + ")";

        // Step 3: Score each available desk by squared distance to anchor
        List<DeskScore> scoredDesks = new ArrayList<>(availableDesks.size());
        for (Desk desk : availableDesks) {
            DeskCoordinate deskPos = new DeskCoordinate(desk.getRowNumber(), desk.getColumnNumber());
            int distance = anchor.squaredDistanceTo(deskPos);
            scoredDesks.add(new DeskScore(desk, distance, reason));
        }

        // Step 4: Sort ascending by score, tie-break by desk ID ASC (deterministic)
        scoredDesks.sort(Comparator
                .comparingInt(DeskScore::score)
                .thenComparing(ds -> ds.desk().getId()));

        return scoredDesks;
    }

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
}
