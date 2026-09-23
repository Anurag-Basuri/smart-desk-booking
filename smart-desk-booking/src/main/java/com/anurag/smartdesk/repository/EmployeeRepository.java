package com.anurag.smartdesk.repository;

import com.anurag.smartdesk.model.Employee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    // Used during login / authentication
    Optional<Employee> findByEmail(String email);

    boolean existsByEmail(String email);

    // Find all members of a team (for team neighbourhood placement)
    List<Employee> findByTeamId(Long teamId);
}
