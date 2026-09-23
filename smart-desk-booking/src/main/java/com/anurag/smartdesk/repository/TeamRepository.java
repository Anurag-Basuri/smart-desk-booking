package com.anurag.smartdesk.repository;

import com.anurag.smartdesk.model.Team;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {

    // Used for Login flow. If the team name does not match, the user cannot login.
    Optional<Team> findByName(String name);

    // Used to check if a team with the same name already exists.
    boolean existsByName(String name);
}
