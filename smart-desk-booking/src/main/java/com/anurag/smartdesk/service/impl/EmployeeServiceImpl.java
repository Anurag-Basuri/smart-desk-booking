package com.anurag.smartdesk.service.impl;

import com.anurag.smartdesk.exception.ResourceNotFoundException;
import com.anurag.smartdesk.model.Employee;
import com.anurag.smartdesk.repository.EmployeeRepository;
import com.anurag.smartdesk.service.EmployeeService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class EmployeeServiceImpl implements EmployeeService {

    private final EmployeeRepository employeeRepository;

    public EmployeeServiceImpl(EmployeeRepository employeeRepository) {
        this.employeeRepository = employeeRepository;
    }

    // Cached because employee → team mapping changes only on team transfers.
    // TTL = 15 minutes (configured in RedisConfig).
    @Override
    @Cacheable(value = "employee-team", key = "#id")
    public Employee getEmployeeById(Long id) {
        return employeeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee not found with ID: " + id));
    }

    @Override
    public Employee getEmployeeByEmail(String email) {
        return employeeRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee not found with email: " + email));
    }
}
