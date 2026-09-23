# Smart Desk Booking - Agent Guidelines & Rules

This project is a high-concurrency Spring Boot application implementing an intelligent desk booking and team neighborhood allocation system. All code and architectural decisions must satisfy standard enterprise/industry-grade engineering standards and the assignment evaluation criteria.

---

## 1. Java Naming Conventions

All code written or edited by agents must adhere strictly to these conventions:

| Identifier Type | Case Style | Example | Rule / Intent |
| :--- | :--- | :--- | :--- |
| **Classes & Interfaces** | `PascalCase` | `UserAccount`, `PaymentProcessor`, `DeskAllocationService` | Use nouns or noun phrases representing concepts or entities. |
| **Methods** | `camelCase` | `calculateTotal()`, `fetchData()`, `allocateDesk()` | Use verbs or verb phrases that describe actions. |
| **Variables & Fields** | `camelCase` | `totalAmount`, `isPremiumUser`, `bookingRepository` | Use meaningful nouns; avoid single letters except in loops (`i`, `j`). |
| **Constants** | `UPPER_SNAKE_CASE` | `MAX_LOGIN_ATTEMPTS`, `DEFAULT_FLOOR_CAPACITY` | Must use `static final` modifiers. |
| **Packages** | `lowercase` | `com.anurag.smartdesk.booking`, `com.company.project.module` | Reverse internet domain name, completely lowercase. |

---

## 2. Formatting & Layout

- **Braces**: Standard Egyptian brackets style (`{` stays on the same line; `}` closes on a new line).
- **Indentation**: 4 spaces per block level (Oracle standard). Never mix tabs and spaces.
- **Line Scope**: Limit lines to 80–100 characters to prevent horizontal scrolling.
- **Statement Density**: Write exactly one statement per line.

---

## 3. Local Variables & Scope

- **Declare and Initialize Together**: Never declare variables at the top of a method if they are not used until later. Declare variables in the smallest possible scope right where they are first needed.
- **Encapsulation**:
  - Always keep instance variables `private`.
  - Expose fields only when necessary via getters and setters.
  - For boolean fields, use `is...` rather than `get...` (e.g., `isActive()`, `isAssigned()`, not `getIsActive()`).

---

## 4. Code Structure & Commenting

- **Keep Methods Short**: Methods should fit on a single screen without scrolling (ideally under 20–50 lines) and strictly adhere to the Single Responsibility Principle (do one thing well).
- **Avoid "Magic" Values**: Never hardcode raw numbers or strings inside business logic. Assign them to clearly named `static final` constants.
- **Comments & Documentation**:
  - Comments must explain **why** a specific path, formula, or workaround was chosen—never merely restate what readable code already shows.
  - **Normal explanations**: Use single-line `//` comments.
  - **Longer algorithmic explanations**: Use block `/* ... */` comments (e.g., Euclidean distance calculations, centroid tie-breakers, concurrency state machines).
  - **Public APIs**: Use Javadoc (`/** ... */`) on all public service interfaces, controllers, and domain models.

---

## 5. Industry-Grade Package Structure & Layering

The codebase must follow a clean, modular, and decoupled layered architecture:

```
com.anurag.smartdeskbooking
├── config/              // Security, Redis, Actuator, OpenApi, Async configs
├── controller/          // REST API controllers (HTTP mapping, input validation)
├── dto/                 // Data Transfer Objects (Request/Response contracts)
│   ├── request/
│   └── response/
├── exception/           // Domain exceptions, error response DTOs, @RestControllerAdvice
├── model/               // JPA Entities, Value Objects, Enums, Auditing
├── repository/          // Spring Data JPA repositories with explicit pessimistic locks
├── service/             // Business logic interfaces & implementations
│   └── impl/
├── strategy/            // Strategy pattern implementations (desk allocation)
└── util/                // Mathematical helpers, timezone utilities, constants
```

### Layer Separation Directives
1. **Controllers**: Never call Repositories directly. Always delegate to Services. Keep controllers slim (`@Valid` request mapping and response wrapping).
2. **DTOs vs Entities**: Entities must never leak out through public API endpoints. Always convert Entities to DTOs.
3. **Services**: Interface-driven design (e.g., `BookingService` interface with `BookingServiceImpl`). Annotate transactional methods with `@Transactional`.

---

## 6. Implementation Directives for Evaluation Criteria (Plus Points)

The agent must strictly build each component with these 8 evaluation criteria in mind:

### 1. Authentication & Authorization
- Implement Spring Security with stateless JSON Web Tokens (JWT) or secure bearer tokens.
- Role-based authorization: `ROLE_EMPLOYEE`, `ROLE_TEAM_COORDINATOR`, `ROLE_ADMIN`.
- Passwords must be hashed using `BCryptPasswordEncoder` (strength 12).
- Enforce method-level security with `@PreAuthorize` on administrative and coordinator endpoints.

### 2. Cost Estimation (Time & Space Complexity)
- Document the Big-O Time and Space complexity in Javadoc block comments on all algorithmic methods.
- Core algorithmic guidelines:
  - **Filter-First**: Filter ineligible desks before scoring to reduce candidate space from $O(TotalDesks)$ to $O(EligibleDesks)$.
  - **Squared Distance**: Use squared Euclidean distance ($dx^2 + dy^2$) instead of `Math.sqrt` to reduce floating-point operations.
  - **Spatial Recommendation**: Bounded to $O(D + C \cdot T)$ time and $O(C)$ space for candidate scoring.
  - **Heuristic Clustering**: Anchor-and-expand with fixed $K=10$ to keep team placement bounded to $O(K \cdot C \log M)$ instead of NP-hard combinatorial explosion.
  - **B-Tree Database Index Traversal**: Account for $O(\log N)$ storage engine traversal for row lookups and pessimistic lock acquisitions.

### 3. Handling System Failures & Fault Tolerance
- **ACID Transactions**: Atomic transactions (`@Transactional(rollbackFor = Exception.class)`) ensure zero partial state persistence on failure.
- **Race Condition Prevention & Parent Row Serialization**:
  1. **Parent Row Serialization**: Quotas and floor capacities are aggregate invariants that individual desk locks cannot protect. Forward booking transactions acquire pessimistic locks in strict hierarchy: Parent `Floor` row first (`SELECT ... FOR UPDATE`), then `TeamFloorQuota` row, then the chosen `Desk` row.
  2. **In-Lock Fresh Spatial Ranking (PostgreSQL Aborted Transaction Prevention)**: In PostgreSQL, any failed statement (such as a `NOWAIT` lock error or constraint violation) aborts the physical transaction, preventing subsequent SQL execution even if Java catches the exception. Because the parent `Floor` lock serializes requests, competing threads wait, acquire the lock, and execute a **single fresh in-transaction ranking** against current DB state. Thread B seamlessly selects the next adjacent desk without looping or catching runtime errors.
  3. **Exact Floor Headroom Invariant**:
     $$\text{hotActive} + \text{fixedNotReleased} \le \text{maxCapacity}$$
     where $\text{fixedNotReleased} = \text{totalFixedDesksOnFloor} - \text{fixedReleasedToday}$. Desks whose owner has not yet booked still count toward $\text{fixedNotReleased}$, preserving their seat headroom.
  4. **Queryable Quota & Headroom via `is_owner_booking`**: `bookings.is_owner_booking BOOLEAN` distinguishes owner bookings from hot bookings, enabling partial index scans on `idx_bookings_team_quota` and `idx_bookings_floor_capacity` without table joins.
  5. **Storage-Level Defense-in-Depth (Partial Unique Indexes)**:
     - `uq_active_desk_day`: `UNIQUE (desk_id, booking_date) WHERE status IN ('BOOKED', 'CHECKED_IN')` (automatically allows re-booking once status is `CANCELLED` or `NO_SHOW`).
     - `uq_active_employee_day`: `UNIQUE (employee_id, booking_date) WHERE status IN ('BOOKED', 'CHECKED_IN')` (restricts employee to one active booking per calendar day).
- **Deadlock Mitigation**: Forward booking transactions adhere to descending global lock hierarchy (`Floor` $\to$ `TeamFloorQuota` $\to$ `Desk` ASC). Single-row updates (cancellations, no-show releases) do not participate in multi-resource circular waits.
- **Timezone-Proof Crash-Resilient Schedulers**: No-show auto-release sweeps execute atomic conditional SQL:
  ```sql
  UPDATE bookings SET status = 'NO_SHOW', updated_at = :nowUtc 
  WHERE status = 'BOOKED' AND check_in_deadline <= :nowUtc;
  ```
  The query is idempotent and race-proof; multi-instance deployments run it safely without requiring distributed lock libraries.
- **Target Production SLA & Disaster Recovery Blueprint**:
  - PostgreSQL Write-Ahead Logging (WAL) archiving and Point-In-Time Recovery (PITR) support.
  - Daily physical snapshots and logical exports (`pg_dump`) targeting RPO $\le 5$ min and RTO $\le 15$ min.
  - Redis cache resilience: Custom `CacheErrorHandler` ensures transparent failover to PostgreSQL direct queries if Redis nodes fail.

### 4. Object-Oriented Programming (OOPS) Principles
- **Encapsulation**: Private fields, immutable value objects where appropriate (`DeskCoordinate`), domain invariants guarded inside entity methods.
- **Polymorphism & Strategy Pattern**: Define `DeskAllocationStrategy` interface with multiple implementations:
  - `TeamNeighbourhoodStrategy` (allocates adjacent to active teammates)
  - `CenterBasedStrategy` (deterministic floor centroid seeding for new teams with $\gamma$ occupancy penalty)
- **Inheritance**: Abstract base entity (`BaseEntity`) for audit tracking (`id`, `createdAt`, `updatedAt`, `version`).

### 5. Documented Trade-offs
- Every significant architectural decision must have documented rationale (in code comments, Javadoc, and documentation):
  - Pessimistic locking vs. Optimistic locking
  - In-memory Euclidean grid vs. Graph-based spatial indexing (PostGIS)
  - Workday slotting vs. Arbitrary granular time intervals
  - Heuristic grouping vs. Exact Integer Linear Programming (ILP)

### 6. System Monitoring & Observability
- Integrate **Spring Boot Actuator** (`/actuator/health`, `/actuator/metrics`, `/actuator/info`).
- Register **Micrometer metrics** for key domain events:
  - `booking.requests.total`, `booking.conflicts.total`, `booking.allocations.latency`, `booking.noshow.releases.total`.
- Implement structured logging with SLF4J and MDC (Mapped Diagnostic Context) to attach `requestId`, `employeeId`, and `traceId`.

### 7. Caching Strategy
- **Fundamental Rule**: Redis speeds up reads; PostgreSQL remains the single source of truth for booking and availability.
- Leverage **Redis / Spring Cache abstraction** with **cache-aside pattern**:
  - `@Cacheable("floors")`: Cache floor metadata (capacity, name) with TTL 30–60 min.
  - `@Cacheable("floor-desks")`: Cache desk layout/coordinates (id, row, column, type) with TTL 30–60 min.
  - `@Cacheable("teams")`: Cache team metadata and membership with TTL 15–30 min.
  - `@Cacheable("employee-team")`: Cache employee → team mapping with TTL 15–30 min.
- **Cache desk layout, NOT desk occupancy**: The cached desk data contains physical properties (coordinates, type) — never `available: true/false`.
- **Fault-Tolerant Cache Handler**: Register a custom `CacheErrorHandler` so that Redis connection failures log warnings and fall through to PostgreSQL rather than failing client requests.
- **Fresh Quota Resolution**: Always resolve current `team_id` from DB or cache-aside during transactional quota checks; never rely on potentially stale JWT claims following team transfers.
- **CRITICAL**: Booking persistence, desk availability checks, quota enforcement, and pessimistic row locks MUST bypass cache and run directly against PostgreSQL.
- **Do NOT cache**: Active bookings count, availability flags, quota remaining at booking time, or any transactional booking state.

### 8. Error and Exception Handling Framework
- Centralized exception handling via `@RestControllerAdvice`.
- Adhere to **RFC 7807 / RFC 9457 Problem Details** standard for all error responses:
  - Fields: `type`, `title`, `status`, `detail`, `instance`, `errorCode`, `timestamp`.
- Strict custom exception hierarchy extending a base `DomainException`:
  - `DeskAlreadyBookedException` (409 Conflict)
  - `NoDeskAvailableException` (409 Conflict)
  - `AlreadyBookedException` (409 Conflict)
  - `QuotaExceededException` (422 Unprocessable Entity)
  - `CutOffPassedException` (400 Bad Request)
  - `InvalidBookingDateException` (400 Bad Request)
  - `InvalidCheckInException` (400 Bad Request)
- Never leak raw stack traces or internal SQL errors to API consumers.

---

## 7. Deterministic Allocation Directives

1. **No Randomness**: Never use `Random`, `ThreadLocalRandom`, `Collections.shuffle()`, or arbitrary database sorting without an explicit `ORDER BY`.
2. **Deterministic Tie-Breaking**: When multiple desks have identical distance scores, tie-break deterministically using fixed primary keys (`desk.id ASC` or `(row, col)` ascending order).

---

## 8. Booking Window & Temporal Directives

1. **India Standard Time (`Asia/Kolkata`)**: All operations, offices, and employees operate in IST (UTC+05:30). Uniform 24-hour days without DST drift.
2. **Continuous Anytime Booking**: Employees can book desks up to $N$ business days ahead at **any time**. There is no artificial pre-day cut-off or booking blackout.
3. **On-Day Time Window Cut-Off Rule**: On day $D$, an employee can book a desk for an upcoming time window at **any time before that time window begins** (`requestTime < cutoffTime`). A request to book an expired time window is rejected if `requestTime >= cutoffTime` (`InvalidBookingDateException`).
4. **Same-Day Available & Reclaimed Desk Booking**: Throughout day $D$, unreserved desks and desks reclaimed from cancellations and no-show sweeps can be booked immediately. To avoid instant expiration, check-in deadline is computed dynamically:
   $$\text{check\_in\_deadline} = \max(\text{workday\_start} + \text{grace\_period}, \text{booked\_at} + \text{walk\_in\_grace})$$
5. **Cancellation Cut-Off Rule**: Cancellations are permitted at any time before the cut-off boundary (`now < check_in_deadline`). From the moment of cancellation, the desk immediately returns to the eligible pool.
6. **Clock Abstraction**: All temporal evaluations must inject `java.time.Clock` for deterministic testing.
