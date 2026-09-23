package com.anurag.smartdesk.service;

import com.anurag.smartdesk.model.Employee;

// Service for employee-related operations.
public interface EmployeeService {

    Employee getEmployeeById(Long id);

    Employee getEmployeeByEmail(String email);
}
