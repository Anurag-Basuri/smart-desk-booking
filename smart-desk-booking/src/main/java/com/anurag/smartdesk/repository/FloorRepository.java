package com.anurag.smartdesk.repository;

import com.anurag.smartdesk.model.Floor;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface FloorRepository extends JpaRepository<Floor, Long> {

    List<Floor> findByIsActiveTrue();

    // Acquires a PostgreSQL row-level lock (SELECT ... FOR UPDATE) on a floor.
    // This is the "parent row serialization" strategy described in our architecture
    // — it prevents overbooking beyond floor capacity or team quota limits.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Floor f WHERE f.id = :id")
    Optional<Floor> findByIdForUpdate(Long id);
}
