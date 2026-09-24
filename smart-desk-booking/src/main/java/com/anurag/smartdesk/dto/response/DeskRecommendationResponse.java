package com.anurag.smartdesk.dto.response;

import com.anurag.smartdesk.model.Desk;
import lombok.Getter;
import lombok.Setter;

// DTO representing a recommended seat for an employee or team.
// Includes spatial ranking and reasoning to help users pick the best desk.
@Getter
@Setter
public class DeskRecommendationResponse {

    private Long deskId;
    private int rowNumber;
    private int columnNumber;
    private String deskType;
    private int rank;
    private int distanceScore;
    private String recommendationReason;

    public static DeskRecommendationResponse fromDesk(Desk desk, int rank, int distanceScore, String reason) {
        DeskRecommendationResponse response = new DeskRecommendationResponse();
        response.setDeskId(desk.getId());
        response.setRowNumber(desk.getRowNumber());
        response.setColumnNumber(desk.getColumnNumber());
        response.setDeskType(desk.getDeskType().name());
        response.setRank(rank);
        response.setDistanceScore(distanceScore);
        response.setRecommendationReason(reason);
        return response;
    }
}
