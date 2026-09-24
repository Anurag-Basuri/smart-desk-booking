package com.anurag.smartdesk.service.impl;

import com.anurag.smartdesk.config.BookingMetrics;

import com.anurag.smartdesk.exception.AlreadyBookedException;
import com.anurag.smartdesk.exception.CutOffPassedException;
import com.anurag.smartdesk.exception.InvalidBookingDateException;
import com.anurag.smartdesk.exception.InvalidCheckInException;
import com.anurag.smartdesk.exception.NoDeskAvailableException;
import com.anurag.smartdesk.exception.QuotaExceededException;
import com.anurag.smartdesk.exception.ResourceNotFoundException;
import com.anurag.smartdesk.model.Booking;
import com.anurag.smartdesk.model.BookingStatus;
import com.anurag.smartdesk.model.Desk;
import com.anurag.smartdesk.model.Employee;
import com.anurag.smartdesk.model.Floor;
import com.anurag.smartdesk.model.TeamFloorQuota;
import com.anurag.smartdesk.repository.BookingRepository;
import com.anurag.smartdesk.repository.DeskRepository;
import com.anurag.smartdesk.repository.FloorRepository;
import com.anurag.smartdesk.repository.TeamFloorQuotaRepository;
import com.anurag.smartdesk.service.BookingService;
import com.anurag.smartdesk.service.EmployeeService;
import com.anurag.smartdesk.strategy.CenterBasedStrategy;
import com.anurag.smartdesk.strategy.DeskAllocationStrategy;
import com.anurag.smartdesk.strategy.TeamNeighbourhoodStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/*
 * The central booking engine of the application.
 *
 * This is where all the important business rules come together:
 *   - Date validation (no past dates, within advance window)
 *   - One booking per employee per day
 *   - Floor capacity headroom check
 *   - Team quota enforcement
 *   - Smart desk allocation (near teammates or near center)
 *   - Cancellation and check-in rules
 *   - No-show auto-release
 *
 * WHY IS THIS CLASS SO IMPORTANT?
 * In a real office with hundreds of people booking desks at the same time,
 * we need to guarantee that two people never accidentally book the same desk
 * and that teams don't exceed their desk limits. This class handles all
 * those concurrency problems using database locks.
 */
@Service
public class BookingServiceImpl implements BookingService {

    private static final Logger log = LoggerFactory.getLogger(
            BookingServiceImpl.class);

    // --- Configuration constants ---

    // All our offices operate in IST
    private static final ZoneId OFFICE_ZONE = ZoneId.of("Asia/Kolkata");

    // Employees can book up to 14 business days ahead
    private static final int MAX_ADVANCE_DAYS = 14;

    // Workday starts at 9 AM IST
    private static final LocalTime WORKDAY_START = LocalTime.of(9, 0);

    // 30 minutes grace period to check in after workday starts
    private static final int CHECK_IN_GRACE_MINUTES = 30;

    // 30 minutes grace period for same-day walk-in bookings
    private static final int WALK_IN_GRACE_MINUTES = 30;

    // --- Dependencies ---

    private final BookingRepository bookingRepository;
    private final DeskRepository deskRepository;
    private final FloorRepository floorRepository;
    private final TeamFloorQuotaRepository quotaRepository;
    private final EmployeeService employeeService;
    private final TeamNeighbourhoodStrategy neighbourhoodStrategy;
    private final CenterBasedStrategy centerStrategy;
    private final Clock clock;
    private final BookingMetrics metrics;

    public BookingServiceImpl(BookingRepository bookingRepository,
                              DeskRepository deskRepository,
                              FloorRepository floorRepository,
                              TeamFloorQuotaRepository quotaRepository,
                              EmployeeService employeeService,
                              TeamNeighbourhoodStrategy neighbourhoodStrategy,
                              CenterBasedStrategy centerStrategy,
                              Clock clock,
                              BookingMetrics metrics) {
        this.bookingRepository = bookingRepository;
        this.deskRepository = deskRepository;
        this.floorRepository = floorRepository;
        this.quotaRepository = quotaRepository;
        this.employeeService = employeeService;
        this.neighbourhoodStrategy = neighbourhoodStrategy;
        this.centerStrategy = centerStrategy;
        this.clock = clock;
        this.metrics = metrics;
    }

    // ========================================================================
    //  BOOK A HOT DESK
    // ========================================================================

    /*
     * Main booking flow. Here's what happens step by step:
     *
     *   1. Validate the date (not past, within advance window)
     *   2. Check employee doesn't already have a booking that day
     *   3. Lock the Floor row (prevents overbooking the floor)
     *   4. Lock the TeamFloorQuota row (prevents exceeding team limit)
     *   5. Check floor capacity headroom
     *   6. Check team quota
     *   7. Get available desks (fresh query while holding locks)
     *   8. Pick best desk using allocation strategy
     *   9. Create and save the booking
     *
     * WHY DO WE LOCK IN THIS ORDER?
     * If Thread A locks Floor then Quota, and Thread B also locks Floor
     * then Quota, they always compete in the same order — no deadlocks.
     * If they locked in different orders, they could wait for each other
     * forever (classic deadlock).
     *
     * Time Complexity:  O(D + T) where D = available desks, T = teammate bookings
     * Space Complexity: O(D) for the available desk list
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Booking bookHotDesk(Long employeeId, Long floorId,
                               LocalDate bookingDate) {

        metrics.incrementBookingRequests();

        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.now(clock.withZone(OFFICE_ZONE));

        // --- Step 1: Validate the booking date ---
        validateBookingDate(bookingDate, today);

        // --- Step 2: Check employee doesn't already have a booking ---
        Employee employee = employeeService.getEmployeeById(employeeId);

        bookingRepository.findActiveByEmployeeAndDate(employeeId, bookingDate)
                .ifPresent(existing -> {
                    throw new AlreadyBookedException(
                            "You already have a booking for " + bookingDate);
                });

        Long teamId = employee.getTeam().getId();

        // --- Step 3: Lock the Floor row ---
        // This is the parent lock. Any other booking attempt on this floor
        // will wait here until we finish. This prevents two threads from
        // both passing the capacity check and overbooking the floor.
        Floor floor = floorRepository.findByIdForUpdate(floorId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Floor not found with ID: " + floorId));

        // --- Step 4: Lock the TeamFloorQuota row ---
        TeamFloorQuota quota = quotaRepository
                .findByTeamIdAndFloorIdForUpdate(teamId, floorId)
                .orElseThrow(() -> new QuotaExceededException(
                        "Your team has no desk quota on this floor"));

        // --- Step 5: Check floor capacity headroom ---
        checkFloorCapacity(floor, bookingDate);

        // --- Step 6: Check team quota ---
        checkTeamQuota(quota, teamId, floorId, bookingDate);

        // --- Step 7: Get available desks (fresh query under lock) ---
        List<Desk> availableDesks = deskRepository
                .findAvailableHotDesks(floorId, bookingDate);

        if (availableDesks.isEmpty()) {
            throw new NoDeskAvailableException(
                    "No available desks on this floor for " + bookingDate);
        }

        // --- Step 8: Pick the best desk ---
        // Timer records how long the allocation algorithm takes
        DeskAllocationStrategy strategy = chooseStrategy(
                teamId, floorId, bookingDate);
        Desk chosenDesk = metrics.getAllocationLatency().record(() ->
                strategy.allocate(
                        availableDesks, floorId, teamId, bookingDate));

        // --- Step 9: Create and save the booking ---
        Booking booking = new Booking();
        booking.setDesk(chosenDesk);
        booking.setEmployee(employee);
        booking.setTeam(employee.getTeam());
        booking.setFloor(floor);
        booking.setBookingDate(bookingDate);
        booking.setStartTime(WORKDAY_START);
        booking.setEndTime(LocalTime.of(18, 0));
        booking.setStatus(BookingStatus.BOOKED);
        booking.setOwnerBooking(false);
        booking.setCheckInDeadline(
                computeCheckInDeadline(bookingDate, now));
        booking.setCreatedAt(now);
        booking.setUpdatedAt(now);

        Booking saved = bookingRepository.save(booking);

        log.info("Booking created: id={}, employee={}, desk={}, floor={}, date={}",
                saved.getId(), employeeId, chosenDesk.getId(),
                floorId, bookingDate);

        return saved;
    }

    // ========================================================================
    //  CANCEL A BOOKING
    // ========================================================================

    /*
     * Cancellation rules:
     *   1. Only the employee who made the booking can cancel it
     *   2. Booking must be in BOOKED status (can't cancel CHECKED_IN)
     *   3. Must cancel before the check-in deadline
     *
     * After cancellation, the desk immediately becomes available for others.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Booking cancelBooking(Long bookingId, Long employeeId) {
        Instant now = Instant.now(clock);

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Booking not found with ID: " + bookingId));

        // Only the booking owner can cancel
        if (!booking.getEmployee().getId().equals(employeeId)) {
            throw new ResourceNotFoundException(
                    "Booking not found with ID: " + bookingId);
        }

        // Can only cancel BOOKED bookings (not CHECKED_IN, CANCELLED, etc.)
        if (booking.getStatus() != BookingStatus.BOOKED) {
            throw new CutOffPassedException(
                    "Cannot cancel booking with status: "
                            + booking.getStatus());
        }

        // Must cancel before the check-in deadline
        if (!now.isBefore(booking.getCheckInDeadline())) {
            throw new CutOffPassedException(
                    "Cancellation deadline has passed");
        }

        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(now);
        booking.setUpdatedAt(now);

        log.info("Booking cancelled: id={}, employee={}",
                bookingId, employeeId);

        return bookingRepository.save(booking);
    }

    // ========================================================================
    //  CHECK IN
    // ========================================================================

    /*
     * Check-in rules:
     *   1. Only the booking owner can check in
     *   2. Booking must be in BOOKED status
     *   3. Must check in before the deadline
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Booking checkIn(Long bookingId, Long employeeId) {
        Instant now = Instant.now(clock);

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Booking not found with ID: " + bookingId));

        if (!booking.getEmployee().getId().equals(employeeId)) {
            throw new ResourceNotFoundException(
                    "Booking not found with ID: " + bookingId);
        }

        if (booking.getStatus() != BookingStatus.BOOKED) {
            throw new InvalidCheckInException(
                    "Cannot check in to booking with status: "
                            + booking.getStatus());
        }

        if (!now.isBefore(booking.getCheckInDeadline())) {
            throw new InvalidCheckInException(
                    "Check-in deadline has passed. "
                            + "Your booking was auto-released.");
        }

        booking.setStatus(BookingStatus.CHECKED_IN);
        booking.setCheckedInAt(now);
        booking.setUpdatedAt(now);

        log.info("Check-in successful: booking={}, employee={}",
                bookingId, employeeId);

        return bookingRepository.save(booking);
    }

    // ========================================================================
    //  BOOKING HISTORY
    // ========================================================================

    @Override
    public List<Booking> getBookingHistory(Long employeeId) {
        // Verify employee exists first
        employeeService.getEmployeeById(employeeId);
        return bookingRepository
                .findByEmployeeIdOrderByBookingDateDesc(employeeId);
    }

    // ========================================================================
    //  NO-SHOW SWEEP
    // ========================================================================

    /*
     * Called by a scheduler to mark expired bookings as NO_SHOW.
     *
     * This query is idempotent — running it twice produces the same result.
     * Multiple app instances can run it safely without distributed locks
     * because the SQL UPDATE only affects rows still in BOOKED status
     * with expired deadlines.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int sweepNoShows() {
        Instant now = Instant.now(clock);
        int count = bookingRepository.markNoShowBookings(now);

        if (count > 0) {
            log.info("No-show sweep: marked {} bookings as NO_SHOW", count);
            metrics.incrementNoShowReleases(count);
        }

        return count;
    }

    // ========================================================================
    //  PRIVATE HELPER METHODS
    // ========================================================================

    /*
     * Validates that the booking date is acceptable:
     *   - Not in the past
     *   - Not more than MAX_ADVANCE_DAYS into the future
     */
    private void validateBookingDate(LocalDate bookingDate, LocalDate today) {
        if (bookingDate.isBefore(today)) {
            throw new InvalidBookingDateException(
                    "Cannot book a desk in the past: " + bookingDate);
        }

        LocalDate maxDate = today.plusDays(MAX_ADVANCE_DAYS);
        if (bookingDate.isAfter(maxDate)) {
            throw new InvalidBookingDateException(
                    "Cannot book more than " + MAX_ADVANCE_DAYS
                            + " days ahead. Maximum date: " + maxDate);
        }
    }

    /*
     * Floor capacity headroom check.
     *
     * Formula:  hotActive + fixedNotReleased <= maxCapacity
     *
     * Where:
     *   hotActive       = number of active HOT desk bookings on this floor today
     *   fixedNotReleased = total fixed desks - fixed desks released (cancelled/no-show)
     *
     * Why "fixedNotReleased"?
     * Fixed desks belong to specific employees. Even if an owner hasn't
     * booked today, their desk still takes up a seat on the floor.
     * We only free up that headroom if the owner explicitly cancelled
     * or was marked as a no-show.
     */
    private void checkFloorCapacity(Floor floor, LocalDate date) {
        long hotActive = bookingRepository
                .countActiveHotBookingsByFloor(floor.getId(), date);

        long totalFixed = deskRepository
                .findByFloorIdAndIsActiveTrue(floor.getId())
                .stream()
                .filter(Desk::isFixed)
                .count();

        long fixedReleased = bookingRepository
                .countReleasedFixedDesks(floor.getId(), date);

        long fixedNotReleased = totalFixed - fixedReleased;
        long totalOccupied = hotActive + fixedNotReleased;

        if (totalOccupied >= floor.getMaxCapacity()) {
            throw new NoDeskAvailableException(
                    "Floor " + floor.getName()
                            + " has reached its capacity of "
                            + floor.getMaxCapacity() + " desks");
        }
    }

    /*
     * Team quota check — ensures a team doesn't book more hot desks
     * than their allocated limit on a floor.
     */
    private void checkTeamQuota(TeamFloorQuota quota, Long teamId,
                                Long floorId, LocalDate date) {

        long currentHotBookings = bookingRepository
                .countActiveHotBookingsByTeamAndFloor(
                        teamId, floorId, date);

        if (currentHotBookings >= quota.getMaxDesks()) {
            throw new QuotaExceededException(
                    "Team has reached its quota of "
                            + quota.getMaxDesks()
                            + " hot desks on this floor");
        }
    }

    /*
     * Decides which allocation strategy to use:
     *   - If teammates are already on this floor today → sit near them
     *   - If no teammates on this floor → sit near the center
     *
     * This is where the Strategy Pattern pays off. The BookingService
     * doesn't contain any distance calculation logic. It just picks
     * the right strategy and delegates.
     */
    private DeskAllocationStrategy chooseStrategy(Long teamId,
                                                  Long floorId,
                                                  LocalDate date) {

        List<Booking> teammateBookings = bookingRepository
                .findActiveByTeamAndFloorAndDate(teamId, floorId, date);

        if (teammateBookings.isEmpty()) {
            log.debug("No teammates on floor {} — using CenterBasedStrategy",
                    floorId);
            return centerStrategy;
        }

        log.debug("{} teammates on floor {} — using TeamNeighbourhoodStrategy",
                teammateBookings.size(), floorId);
        return neighbourhoodStrategy;
    }

    /*
     * Computes when the employee must check in by.
     *
     * Formula:
     *   deadline = max(workdayStart + grace, bookedAt + walkInGrace)
     *
     * Why max()?
     * - For advance bookings: deadline = 9:00 AM + 30 min = 9:30 AM
     * - For same-day walk-ins at 2 PM: deadline = 2:00 PM + 30 min = 2:30 PM
     *   (because 2:30 PM > 9:30 AM, the walk-in grace wins)
     *
     * This prevents absurd situations like booking at 3 PM but having
     * a 9:30 AM deadline that already expired.
     */
    private Instant computeCheckInDeadline(LocalDate bookingDate,
                                           Instant bookedAt) {

        // Workday-based deadline: 9:00 AM + 30 min = 9:30 AM on booking day
        Instant workdayDeadline = bookingDate
                .atTime(WORKDAY_START)
                .plusMinutes(CHECK_IN_GRACE_MINUTES)
                .atZone(OFFICE_ZONE)
                .toInstant();

        // Walk-in deadline: booking creation time + 30 min
        Instant walkInDeadline = bookedAt
                .plusSeconds(WALK_IN_GRACE_MINUTES * 60L);

        // Take the later of the two
        return workdayDeadline.isAfter(walkInDeadline)
                ? workdayDeadline
                : walkInDeadline;
    }
}
