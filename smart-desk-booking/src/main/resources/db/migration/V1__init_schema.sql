-- Smart Desk Booking System - Flyway Database Migration
-- Description: Core Schema, Check Constraints, and Partial Unique Indexes

-- 1. Teams Table
CREATE TABLE teams (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. Employees Table
-- Role enum constraint: EMPLOYEE, TEAM_COORDINATOR, ADMIN
CREATE TABLE employees (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    team_id BIGINT NOT NULL REFERENCES teams(id) ON DELETE RESTRICT,
    timezone VARCHAR(50) NOT NULL DEFAULT 'Asia/Kolkata',
    role VARCHAR(30) NOT NULL DEFAULT 'EMPLOYEE',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_employee_role CHECK (role IN ('EMPLOYEE', 'TEAM_COORDINATOR', 'ADMIN'))
);

CREATE INDEX idx_employees_team_id ON employees(team_id);

-- 3. Floors Table
-- Geometric center (center_row, center_column) used for centroid seeding
CREATE TABLE floors (
    id BIGSERIAL PRIMARY KEY,
    floor_number INT NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    max_capacity INT NOT NULL,
    center_row INT NOT NULL,
    center_column INT NOT NULL,
    timezone VARCHAR(50) NOT NULL DEFAULT 'Asia/Kolkata',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_floor_capacity_positive CHECK (max_capacity > 0)
);

-- 4. Desks Table
-- Ownership Invariant:
-- - FIXED desk must have reserved_for_employee_id IS NOT NULL
-- - HOT desk must have reserved_for_employee_id IS NULL
CREATE TABLE desks (
    id BIGSERIAL PRIMARY KEY,
    floor_id BIGINT NOT NULL REFERENCES floors(id) ON DELETE CASCADE,
    row_number INT NOT NULL,
    column_number INT NOT NULL,
    desk_type VARCHAR(20) NOT NULL DEFAULT 'HOT',
    reserved_for_employee_id BIGINT REFERENCES employees(id) ON DELETE SET NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_floor_row_col UNIQUE (floor_id, row_number, column_number),
    CONSTRAINT chk_desk_type CHECK (desk_type IN ('HOT', 'FIXED')),
    CONSTRAINT chk_desk_fixed_owner CHECK (
        (desk_type = 'FIXED' AND reserved_for_employee_id IS NOT NULL) OR
        (desk_type = 'HOT' AND reserved_for_employee_id IS NULL)
    )
);

CREATE INDEX idx_desks_floor_id ON desks(floor_id);
CREATE INDEX idx_desks_reserved_employee ON desks(reserved_for_employee_id) WHERE reserved_for_employee_id IS NOT NULL;

-- 5. Team Floor Quotas Table
-- Defines zone/team cap per floor: Team T <= max_desks on Floor F
CREATE TABLE team_floor_quotas (
    id BIGSERIAL PRIMARY KEY,
    team_id BIGINT NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    floor_id BIGINT NOT NULL REFERENCES floors(id) ON DELETE CASCADE,
    max_desks INT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_team_floor_quota UNIQUE (team_id, floor_id),
    CONSTRAINT chk_quota_non_negative CHECK (max_desks >= 0)
);

CREATE INDEX idx_quotas_team_floor ON team_floor_quotas(team_id, floor_id);

-- 6. Bookings Table
-- Denormalized team_id & floor_id enable fast index-only scans for aggregate checks.
-- is_owner_booking separates owner reservations from hot capacity/quota.
CREATE TABLE bookings (
    id BIGSERIAL PRIMARY KEY,
    desk_id BIGINT NOT NULL REFERENCES desks(id) ON DELETE RESTRICT,
    employee_id BIGINT NOT NULL REFERENCES employees(id) ON DELETE RESTRICT,
    team_id BIGINT NOT NULL REFERENCES teams(id) ON DELETE RESTRICT,
    floor_id BIGINT NOT NULL REFERENCES floors(id) ON DELETE RESTRICT,
    booking_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    check_in_deadline TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'BOOKED',
    is_owner_booking BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    checked_in_at TIMESTAMP WITH TIME ZONE,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_booking_status CHECK (status IN ('BOOKED', 'CHECKED_IN', 'CANCELLED', 'NO_SHOW')),
    CONSTRAINT chk_booking_time_window CHECK (start_time < end_time)
);

-- Partial Unique Indexes for Business Logic Enforcement
CREATE UNIQUE INDEX uq_active_desk_day 
ON bookings (desk_id, booking_date) 
WHERE status IN ('BOOKED', 'CHECKED_IN');

-- Partial Unique Index for Employee Booking Constraint:
CREATE UNIQUE INDEX uq_active_employee_day 
ON bookings (employee_id, booking_date) 
WHERE status IN ('BOOKED', 'CHECKED_IN');

-- Composite partial index for fast team quota enforcement (hot bookings only)
CREATE INDEX idx_bookings_team_quota 
ON bookings (team_id, floor_id, booking_date) 
WHERE status IN ('BOOKED', 'CHECKED_IN') AND is_owner_booking = FALSE;

-- Composite partial index for fast floor capacity headroom enforcement (hot bookings only):
CREATE INDEX idx_bookings_floor_capacity 
ON bookings (floor_id, booking_date) 
WHERE status IN ('BOOKED', 'CHECKED_IN') AND is_owner_booking = FALSE;

-- Index for timezone-proof background no-show auto-release sweeps:
CREATE INDEX idx_bookings_noshow_sweep 
ON bookings (status, check_in_deadline) 
WHERE status = 'BOOKED';

-- Supporting index for employee booking history lookups:
CREATE INDEX idx_bookings_employee_date 
ON bookings (employee_id, booking_date DESC);
