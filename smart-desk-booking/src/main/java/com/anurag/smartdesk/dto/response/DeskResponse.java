package com.anurag.smartdesk.dto.response;

import com.anurag.smartdesk.model.Desk;
import lombok.Getter;
import lombok.Setter;

// What the client receives when viewing the desk layout on a floor.
// Contains physical desk properties — NOT availability status.
// Availability is checked at booking time against the live database.
@Getter
@Setter
public class DeskResponse {

    private Long id;
    private int rowNumber;
    private int columnNumber;
    private String deskType;
    private boolean isActive;

    public static DeskResponse fromEntity(Desk desk) {
        DeskResponse response = new DeskResponse();
        response.setId(desk.getId());
        response.setRowNumber(desk.getRowNumber());
        response.setColumnNumber(desk.getColumnNumber());
        response.setDeskType(desk.getDeskType().name());
        response.setActive(desk.isActive());
        return response;
    }
}
