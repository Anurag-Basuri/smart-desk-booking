package com.anurag.smartdesk.service.impl;

import com.anurag.smartdesk.exception.ResourceNotFoundException;
import com.anurag.smartdesk.model.Team;
import com.anurag.smartdesk.repository.TeamRepository;
import com.anurag.smartdesk.service.TeamService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class TeamServiceImpl implements TeamService {

    private final TeamRepository teamRepository;

    public TeamServiceImpl(TeamRepository teamRepository) {
        this.teamRepository = teamRepository;
    }

    // Cached because team metadata (name) changes very rarely.
    // TTL = 15 minutes (configured in RedisConfig).
    @Override
    @Cacheable(value = "teams", key = "#id")
    public Team getTeamById(Long id) {
        return teamRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Team not found with ID: " + id));
    }
}
