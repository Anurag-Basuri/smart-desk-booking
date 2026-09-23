package com.anurag.smartdesk.repository;

import com.anurag.smartdesk.model.TeamFloorQuota;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface TeamFloorQuotaRepository extends JpaRepository<TeamFloorQuota, Long> {

    Optional<TeamFloorQuota> findByTeamIdAndFloorId(Long teamId, Long floorId);

    // Lock the quota row during booking to prevent two concurrent requests
    // from both passing the quota check and exceeding the team's desk limit.
    // This is the second lock in the hierarchy: Floor lock first, then Quota lock.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM TeamFloorQuota q WHERE q.team.id = :teamId AND q.floor.id = :floorId")
    Optional<TeamFloorQuota> findByTeamIdAndFloorIdForUpdate(Long teamId, Long floorId);
}
