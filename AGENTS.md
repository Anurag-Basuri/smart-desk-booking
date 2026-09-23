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
  - **Heuristic Clustering**: Anchor-and-expand with fixed $K=10$ to keep team placement bounded to $O(K \cdot C \log M)$ instead of NP-hard combinatorial explosion.

### 3. Handling System Failures & Fault Tolerance
- **ACID Transactions**: Atomic transactions (`@Transactional(rollbackFor = Exception.class)`) ensure zero partial state persistence on failure.
- **Race Condition Prevention**: Defense-in-depth:
  1. Pessimistic row locking: `SELECT ... FOR UPDATE` via `@Lock(LockModeType.PESSIMISTIC_WRITE)`.
  2. Database uniqueness constraint on `(desk_id, booking_date)` for active bookings.
- **Deadlock Mitigation**: When locking multiple resources (e.g., team bookings), sort resource IDs (e.g., `deskId` ascending) prior to acquisition.
- **Crash-Resilient Schedulers**: No-show auto-release sweeps must query idempotent status windows (`WHERE booking_date = :today AND status = 'CONFIRMED' AND start_time + grace_period <= :now`) so missed runs immediately self-heal on reboot.

### 4. Object-Oriented Programming (OOPS) Principles
- **Encapsulation**: Private fields, immutable value objects where appropriate (`DeskCoordinate`), domain invariants guarded inside entity methods.
- **Polymorphism & Strategy Pattern**: Define `DeskAllocationStrategy` interface with multiple implementations:
  - `TeamNeighbourhoodStrategy` (allocates adjacent to active teammates)
  - `CenterBasedStrategy` (deterministic floor centroid seeding for new teams)
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
- Leverage **Redis / Spring Cache abstraction**:
  - `@Cacheable("floors")`: Cache static floor maps and desk coordinates with a long TTL (e.g., 24h).
  - `@Cacheable("teams")`: Cache team rosters and membership.
  - Evict or invalidate appropriately on administrative updates using `@CacheEvict`.
  - **CRITICAL**: Booking persistence and final desk reservation MUST bypass the cache and run directly through the database with pessimistic locking to guarantee strict ACID consistency.

### 8. Error and Exception Handling Framework
- Centralized exception handling via `@RestControllerAdvice`.
- Adhere to **RFC 7807 / RFC 9457 Problem Details** standard for all error responses:
  - Fields: `type`, `title`, `status`, `detail`, `instance`, `errorCode`, `timestamp`.
- Strict custom exception hierarchy extending a base `DomainException` (e.g., `DeskAlreadyBookedException`, `QuotaExceededException`, `CutOffPassedException`).
- Never leak raw stack traces or internal SQL errors to API consumers.

---

## 7. Deterministic Allocation Directives

1. **No Randomness**: Never use `Random`, `ThreadLocalRandom`, `Collections.shuffle()`, or arbitrary database sorting without an explicit `ORDER BY`.
2. **Deterministic Tie-Breaking**: When multiple desks have identical distance scores, tie-break deterministically using fixed primary keys (`desk.id ASC` or `(row, col)` ascending order).
