package com.anurag.smartdesk.dto.response;

import com.anurag.smartdesk.model.Floor;
import lombok.Getter;
import lombok.Setter;

// What the client receives when viewing floor information.
@Getter
@Setter
public class FloorResponse {

    private Long id;
    private int floorNumber;
    private String name;
    private int maxCapacity;
    private boolean isActive;

    public static FloorResponse fromEntity(Floor floor) {
        FloorResponse response = new FloorResponse();
        response.setId(floor.getId());
        response.setFloorNumber(floor.getFloorNumber());
        response.setName(floor.getName());
        response.setMaxCapacity(floor.getMaxCapacity());
        response.setActive(floor.isActive());
        return response;
    }
}
