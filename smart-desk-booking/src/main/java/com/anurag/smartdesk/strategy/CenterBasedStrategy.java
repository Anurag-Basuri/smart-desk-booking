package com.anurag.smartdesk.strategy;

import com.anurag.smartdesk.model.Desk;
import com.anurag.smartdesk.model.DeskCoordinate;
import com.anurag.smartdesk.model.Floor;
import com.anurag.smartdesk.repository.FloorRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Allocates a desk near the center of the floor when no teammates are yet booked.
 *
 * Time Complexity:  O(D log D) where D = number of available desks
 * Space Complexity: O(D)
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
        List<DeskScore> ranked = rank(availableDesks, floorId, teamId, bookingDate);
        return ranked.isEmpty() ? null : ranked.get(0).desk();
    }

    @Override
    public List<DeskScore> rank(List<Desk> availableDesks, Long floorId,
                                Long teamId, LocalDate bookingDate) {
        if (availableDesks == null || availableDesks.isEmpty()) {
            return List.of();
        }

        Floor floor = floorRepository.findById(floorId)
                .orElseThrow(() -> new IllegalStateException("Floor not found: " + floorId));

        DeskCoordinate floorCenter = new DeskCoordinate(
                floor.getCenterRow(), floor.getCenterColumn());
        String reason = "Centrally located on " + floor.getName() + " (near center: row "
                + floor.getCenterRow() + ", col " + floor.getCenterColumn() + ")";

        List<DeskScore> scoredDesks = new ArrayList<>(availableDesks.size());
        for (Desk desk : availableDesks) {
            DeskCoordinate deskPos = new DeskCoordinate(desk.getRowNumber(), desk.getColumnNumber());
            int distance = floorCenter.squaredDistanceTo(deskPos);
            scoredDesks.add(new DeskScore(desk, distance, reason));
        }

        // Sort ascending by score, tie-break by desk ID ASC
        scoredDesks.sort(Comparator
                .comparingInt(DeskScore::score)
                .thenComparing(ds -> ds.desk().getId()));

        return scoredDesks;
    }
}
