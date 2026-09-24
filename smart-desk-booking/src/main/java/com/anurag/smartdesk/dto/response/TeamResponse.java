package com.anurag.smartdesk.dto.response;

import com.anurag.smartdesk.model.Team;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TeamResponse {
    private Long id;
    private String name;

    public static TeamResponse fromEntity(Team team) {
        TeamResponse response = new TeamResponse();
        response.setId(team.getId());
        response.setName(team.getName());
        return response;
    }
}
