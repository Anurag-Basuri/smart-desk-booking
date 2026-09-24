package com.anurag.smartdesk;

import com.anurag.smartdesk.config.JwtService;
import com.anurag.smartdesk.model.Desk;
import com.anurag.smartdesk.model.Employee;
import com.anurag.smartdesk.model.Floor;
import com.anurag.smartdesk.model.Role;
import com.anurag.smartdesk.model.Team;
import com.anurag.smartdesk.model.TeamFloorQuota;
import com.anurag.smartdesk.repository.BookingRepository;
import com.anurag.smartdesk.repository.DeskRepository;
import com.anurag.smartdesk.repository.EmployeeRepository;
import com.anurag.smartdesk.repository.FloorRepository;
import com.anurag.smartdesk.repository.TeamFloorQuotaRepository;
import com.anurag.smartdesk.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Integration test proving the double-booking race condition is prevented.
 *
 * <h3>What this test proves:</h3>
 * <ol>
 *   <li>Two threads fire simultaneously at <code>POST /api/bookings</code>,
 *       both targeting the <b>same desk</b> on the <b>same date</b>.</li>
 *   <li>Exactly <b>one thread succeeds</b> (HTTP 201 CREATED).</li>
 *   <li>Exactly <b>one thread is rejected</b> (HTTP 409 CONFLICT).</li>
 * </ol>
 *
 * <h3>How concurrency safety is enforced in the system:</h3>
 * <ul>
 *   <li><b>Pessimistic parent row locking</b>: Booking transactions
 *       acquire <code>SELECT ... FOR UPDATE</code> on the Floor row
 *       first, serializing access.</li>
 *   <li><b>PostgreSQL partial unique indexes</b>: <code>uq_active_desk_day</code>
 *       and <code>uq_active_employee_day</code> provide defense-in-depth
 *       at the storage engine level.</li>
 * </ul>
 *
 * <p>Time complexity: O(1) per thread – each thread executes a single
 * booking request against fixed, pre-seeded data.</p>
 * <p>Space complexity: O(1) – the test seeds a fixed, small number
 * of entities regardless of system size.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class BookingConcurrencyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private FloorRepository floorRepository;

    @Autowired
    private DeskRepository deskRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private TeamFloorQuotaRepository quotaRepository;

    private Long targetDeskId;
    private String tokenAlice;
    private String tokenBob;
    private LocalDate bookingDate;

    /**
     * Seeds the database with a minimal dataset for the concurrency test:
     * one team, two employees, one floor with exactly one desk.
     *
     * <p>Using a unique future date avoids collisions with any existing
     * bookings from manual testing or prior test runs.</p>
     */
    @BeforeEach
    void setUp() {
        /* Use a future date within the 14-day advance booking window
         * that won't collide with manual tests. The service validates
         * MAX_ADVANCE_DAYS = 14, so we use 7 days ahead to stay safe. */
        bookingDate = LocalDate.now().plusDays(7);

        // Clean up any bookings on this date from previous runs
        bookingRepository.findAll().stream()
                .filter(b -> b.getBookingDate().equals(bookingDate))
                .forEach(b -> bookingRepository.delete(b));

        // Seed team
        Team team = teamRepository.findByName("ConcurrencyTestTeam")
                .orElseGet(() -> {
                    Team t = new Team("ConcurrencyTestTeam");
                    return teamRepository.save(t);
                });

        // Seed Alice
        Employee alice = employeeRepository.findByEmail(
                "alice.conctest@company.com").orElseGet(() -> {
            Employee e = new Employee();
            e.setName("Alice ConcTest");
            e.setEmail("alice.conctest@company.com");
            e.setPasswordHash("$2a$12$placeholder");
            e.setTeam(team);
            e.setRole(Role.EMPLOYEE);
            return employeeRepository.save(e);
        });

        // Seed Bob
        Employee bob = employeeRepository.findByEmail(
                "bob.conctest@company.com").orElseGet(() -> {
            Employee e = new Employee();
            e.setName("Bob ConcTest");
            e.setEmail("bob.conctest@company.com");
            e.setPasswordHash("$2a$12$placeholder");
            e.setTeam(team);
            e.setRole(Role.EMPLOYEE);
            return employeeRepository.save(e);
        });

        /* Reuse an existing floor if available, otherwise create one.
         * The test only requires a floor with at least one active
         * HOT desk to demonstrate the race condition. */
        Floor floor = floorRepository.findAll().stream()
                .findFirst()
                .orElseGet(() -> {
                    Floor f = new Floor();
                    f.setFloorNumber(99);
                    f.setName("Concurrency Test Floor");
                    f.setMaxCapacity(50);
                    f.setCenterRow(3);
                    f.setCenterColumn(3);
                    return floorRepository.save(f);
                });

        /* Ensure the test team has a desk quota on this floor.
         * Without this, the service throws QuotaExceededException (422)
         * because every booking transaction checks team quota. */
        quotaRepository.findByTeamIdAndFloorId(
                team.getId(), floor.getId())
                .orElseGet(() -> {
                    TeamFloorQuota q = new TeamFloorQuota();
                    q.setTeam(team);
                    q.setFloor(floor);
                    q.setMaxDesks(10);
                    return quotaRepository.save(q);
                });

        /* Find or create a single HOT desk on this floor.
         * We pick the first available desk to target. */
        Desk targetDesk = deskRepository
                .findByFloorIdAndIsActiveTrue(floor.getId())
                .stream()
                .filter(d -> !d.isFixed())
                .findFirst()
                .orElseGet(() -> {
                    Desk d = new Desk();
                    d.setFloor(floor);
                    d.setRowNumber(1);
                    d.setColumnNumber(1);
                    return deskRepository.save(d);
                });

        targetDeskId = targetDesk.getId();

        // Generate JWT tokens for both employees
        tokenAlice = jwtService.generateToken(
                alice.getId(), alice.getEmail(),
                alice.getRole().name());

        tokenBob = jwtService.generateToken(
                bob.getId(), bob.getEmail(),
                bob.getRole().name());
    }

    /**
     * Proves that two simultaneous booking requests for the same desk
     * on the same date result in exactly one success and one rejection.
     *
     * <h3>Mechanism:</h3>
     * <ol>
     *   <li>A {@link CountDownLatch} ensures both threads fire their
     *       HTTP requests at the <b>exact same instant</b>.</li>
     *   <li>An {@link ExecutorService} pool of 2 threads simulates
     *       two concurrent users.</li>
     *   <li>Both threads target the same <code>deskId</code> and
     *       <code>bookingDate</code>, triggering the pessimistic
     *       lock contention path.</li>
     * </ol>
     *
     * <p>Time: O(1). Space: O(1).</p>
     */
    @Test
    @DisplayName("Two simultaneous bookings for the same desk → "
            + "exactly one wins, the other gets 409 CONFLICT")
    void concurrentBookingSameDeskOnlyOneWins() throws Exception {

        // JSON request body targeting the specific desk
        Floor floor = deskRepository.findById(targetDeskId)
                .orElseThrow().getFloor();

        String requestBody = """
                {
                    "floorId": %d,
                    "bookingDate": "%s",
                    "deskId": %d
                }
                """.formatted(floor.getId(), bookingDate, targetDeskId);

        // Gate that holds both threads until they are both ready
        CountDownLatch startGate = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        List<Future<MvcResult>> futures = new ArrayList<>();

        /* Thread 1: Alice tries to book the target desk */
        futures.add(executor.submit(() -> {
            startGate.await(); // Wait until both threads are ready
            return mockMvc.perform(
                    post("/api/bookings")
                            .header("Authorization",
                                    "Bearer " + tokenAlice)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody)
            ).andReturn();
        }));

        /* Thread 2: Bob tries to book the SAME target desk */
        futures.add(executor.submit(() -> {
            startGate.await(); // Wait until both threads are ready
            return mockMvc.perform(
                    post("/api/bookings")
                            .header("Authorization",
                                    "Bearer " + tokenBob)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody)
            ).andReturn();
        }));

        // Release both threads simultaneously
        startGate.countDown();

        // Collect HTTP status codes from both responses
        List<Integer> statusCodes = new ArrayList<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get();
            statusCodes.add(result.getResponse().getStatus());
        }

        executor.shutdown();

        /* ── Assertions ───────────────────────────────────────────
         * Exactly one thread should get HTTP 201 (CREATED),
         * and the other should get HTTP 409 (CONFLICT).
         *
         * This proves:
         *  1. Pessimistic locking serialized access correctly
         *  2. The partial unique index blocked the second insert
         *  3. Zero double-bookings occurred
         * ─────────────────────────────────────────────────────── */
        long successCount = statusCodes.stream()
                .filter(s -> s == 201).count();
        long conflictCount = statusCodes.stream()
                .filter(s -> s == 409).count();

        assertThat(successCount)
                .as("Exactly one booking should succeed (HTTP 201)")
                .isEqualTo(1);

        assertThat(conflictCount)
                .as("Exactly one booking should be rejected "
                        + "(HTTP 409 CONFLICT)")
                .isEqualTo(1);

        // Verify only ONE booking actually exists in the database
        long activeBookingsForDesk = bookingRepository.findAll()
                .stream()
                .filter(b -> b.getDesk().getId().equals(targetDeskId))
                .filter(b -> b.getBookingDate().equals(bookingDate))
                .filter(b -> b.isActive())
                .count();

        assertThat(activeBookingsForDesk)
                .as("Only one active booking should exist for "
                        + "desk %d on %s", targetDeskId, bookingDate)
                .isEqualTo(1);
    }

    /**
     * Proves that the same employee cannot create two active bookings
     * on the same calendar day, even for different desks.
     *
     * <p>The <code>uq_active_employee_day</code> partial unique index
     * enforces the one-booking-per-employee-per-day invariant at the
     * database level.</p>
     *
     * <p>Time: O(1). Space: O(1).</p>
     */
    @Test
    @DisplayName("Same employee booking twice on the same day → "
            + "second request gets 409 CONFLICT")
    void sameEmployeeCannotDoubleBookSameDay() throws Exception {

        Floor floor = deskRepository.findById(targetDeskId)
                .orElseThrow().getFloor();

        String requestBody = """
                {
                    "floorId": %d,
                    "bookingDate": "%s"
                }
                """.formatted(floor.getId(), bookingDate);

        // First booking should succeed
        MvcResult firstResult = mockMvc.perform(
                post("/api/bookings")
                        .header("Authorization",
                                "Bearer " + tokenAlice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
        ).andReturn();

        assertThat(firstResult.getResponse().getStatus())
                .as("First booking should succeed")
                .isEqualTo(201);

        // Second booking by the SAME employee on the SAME day
        MvcResult secondResult = mockMvc.perform(
                post("/api/bookings")
                        .header("Authorization",
                                "Bearer " + tokenAlice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
        ).andReturn();

        assertThat(secondResult.getResponse().getStatus())
                .as("Second booking by same employee on same day "
                        + "should be rejected (HTTP 409)")
                .isEqualTo(409);
    }
}
