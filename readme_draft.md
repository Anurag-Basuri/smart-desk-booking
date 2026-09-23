# Smart Desk Booking — Seat Your Team Together

A backend system for hybrid-office desk booking. Employees book hot desks or occupy their reserved fixed desks for the days they come in. The system seats teammates near each other, enforces team/floor quotas, handles no-shows, and guarantees that two people never end up with the same desk — even when hundreds book at the same moment.

Built with **Java 17 + Spring Boot 3 + PostgreSQL**.

---

## Table of Contents

- [How It Works](#how-it-works)
- [Desk Types](#desk-types)
- [Booking Rules](#booking-rules)
- [Team Neighbourhood Placement — The Algorithm](#team-neighbourhood-placement--the-algorithm)
- [Concurrency — No Double Booking](#concurrency--no-double-booking)
- [No-Show Detection and Auto-Release](#no-show-detection-and-auto-release)
- [Quotas and Capacity](#quotas-and-capacity)
- [Timezones and Cut-Off Windows](#timezones-and-cut-off-windows)
- [Assumptions](#assumptions)
- [Trade-Offs](#trade-offs)
- [API Overview](#api-overview)
- [Database Schema](#database-schema)
- [Layered Architecture & Project Structure](#layered-architecture--project-structure)
- [Enterprise Evaluation Criteria & Interview Deep-Dive](#enterprise-evaluation-criteria--interview-deep-dive)
- [How to Run](#how-to-run)
- [Testing](#testing)
- [Coding Standards & Guidelines](#coding-standards--guidelines)

---

## How It Works

An authenticated employee opens the system and sees available desks for a given date and floor.

The system doesn't hand out desks randomly. It recommends desks based on where the employee's teammates are already sitting that day. If no teammates have booked yet, it picks a desk near the center of the floor — a deterministic starting point that's reproducible and easy to test.

The process is split into two distinct stages:

**1. Floor Selection:** We first determine the eligible floor based on hard constraints and user/team preferences.
**2. Desk Selection:** Once a floor is selected, we run the spatial neighbourhood algorithm to find the best desk *only on that floor*.

The complete architecture looks like this:

```text
                 Employee
                    │
                    ▼
              Authentication
                    │
                    ▼
             Booking Request
                    │
                    ▼
             ┌───────────────┐
             │ FLOOR CHOICE  │
             └───────┬───────┘
                     │
          ┌──────────┼──────────┐
          │          │          │
       Fixed      User/Team   Fallback
        desk       preference   floor
          │          │          │
          └──────────┼──────────┘
                     ▼
                Selected Floor
                     │
                     ▼
              Desk Allocation
                     │
         ┌───────────┴───────────┐
         │                       │
      Fixed desk          Hot desk allocation
                                 │
                         Team has bookings?
                           /           \
                         Yes            No
                          │              │
                 Team neighbourhood   Floor seed /
                    scoring           balanced seed
                          │              │
                          └──────┬───────┘
                                 ▼
                           Best candidate
                                 │
                                 ▼
                          DB row lock
                                 │
                                 ▼
                           Re-check state
                                 │
                                 ▼
                            Create booking
```

---

## Desk Types

The system supports two types:

### Hot Desks

Shared pool. Any eligible employee can book any available hot desk, subject to team quotas and floor capacity limits.

```
AVAILABLE  -->  BOOKED  -->  CHECKED_IN
                  |
                  v
              CANCELLED  (cancelled before check-in deadline)
                  or
              NO_SHOW    (grace period expires without check-in)
                  |
                  v
        [Automatically available again via partial unique index]
```

When a booking transitions to `CANCELLED` or `NO_SHOW`, it is immediately excluded by the database partial unique index (`WHERE status IN ('BOOKED', 'CHECKED_IN')`). The desk instantly becomes eligible for re-booking on that date without needing a synthetic `RELEASED` status.

### Fixed Desks

Reserved for a specific named employee. A fixed desk does not become a hot desk just because its owner hasn't booked it yet.

```
RESERVED_FOR_EMPLOYEE
    |
    +-- Owner books it      --> BOOKED --> CHECKED_IN (is_owner_booking = TRUE)
    |
    +-- Owner cancels       --> CANCELLED --> Desk immediately hot-eligible for that date
    |
    +-- Owner no-shows      --> NO_SHOW   --> Desk immediately hot-eligible for that date
    |
    +-- Owner doesn't book  --> Desk stays reserved, NOT available to others
```

#### Fixed Desk Headroom & Capacity Invariant

To guarantee that fixed-desk owners always have headroom while maximizing floor utilization, floor hot-desk bookings are governed by a single exact invariant:

$$\text{hotActive} + \text{fixedNotReleased} \le \text{maxCapacity}$$

Where:
- $\text{hotActive}$: Total active hot bookings on the floor for date $D$ (`status IN ('BOOKED', 'CHECKED_IN') AND is_owner_booking = FALSE`).
- $\text{fixedNotReleased} = \text{totalFixedDesksOnFloor} - \text{fixedReleasedToday}$: Fixed desks that must remain protected. A fixed desk whose owner has *not yet booked* still counts toward $\text{fixedNotReleased}$, guaranteeing their seat is held.
- $\text{fixedReleasedToday}$: Fixed desks on that floor where the assigned owner explicitly `CANCELLED` or was marked `NO_SHOW` on date $D$.
- $\text{maxCapacity}$: `floors.max_capacity`.

**Operational Implications**:
- **Quota Exemption**: When a fixed-desk owner books their assigned desk, `is_owner_booking = TRUE`. This booking does not consume team hot-desk quota and does not count against $\text{hotActive}$.
- **Dynamic Capacity Release**: When a fixed-desk owner cancels or no-shows, $\text{fixedReleasedToday}$ increments by 1, so $\text{fixedNotReleased}$ decrements by 1. This mathematically increases the permissible $\text{hotActive}$ count by 1, immediately making the desk eligible for other employees.

---

## Booking Rules

These rules govern how and when desks can be reserved:

| Rule | Enforcement Level | Detail |
| :--- | :--- | :--- |
| **Anytime Booking Window** | Application Service | Employees can book desks up to $N$ business days ahead (configurable via `smartdesk.booking.advance-days`, default: 14 business days) at **any time**. There is no artificial pre-day blackout. |
| **On-Day Booking for Time Window** | Application Service | On day $D$, an employee can book a desk for an upcoming time window at **any time before that time window begins** (e.g., at 07:30 AM or 08:45 AM before a 09:00 AM slot). |
| **Strict Cut-Off Boundary** | Application Service | A booking request for a time window must arrive before the cut-off: `requestTime < cutoffTime → allowed`; `requestTime >= cutoffTime → rejected` (`InvalidBookingDateException`). Booking a time window whose start has passed is disallowed. |
| **Same-Day Available & Reclaimed Booking** | Application Service | Throughout day $D$, any currently unreserved desk or desk reclaimed from cancellations and no-shows can be booked immediately, subject to quotas and floor capacity. |
| **Dynamic Check-In Deadline** | Application Service | Prevents instant expiration of same-day bookings: `check_in_deadline = max(workday_start + grace_period, booked_at + walk_in_grace)` (default walk-in grace: 15 minutes). |
| **Cancellation Cut-Off** | Application Service | An employee can cancel their booking at any time before the cut-off (before the time window starts or before the desk's check-in deadline: `now < check_in_deadline`). Upon cancellation, the desk immediately returns to the eligible pool. |
| **Single Active Booking per Day** | Storage Layer (DB Index) | Enforced via partial unique index `uq_active_employee_day` on `(employee_id, booking_date) WHERE status IN ('BOOKED', 'CHECKED_IN')`. |
| **Desk Concurrency Defense** | Storage Layer (DB Index) | Enforced via partial unique index `uq_active_desk_day` on `(desk_id, booking_date) WHERE status IN ('BOOKED', 'CHECKED_IN')`. Automatically permits re-booking once status is `CANCELLED` or `NO_SHOW`. |
| **Fixed Desk Ownership** | Storage Layer (Check Constraint) | Enforced via `chk_desk_fixed_owner`: fixed desks must have `reserved_for_employee_id NOT NULL`; hot desks must have `NULL`. |
| **Team Quota & Floor Capacity** | Application + DB Locks | Team limits (e.g. "Team A $\le$ 8 on Floor 3") and floor hot capacity headroom are validated inside the transaction protected by parent pessimistic row locks. |

> **Storage vs. Application Division of Responsibility**: Database constraints act as the unbreachable safety net for active booking uniqueness and physical layout consistency. High-level temporal policies (booking windows, time-slot cut-offs, team quotas, and fixed-desk ownership checks) are orchestrated inside transactional Spring services.

---

## Floor Selection vs. Desk Selection

Floor selection is a **constrained recommendation problem**. We evaluate hard eligibility (capacity, quotas), then score floors based on where the employee's team is mostly located, alongside any explicitly requested floor. 

Once a floor is selected, we perform the **spatial assignment problem** (desk selection). Coordinates like `(row, column)` only have meaning *within* a specific floor, so our algorithm never compares a desk on Floor 2 against a desk on Floor 3.

## Team Neighbourhood Placement — The Algorithm

This is the core algorithmic piece that runs **only on the selected floor**. The goal: when an employee books a desk, prefer desks that are physically close to where their teammates are already sitting on that same floor.

We are solving a constrained spatial seat-assignment problem, not a generic clustering problem. We don't use K-means, DBSCAN, or any ML-based approach. The team membership is already known from the database — we don't need to discover clusters.

### Why not random allocation?

If an employee refreshes the page and gets a completely different recommendation each time, the system becomes hard to reason about and hard to test. All our allocation is deterministic: same inputs produce the same recommendation.

### Individual Booking — How It Works

Suppose Team A currently has three people booked on Floor 3:

```
D10 at position (2,3)
D11 at position (2,4)
D20 at position (3,3)
```

A fourth teammate wants a desk. The available desks are:

```
D12 (2,5),  D13 (2,6),  D21 (3,4),  D31 (5,2),  D40 (8,8)
```

**Step 1 — Filter.** Remove every desk that fails a hard rule (already booked, wrong type, quota exceeded, etc.). Only eligible candidates survive.

**Step 2 — Score each candidate.** For every remaining desk, calculate two things:

1. **Nearest-teammate distance**: the squared Euclidean distance to the closest existing team desk.

   ```
   nearestDistance(D) = min over all team desks T of: (row_D - row_T)² + (col_D - col_T)²
   ```

2. **Centroid distance**: the squared Euclidean distance to the team's geometric center.

   ```
   centroid = (average row of team desks, average column of team desks)
   centroidDistance(D) = (row_D - centroid_row)² + (col_D - centroid_col)²
   ```

We use squared distances because we only need relative ordering — skipping the square root saves computation without changing the ranking.

**Step 3 — Rank.** Sort candidates by:
1. Smallest nearest-teammate distance (primary)
2. Smallest centroid distance (secondary — breaks ties by preferring desks closer to the team's center of mass)
3. Smallest desk ID (tertiary — deterministic tie-break)

**Step 4 — Return the top candidate.**

Working through the example:

```
D21 (3,4):
  nearest to D11(2,4) = 1² + 0² = 1
  nearest to D20(3,3) = 0² + 1² = 1
  --> nearestDistance = 1

D12 (2,5):
  nearest to D11(2,4) = 0² + 1² = 1
  --> nearestDistance = 1

D40 (8,8):
  nearest to D20(3,3) = 25 + 25 = 50
  --> nearestDistance = 50
```

D21 and D12 tie on nearest distance (both 1). The centroid of team desks is roughly (2.33, 3.33). D21 at (3,4) is closer to the centroid than D12 at (2,5), so D21 wins.

Result: the team grows its occupied region naturally.

```
Before:          After:
D10  D11         D10  D11
D20              D20  D21  <-- new booking
```

### What if the employee is the first from their team?

When no teammates are currently booked on the target floor, there is no existing team cluster to expand. We fall back to **Center-Based Seeding with Local Occupancy Penalty**:

#### 1. Floor Geometric Center
For a floor grid defined by dimensions $(M_{\text{rows}}, N_{\text{cols}})$, the geometric center coordinates $C_{\text{floor}} = (r_c, c_c)$ are:
$$r_c = \left\lfloor \frac{M_{\text{rows}}}{2} \right\rfloor, \quad c_c = \left\lfloor \frac{N_{\text{cols}}}{2} \right\rfloor$$
*(Alternatively configured directly via `floors.center_row` and `floors.center_column` in the database).*

#### 2. Local Occupancy Penalty Formula
To prevent multiple teams from colliding at the exact same physical center point, candidate desks $D = (r, c)$ are ranked by a composite penalty score:

$$\text{Score}(D) = \text{dist}^2(D, C_{\text{floor}}) + \gamma \cdot \text{Occupancy}(D, R)$$

Where:
- **Center Proximity**: $\text{dist}^2(D, C_{\text{floor}}) = (r - r_c)^2 + (c - c_c)^2$ (squared Euclidean distance to floor center).
- **Local Neighborhood Occupancy**: $\text{Occupancy}(D, R)$ counts active bookings within Chebyshev distance radius $R = 2$ (a $5 \times 5$ grid cell around desk $D$):
  $$\text{Occupancy}(D, R) = \sum_{B \in \text{ActiveBookings}} \mathbb{I}\Big(\max(|r - B_{\text{row}}|, |c - B_{\text{col}}|) \le R\Big)$$
- **Dispersion Weight**: $\gamma = 5.0$ (configurable constant penalizing crowded zones).
- **Deterministic Selection**: The candidate desk with the **lowest composite score** is selected (tie-break on `desk_id ASC`). This deterministically distributes new team clusters into open, comfortable spaces.

### Team Booking — Booking Multiple Desks at Once

A designated team coordinator can book desks for multiple team members in a single request. 

Crucially, **all members are attempted on the same floor**. If the requested floor doesn't have enough capacity or team quota remaining to fit the whole group, the entire booking fails. We never silently scatter teammates across different floors.

The algorithm here is **anchor-and-expand**:

1. Determine how many hot desks are needed (fixed-desk employees are handled separately).
2. Find all eligible candidate desks on the requested floor.
3. Pick K anchor points (roughly 10 desks closest to the existing team neighbourhood, or closest to the floor center for a new team).
4. For each anchor, greedily expand outward — grab the nearest eligible desks until we have enough.
5. Score each candidate group on:
   - **Team proximity**: total squared distance from the group's desks to the existing team centroid (weighted higher)
   - **Group compactness**: total squared distance from the group's desks to the group's own centroid
6. Pick the group with the best combined score.

For a 500-desk floor with K=10 anchors, this is a small search — well within real-time performance.

The result is either all desks booked atomically (everyone gets a seat) or none (if there aren't enough eligible desks). We never partially book a team.

### What we deliberately avoid

- **K-means / DBSCAN / ML clustering** — overkill for 500 desks and doesn't match the problem (we're assigning, not discovering clusters)
- **Permanent cluster IDs** — the team's neighbourhood is derived dynamically from active bookings, not stored as a separate entity
- **Comparing every possible combination of N desks** — combinatorial explosion; the anchor-and-expand approach is bounded
- **Spatial indexes / R-trees / PostGIS** — unnecessary at this scale; simple in-memory distance calculations are fast enough

### Complexity

| Operation | Complexity | Practical Size |
|-----------|-----------|---------------|
| Individual booking | O(D + C · T) where D = total desks, C = eligible candidates, T = active team bookings | D ≤ 500, C ≤ 500, T ≤ 50. Microsecond ALU execution. |
| Team booking | O(K · C · log M) | K=10 anchors, M ≤ 10 team members, C ≤ 500 candidates. |

Both algorithms run in under 1 millisecond in-memory on modern JVMs.

### Priority Hierarchy

When the algorithm and business rules conflict, the hierarchy is:

```
Fixed desk assignment        (highest priority)
    |
Hard business constraints   (quotas, capacity, availability)
    |
Team neighbourhood preference
    |
Deterministic fallback       (lowest priority)
```

The algorithm never overrides a fixed desk assignment or violates a quota just to make the neighbourhood tighter.

---

## Concurrency — No Double Booking

This is the heart of the problem. Two employees tapping "book" on the last free desk at the same instant must not both succeed, and concurrent requests must never violate team quotas or floor capacities.

### The Serialization Point: Aggregate Invariants Need Parent Locks

In technical interviews, a frequent mistake is claiming that locking individual desk rows (`SELECT * FROM desks WHERE id = ? FOR UPDATE`) prevents over-allocation. 

It does not. **Team quotas and floor capacity are aggregate invariants across many desks.** If two teammates concurrently book the last available seat under Team A's quota on Floor 3, per-desk locks will lock two *different* desks simultaneously and both transactions will commit, breaching the quota.

Therefore, the **parent `Floor` row and `TeamFloorQuota` row serve as the serialization point**.

```text
       Contention Resolution & Dynamic In-Lock Ranking
       
  Thread A (Employee 1)                    Thread B (Employee 2)
           │                                        │
    1. BEGIN TX (READ COMMITTED)             1. BEGIN TX (READ COMMITTED)
           │                                        │
    2. LOCK Floor Row                        2. WAIT on Floor Row Lock
           │                                        :
    3. Verify Floor Hot Headroom                    :
    4. LOCK TeamFloorQuota Row                      :
    5. Verify Team Hot Quota                        :
    6. Fresh In-TX Ranking:                         :
       Rank 1 -> Desk 101                           :
           │                                        :
    7. LOCK Desk 101 (Available)                    :
    8. INSERT Booking (Desk 101)                    :
    9. COMMIT TX ──────────────────────────────────▶:
       (Releases Locks)                      2. ACQUIRES Floor Row Lock
                                             3. Verify Floor Hot Headroom
                                             4. ACQUIRES TeamFloorQuota Lock
                                             5. Verify Team Hot Quota
                                             6. Fresh In-TX Ranking:
                                                (Sees Desk 101 now BOOKED by Thread A)
                                                Rank 1 -> Desk 102 (Adjacent to Desk 101!)
                                             7. LOCK Desk 102 (Available)
                                             8. INSERT Booking (Desk 102)
                                             9. COMMIT TX (Success! Zero 409 Conflict!)
```

### Why In-Lock Fresh Ranking Replaces "NOWAIT Catch-and-Loop"

In PostgreSQL, executing `SELECT ... FOR UPDATE NOWAIT` or catching a unique constraint violation puts the transaction into an aborted state:
```text
ERROR: current transaction is aborted, commands ignored until end of transaction block
```
Even if application Java code catches the exception, PostgreSQL rejects all subsequent SQL statements on that connection until `ROLLBACK`. Catch-and-continue loops inside the same transaction are structurally invalid in PostgreSQL.

**Our Clean Architectural Solution**:
Because the floor-level lock serializes concurrent booking attempts on that floor, competing threads wait on the lock rather than failing immediately. When Thread B acquires the lock:
1. It executes a **single fresh in-transaction ranking** against current database state.
2. It immediately sees that Desk 101 was taken by Thread A and deterministically selects the next-best candidate (Desk 102, which is physically adjacent).
3. It locks Desk 102, inserts the booking, and commits.
4. If and only if **all eligible candidate desks on the floor are exhausted**, the service throws `NoDeskAvailableException` (HTTP 409).

### Transaction Isolation & Lock Timeout

- **Isolation Level**: `READ COMMITTED` (PostgreSQL default). Since the parent floor row lock serializes the critical section, `READ COMMITTED` completely prevents non-repeatable reads and phantom capacity breaches without the overhead of `SERIALIZABLE`.
- **Lock Timeout**: Every booking transaction executes `SET LOCAL lock_timeout = '3s';`. If a connection cannot acquire the floor lock within 3 seconds under extreme surges, it fails fast with an HTTP 503 / 409 `Retry-After: 1` instead of exhausting the connection pool.
- **Connection Pool Sizing**: HikariCP is sized for the workload (`maximum-pool-size = 20-30`). Because each in-lock ranking and insert completes in 10–20 ms, a single floor easily handles 50–100 bookings/second under sustained contention, and scales linearly across floors ($F \times 50\text{--}100\text{ TPS}$).

### Database Constraint as Defense-in-Depth

On top of application-level locking, we enforce database-level uniqueness via a **partial unique index**:

```sql
CREATE UNIQUE INDEX uq_active_desk_day 
ON bookings (desk_id, booking_date) 
WHERE status IN ('BOOKED', 'CHECKED_IN');
```

This is not a substitute for locking — it is the unbreachable safety net. If any code path bypasses locking, PostgreSQL rejects the duplicate row. Spring Boot captures `DataIntegrityViolationException` and translates it to HTTP 409 `DeskAlreadyBookedException`.

Crucially, because this is a **partial index**, it excludes `CANCELLED` and `NO_SHOW` rows. A desk that was cancelled or no-showed can be re-booked immediately without manual index cleanups.

### Deadlock Elimination: Strict Global Lock Hierarchy

When booking multiple desks for a team, circular waits are eliminated by acquiring locks in a strict global hierarchy:
1. Parent `Floor` row
2. Parent `TeamFloorQuota` row
3. Target `Desk` rows sorted ascending by primary key (`desk_id ASC`):
   ```
   Lock Desk 10 -> Lock Desk 11 -> Lock Desk 12 -> Lock Desk 20
   ```
Single-row status updates (`CANCELLED`, `NO_SHOW`, `CHECKED_IN`) update only their own row in `bookings` and do not participate in multi-resource lock graphs.

---

## No-Show Detection and Auto-Release

A booked desk that sits empty is wasted. The system detects no-shows and automatically makes the desk available to other employees.

### State Transitions & Lifecycle

```
Desk booked for 09:00 IST
    │
    ▼
Check-in deadline (09:30 IST)
    │
    ├─► Employee checks in before 09:30 ──► CHECKED_IN (Desk occupied as planned)
    │
    └─► 09:30 arrives without check-in   ──► NO_SHOW (Desk immediately re-bookable)
```

**Reclamation Mechanisms**:
1. **Explicit Cancellation**: Employee cancels before the check-in deadline. Booking transitions to `CANCELLED`.
2. **No-Show Expiry**: Sweeper marks overdue booking `NO_SHOW`.

In both cases, the partial index `uq_active_desk_day` stops indexing the row, instantly returning the desk to the eligible candidate pool for that date. There is no synthetic `RELEASED` status.

### Late Arrivals Policy

If an employee arrives at the office at 09:45 IST after their booking transitioned to `NO_SHOW`:
- Check-in is rejected with HTTP 400 (`InvalidCheckInException: Booking marked NO_SHOW after grace period expired at 09:30`).
- The desk may have already been re-booked by a walk-in colleague.
- The late-arriving employee must use the Same-Day Walk-In API to find an available desk.

### Idempotent Sweeper (Why ShedLock is Unnecessary)

The background sweep executes an atomic, conditional SQL statement:

```sql
UPDATE bookings 
SET status = 'NO_SHOW', updated_at = :nowUtc
WHERE status = 'BOOKED' 
  AND check_in_deadline <= :nowUtc;
```

**Resilience Properties**:
- **Idempotent**: Executing this query once or ten times produces identical state.
- **Race-Proof**: If an employee checks in at 09:29:59 IST (`status = 'CHECKED_IN'`), the condition `WHERE status = 'BOOKED'` matches 0 rows.
- **Multi-Instance Safe Without Distributed Locks**: Because the update is conditional and atomic at the PostgreSQL row level, multiple application instances running the sweep simultaneously cannot corrupt state or double-process rows. Heavy distributed locking libraries like ShedLock are entirely optional overhead.

---

## Quotas and Capacity

The system enforces two kinds of limits:

### Team Quotas

"Team A can have at most 8 desks on Floor 3."

Before any hot desk booking is persisted, the transaction acquires the `TeamFloorQuota` row lock and evaluates:

$$\text{teamHotActive} < \text{maxDesks}$$

Where $\text{teamHotActive}$ is queried as:
```sql
SELECT COUNT(*) FROM bookings 
WHERE team_id = :teamId 
  AND floor_id = :floorId 
  AND booking_date = :date 
  AND status IN ('BOOKED', 'CHECKED_IN') 
  AND is_owner_booking = FALSE;
```

### Floor Capacity & Headroom Invariant

Hot desk reservations are constrained by the single exact headroom formula:

$$\text{hotActive} + \text{fixedNotReleased} < \text{maxCapacity}$$

Where:
- $\text{hotActive}$: Current active hot bookings on the floor (`status IN ('BOOKED', 'CHECKED_IN') AND is_owner_booking = FALSE`).
- $\text{fixedNotReleased} = \text{totalFixedDesksOnFloor} - \text{fixedReleasedToday}$: Guarantees that fixed-desk owners who have not yet booked still have their seats reserved.
- When an assigned fixed-desk owner cancels or no-shows, $\text{fixedNotReleased}$ decrements by 1, automatically increasing the permitted $\text{hotActive}$ count.

Both checks execute **inside the transaction under the parent row locks**, ensuring absolute consistency under high concurrency.

---

## Timezones and Cut-Off Windows

All company offices, floors, and employees operate within **India Standard Time (IST, `Asia/Kolkata`, UTC+05:30)**.

### Operational Calendar & Timezone Modeling
- **Zero DST Drift**: India does not observe Daylight Saving Time, providing completely uniform 24-hour calendar days without spring-forward or fall-back transition anomalies.
- **Enterprise-Grade Time Handling**: Even with single-timezone operations, all timestamps (`check_in_deadline`, `created_at`, `checked_in_at`) are modeled and stored as absolute UTC instants (`TIMESTAMP WITH TIME ZONE` in PostgreSQL and `java.time.Instant` in Java).
- **Injected Clock**: All business logic injects `java.time.Clock`, allowing deterministic time-travel assertions in test suites.

```text
IST Input (e.g. 2026-09-24 09:00:00)
       ↓
ZoneId.of("Asia/Kolkata")
       ↓
Convert to absolute instant (2026-09-24 03:30:00Z)
       ↓
Store & compare consistently in PostgreSQL
```

### Booking Windows & Cut-off Rules

The assignment specification states:
> *"Employees book a desk on a floor for a date (and time window); they can cancel before a cut-off."*
> *"Timezone and cut-off edge cases matter – a booking exactly at the cut-off should behave predictably."*

To provide maximum hybrid flexibility while ensuring strict operational predictability:

#### 1. Continuous Anytime Booking (Up to 14 Business Days Ahead)
- **24/7 Booking Availability**: Employees can book desks up to $N = 14$ business days ahead at **any time of day or night**.
- **No Artificial Pre-Day Blackouts**: There is no arbitrary lockout period (such as blocking bookings the evening before). An employee can reserve a seat a week ahead, the night before at 11:30 PM, or early in the morning at 07:00 AM before commuting.

#### 2. On-Day Booking for a Time Window (Before That Time, At Any Time)
- **Pre-Slot Booking**: On day $D$, an employee can book a desk for an upcoming time window (e.g. workday window 09:00:00–18:00:00 IST) **at any time before that time window begins**.
  - 07:15:00 IST for a 09:00 slot → Allowed ✅
  - 08:59:59.999 IST for a 09:00 slot → Allowed ✅
- **Strict Cut-off Boundary**: Booking a time window after its start/cut-off has passed is strictly rejected:
  ```text
  requestTime < cutoffTime    → allowed
  requestTime >= cutoffTime   → rejected (InvalidBookingDateException)
  ```
  - `08:59:59.999` IST → Allowed ✅
  - `09:00:00.000` IST → Rejected ❌ (Window has begun; must book same-day / walk-in slot)
  - `09:00:00.001` IST → Rejected ❌

#### 3. Same-Day Mid-Day & Reclaimed Desk Booking (No Instant Expiration)
- **Dynamic Inventory**: Throughout day $D$, desks that remained unreserved, along with desks reclaimed from cancellations and no-show sweeps (e.g. after the 09:30 morning sweep), can be booked at **any time**.
- **Dynamic Check-In Deadline Formula**:
  If an advance booking is made for 09:00 IST, the check-in deadline is `09:00 + 30m grace = 09:30 IST`.
  If an employee books a desk on day $D$ at 10:15 IST, assigning a 09:30 deadline would cause immediate eviction by the background sweeper. The service calculates:
  
  $$\text{check\_in\_deadline} = \max(\text{workday\_start} + \text{grace\_period}, \text{booked\_at} + \text{walk\_in\_grace})$$
  
  With $\text{walk\_in\_grace} = 15\text{ minutes}$, a booking at 10:15 IST receives a deadline of 10:30 IST (`05:00:00Z`). In-person walk-ins can also trigger immediate check-in.
- **Quota & Capacity Governance**: Every same-day allocation strictly verifies team floor quotas and remaining floor hot headroom under parent row locks before confirmation.

#### 4. Cancellation Cut-Off Rules
- **Cancellation Cut-Off**: An employee can cancel their booking at any time before the cut-off boundary (prior to the time window start or prior to their desk's check-in deadline: `now < check_in_deadline`).
- **Immediate Desk Reclamation**: The instant a booking is cancelled, the partial index `uq_active_desk_day` stops indexing the row, immediately returning the desk to the pool of eligible desks for other colleagues.
- **Strict Boundary**: A cancellation attempted at or after the check-in deadline (`now >= check_in_deadline`) is rejected with `CutOffPassedException`.

---

## Assumptions

These are decisions we made where the assignment didn't prescribe a specific answer. They're listed here so you know exactly where we exercised judgment.

> [!NOTE]
> Items marked with **[ASSUMPTION]** are not explicitly stated in the case study. They are reasonable design choices we made to keep the scope focused on the core challenges: concurrency, neighbourhood placement, quotas, and no-shows.

| # | Assumption | Why |
|---|-----------|-----|
| 1 | **[ASSUMPTION]** All employees, teams, and office floors operate in India (`Asia/Kolkata`, IST, UTC+05:30). | Eliminates cross-timezone confusion for physical attendance while using proper UTC storage. |
| 2 | **[ASSUMPTION]** Hot desks are floor-wide; zones are modeled as floors. | The PDF refers to "Zone / Team Quotas" and hot pools. Treating floors as distinct zones aligns cleanly with physical building security, floor-level quotas, and 2D spatial coordinates. |
| 3 | **[ASSUMPTION]** Continuous anytime booking up to $N=14$ business days ahead; on day $D$, booking for an upcoming time window is allowed at any time before that window begins. | Allows employees to book whenever they want (days ahead, night before, or morning of day D), eliminating artificial pre-day cut-offs while enforcing strict window boundaries. |
| 4 | **[ASSUMPTION]** One active booking per employee per date. | Enforced at the database level via partial unique index `uq_active_employee_day` on `(employee_id, booking_date) WHERE status IN ('BOOKED', 'CHECKED_IN')`. |
| 5 | **[ASSUMPTION]** Team booking is initiated by a designated team coordinator. | Restricting multi-seat group booking to a coordinator avoids conflicting parallel attempts for the same teammates. |
| 6 | **[ASSUMPTION]** Deterministic allocation (not random). | Same inputs always produce the identical recommendation. Essential for test reproducibility and auditability. |
| 7 | **[ASSUMPTION]** Fixed desks have guaranteed floor headroom ($\text{hotActive} + \text{fixedNotReleased} \le \text{maxCapacity}$); cancelled/no-show fixed desks enter the hot pool immediately. | Prevents hot desk reservations from locking out fixed-desk owners while re-allocating unused desks. |
| 8 | **[ASSUMPTION]** Walk-in check-in deadline is dynamically computed: $\max(\text{start} + \text{grace}, \text{bookedAt} + \text{walkInGrace})$. | Prevents same-day walk-in bookings after morning grace from expiring instantly. |
| 9 | **[ASSUMPTION]** "Exactly at the cut-off" counts as past the cut-off. | Strict exclusive threshold (`requestTime < cutoffTime`) ensures completely deterministic edge-case behavior. |
| 10 | **[ASSUMPTION]** Floor center with local occupancy penalty ($\gamma = 5.0$) seeds new teams. | Deterministically seeds new teams near the center while dispersing clusters across the floor. |

---

## Trade-Offs

| Decision | Alternative We Considered | Why We Chose This |
|----------|--------------------------|-------------------|
| Pessimistic locking over optimistic | Optimistic locking with version columns + retry | Under real contention for the last few desks, pessimistic locking avoids wasted retries. The conflict window is small (single row lock, released quickly), so blocking is minimal. |
| Squared Euclidean distance (no sqrt) | Full Euclidean, Manhattan, or graph-based distance | Squared Euclidean preserves ranking and avoids unnecessary computation. Manhattan distance doesn't reflect actual physical proximity as well. Graph-based (walking distance) would be more accurate but requires floor-plan data we don't have. |
| Filter-first, then score | Score everything, then filter | Filtering before scoring reduces the candidate set dramatically. On a 500-desk floor, most desks are ineligible (already booked, wrong type, quota exceeded). Scoring only eligible desks keeps the algorithm fast. |
| Anchor-and-expand for team booking | Brute-force all combinations / ILP solver | With 500 desks and teams of 5–10, brute-forcing all C(500,5) combinations is infeasible. An ILP solver would work but is overkill for this scale. Anchor-and-expand with K=10 is fast, simple, and produces good-enough groupings. |
| No spatial indexes | PostGIS / R-tree | At 500 desks, in-memory distance calculations take microseconds. Spatial indexes add complexity (setup, maintenance, queries) without meaningful performance gain at this scale. |
| Workday-level booking slots | Arbitrary time windows with overlap detection | Keeps the data model simple (one booking per desk per date). The core assignment challenges are concurrency and neighbourhood placement, not time-slot fragmentation. |
| Algorithm recommends, database decides | Algorithm reserves directly | Clean separation of concerns. The algorithm is a pure function that ranks desks. The booking service handles locking, re-checking, and committing. This makes concurrency correctness much easier to reason about. |
| All-or-nothing team booking | Partial team booking | If 3 out of 5 desks are available, we don't book 3 and leave 2 teammates stranded. The coordinator can retry with a different floor or date. |

---

## API Overview

*(Endpoints will be updated as implementation progresses)*

### Booking

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/bookings` | Book a desk for a date |
| DELETE | `/api/bookings/{id}` | Cancel a booking (before cut-off) |
| POST | `/api/bookings/team` | Book desks for multiple team members |
| POST | `/api/bookings/{id}/check-in` | Check in to a booked desk |
| GET | `/api/bookings/my` | Get current employee's bookings |

### Desks

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/floors/{floorId}/desks/available` | Get available desks for a floor + date, ranked by recommendation |
| GET | `/api/desks/{id}` | Get desk details |

### Floors

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/floors` | List all floors |
| GET | `/api/floors/{id}` | Get floor details including capacity |

### Teams

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/teams/{id}` | Get team info |
| GET | `/api/teams/{id}/bookings` | Get team's current bookings on a floor |

---

## Database Schema

### Core Tables

> **Design Rule**: Desks are stored as **one row per desk** in PostgreSQL — never as JSON blobs. This enables SQL querying, foreign keys, indexes, row-level locking (`SELECT ... FOR UPDATE` on individual desk rows), and constraints. A desk row describes the **physical desk** (position, type). It does **not** store current availability.

```
employees
---------
id              BIGINT PK
name            VARCHAR
email           VARCHAR UNIQUE
password_hash   VARCHAR          -- BCrypt(12), never plaintext
team_id         BIGINT FK -> teams
timezone        VARCHAR          -- IANA timezone, e.g. "Asia/Kolkata"
role            ENUM('EMPLOYEE', 'TEAM_COORDINATOR', 'ADMIN')
is_active       BOOLEAN
created_at      TIMESTAMP
updated_at      TIMESTAMP
```

```
teams
-----
id              BIGINT PK
name            VARCHAR
created_at      TIMESTAMP
updated_at      TIMESTAMP
```

```
floors
------
id              BIGINT PK
floor_number    INT              -- physical floor number (1, 2, 3...)
name            VARCHAR          -- e.g. "Floor 3"
max_capacity    INT              -- total floor capacity (e.g. 60)
center_row      INT              -- geometric center row (for seed algorithm)
center_column   INT              -- geometric center column (for seed algorithm)
timezone        VARCHAR NOT NULL -- IANA timezone, "Asia/Kolkata"
is_active       BOOLEAN
created_at      TIMESTAMP
updated_at      TIMESTAMP
```

```
desks
-----
id              BIGINT PK
floor_id        BIGINT FK -> floors
row_number      INT              -- physical row on the floor grid
column_number   INT              -- physical column on the floor grid
desk_type       ENUM('HOT', 'FIXED')
reserved_for_employee_id  BIGINT FK -> employees (nullable, only for FIXED)
is_active       BOOLEAN          -- false for maintenance / decommissioned

UNIQUE (floor_id, row_number, column_number)  -- no two desks at same position

-- Storage-level ownership invariant:
CONSTRAINT chk_desk_fixed_owner 
CHECK ((desk_type = 'FIXED' AND reserved_for_employee_id IS NOT NULL) 
    OR (desk_type = 'HOT' AND reserved_for_employee_id IS NULL))
```

> **Important — No `is_available` column on desks.** A desk is a physical object. Its availability depends on `desk + requested date + existing bookings`. D42 might be booked on Sept 25 but available on Sept 26. Storing `is_available` as a mutable column would be misleading and introduce stale-state risks with our concurrency model.

```
bookings
--------
id                  BIGINT PK
desk_id             BIGINT FK -> desks
employee_id         BIGINT FK -> employees
team_id             BIGINT FK -> teams       -- denormalized: fast quota index & immutable team audit
floor_id            BIGINT FK -> floors      -- denormalized: fast capacity index & intra-floor queries
booking_date        DATE                     -- reservation date (e.g. 2026-09-24)
start_time          TIME                     -- workday slot start (09:00:00)
end_time            TIME                     -- workday slot end (18:00:00)
check_in_deadline   TIMESTAMP WITH TIME ZONE -- UTC instant (max(start + grace, bookedAt + walkInGrace))
status              ENUM('BOOKED', 'CHECKED_IN', 'CANCELLED', 'NO_SHOW')
is_owner_booking    BOOLEAN NOT NULL         -- TRUE if owner booked assigned fixed desk (exempt from hot quota)
created_at          TIMESTAMP WITH TIME ZONE
checked_in_at       TIMESTAMP WITH TIME ZONE (nullable)
cancelled_at        TIMESTAMP WITH TIME ZONE (nullable)
updated_at          TIMESTAMP WITH TIME ZONE

-- Partial unique indexes on bookings:
-- 1. Desk uniqueness: prevents two active bookings for the same desk on the same day.
--    Automatically allows re-booking once status is CANCELLED or NO_SHOW.
CREATE UNIQUE INDEX uq_active_desk_day 
ON bookings (desk_id, booking_date) 
WHERE status IN ('BOOKED', 'CHECKED_IN');

-- 2. Employee uniqueness: ensures an employee holds at most one active booking per day.
CREATE UNIQUE INDEX uq_active_employee_day 
ON bookings (employee_id, booking_date) 
WHERE status IN ('BOOKED', 'CHECKED_IN');

-- 3. Composite partial index for fast team-floor quota enforcement (hot bookings only):
CREATE INDEX idx_bookings_team_quota 
ON bookings (team_id, floor_id, booking_date) 
WHERE status IN ('BOOKED', 'CHECKED_IN') AND is_owner_booking = FALSE;

-- 4. Composite partial index for fast floor capacity headroom enforcement (hot bookings only):
CREATE INDEX idx_bookings_floor_capacity 
ON bookings (floor_id, booking_date) 
WHERE status IN ('BOOKED', 'CHECKED_IN') AND is_owner_booking = FALSE;

-- 5. Index for timezone-proof background no-show sweeps:
CREATE INDEX idx_bookings_noshow_sweep 
ON bookings (status, check_in_deadline) 
WHERE status = 'BOOKED';
```

```
team_floor_quotas
-----------------
id              BIGINT PK
team_id         BIGINT FK -> teams
floor_id        BIGINT FK -> floors
max_desks       INT              -- e.g. "Team A ≤ 8 desks on Floor 3"

UNIQUE (team_id, floor_id)       -- one quota record per team per floor
```

### Key Constraints & Design Decisions

| Constraint / Index | Level | Purpose |
| :--- | :--- | :--- |
| Partial index `uq_active_desk_day` | Storage Layer (DB) | Prevents double-booking active reservations while immediately allowing re-booking if status becomes `CANCELLED` or `NO_SHOW`. |
| Partial index `uq_active_employee_day` | Storage Layer (DB) | Enforces that an employee can hold at most one active desk booking per calendar day at the storage layer. |
| Check constraint `chk_desk_fixed_owner` | Storage Layer (DB) | Enforces that fixed desks always have an assigned owner and hot desks never do, preventing orphaned fixed desks. |
| `UNIQUE (floor_id, row_number, column_number)` | Storage Layer (DB) | Guarantees no two desks occupy the same physical position on a floor. |
| `UNIQUE (team_id, floor_id)` | Storage Layer (DB) | Ensures each team has exactly one defined quota per floor. |
| Partial index `idx_bookings_team_quota` | Storage Layer (DB) | Turns team hot quota evaluation into an index-only scan without joining `desks` or `employees`. |
| Partial index `idx_bookings_floor_capacity` | Storage Layer (DB) | Turns floor hot capacity evaluation into an index-only scan without joining `desks`. |
| Flag `is_owner_booking` | Schema | Explicitly separates owner bookings from hot bookings, making quota and headroom counts queryable without joins. |
| No `is_available` on desks | Architecture | Availability is derived from active bookings on a date, not stored as mutable desk state. |
| One row per desk | Architecture | Enables `SELECT ... FOR UPDATE` on individual desk rows for pessimistic concurrency control. |

> [!IMPORTANT]
> **Database Migrations via Flyway**: Standard JPA/Hibernate annotations cannot express PostgreSQL partial indexes with `WHERE` clauses or table-level `CHECK` constraints. All schema definitions are maintained via Flyway SQL migrations (`src/main/resources/db/migration/V1__init_schema.sql`). This guarantees that any fresh checkout automatically deploys the exact indexes and storage safety nets.

### ER Relationships

```
Team 1────────< Employee
Floor 1────────< Desk
Employee 1────────< Booking >────────1 Desk
Team 1────────< Booking
Floor 1────────< Booking
Team *────────* Floor (through team_floor_quotas)
Desk *────────1 Employee (reserved_for, nullable — FIXED desks only)
```

---

## How to Run

### Prerequisites

- **Java 17+**
- **Maven 3.8+**
- **Docker & Docker Compose** (for PostgreSQL 15 & Redis 7)

### Local Environment with Docker Compose

A bundled `docker-compose.yml` spins up PostgreSQL 15 and Redis 7 with container health checks:

```yaml
services:
  postgres:
    image: postgres:15-alpine
    container_name: smartdesk-postgres
    environment:
      POSTGRES_DB: smart_desk_booking
      POSTGRES_USER: smartdesk
      POSTGRES_PASSWORD: password123
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U smartdesk -d smart_desk_booking"]
      interval: 5s
      timeout: 5s
      retries: 5
    volumes:
      - pgdata:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine
    container_name: smartdesk-redis
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 5s
      retries: 5

volumes:
  pgdata:
```

Start the infrastructure:
```bash
docker compose up -d
```

Flyway automatically initializes the schema and partial indexes on application startup.

### Running the Application

**Standard Profile (PostgreSQL + Redis)**:
```bash
./mvnw spring-boot:run
```

**Redis-Free Local Profile (PostgreSQL + In-Memory Cache)**:
If you prefer running without Redis, activate the `local` profile:
```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```
*(The `local` profile uses an in-memory `ConcurrentHashMap` cache manager with identical `@Cacheable` semantics, requiring only PostgreSQL).*

The application starts on `http://localhost:8080`.

### Sample Data Initialization

The application automatically seeds a sample floor (Floor 3 with 60 desks, mixed hot and fixed), multiple teams, employees, and floor quotas for immediate API exploration.

---

## Testing

### Automated Test Suite

```bash
./mvnw clean test
```

Integration tests utilize **Testcontainers** with a real PostgreSQL 15 container to validate raw SQL migrations, partial unique indexes, check constraints, and row-level locking.

### 1. Multi-Threaded Concurrency Tests (`CountDownLatch`)

1. **Two Threads Competing for 1 Desk**:
   - Synchronizes two concurrent threads attempting to book the last available desk.
   - Asserts: Exactly 1 transaction commits successfully (`200 OK`); the other receives a dynamic fallback or clean HTTP 409 `NoDeskAvailableException`. Zero partial indexes violated.
2. **Two Threads with 2 Available Desks (Adjacent Seating)**:
   - Two teammates book concurrently on a floor with 2 adjacent desks.
   - Asserts: Both win; Thread B waits on the floor lock, re-ranks against Thread A's fresh placement, and is seated immediately adjacent to Thread A.
3. **100 Concurrent Threads Competing for $K$ Desks**:
   - 100 threads fire simultaneously for $K = 10$ available desks.
   - Asserts: Exactly 10 bookings created in the database; 90 requests rejected. Zero double-bookings, zero quota over-allocations, zero database constraint deadlocks.
4. **Same-Employee Double-Submit Idempotency Test**:
   - Simulates an employee rapidly double-clicking the "Book" button.
   - Asserts: Exactly 1 active booking persisted (`uq_active_employee_day` enforced); second request receives existing booking or `AlreadyBookedException`.
5. **Dynamic Quota & Headroom After No-Show Sweep**:
   - Asserts that when a fixed-desk owner is marked `NO_SHOW` by the sweeper, $\text{fixedNotReleased}$ decrements, dynamically expanding the permissible hot capacity for subsequent walk-in bookings.

### 2. Clock-Driven Temporal & Edge-Case Tests

Injected `java.time.Clock` enables deterministic time-travel assertions:
- **On-Day Time Window Cut-Off Boundary**: Verifies that booking an upcoming time window starting at 09:00:00 IST succeeds at 08:59:59.999 IST (✅) and is rejected at 09:00:00.000 IST (❌ `InvalidBookingDateException`).
- **Cancellation Cut-Off Boundary**: Verifies that cancelling a booking succeeds strictly before the check-in deadline / cut-off and is rejected once the cut-off boundary is reached (`CutOffPassedException`).
- **Same-Day Mid-Day Deadline Calculation**: Verifies booking made at 10:15 IST receives check-in deadline 10:30 IST (`now + 15m`), preventing instant expiration.
- **Auto-Release Sweeper Verification**: Fast-forwards clock past 09:30:00 IST; asserts overdue `BOOKED` reservations transition to `NO_SHOW`, while checked-in reservations remain `CHECKED_IN`.

---

## Layered Architecture & Project Structure

The project implements a decoupled, industry-grade layered Spring Boot architecture with the Strategy pattern, adhering strictly to **SOLID** principles and clean separation of concerns:

```
com.anurag.smartdeskbooking
├── config/              -- Infrastructure configurations
│   ├── SecurityConfig.java         -- Spring Security, JWT filter chain, RBAC
│   ├── RedisCacheConfig.java       -- Redis cache manager, CustomCacheErrorHandler, TTLs
│   ├── OpenApiConfig.java          -- Swagger / OpenAPI 3.0 documentation
│   └── SchedulingConfig.java       -- Thread pool configuration for background tasks
├── controller/          -- REST API presentation layer (Slim controllers)
│   ├── AuthController.java         -- Login, token refresh
│   ├── BookingController.java      -- Individual and walk-in desk reservations
│   ├── DeskController.java         -- Desk query & recommendation endpoints
│   ├── FloorController.java        -- Floor details, quotas, and capacities
│   └── TeamController.java         -- Team membership and bookings
├── dto/                 -- Data Transfer Objects (Strongly typed contracts)
│   ├── request/                    -- Inbound payloads with Bean Validation (@NotNull, @Valid)
│   │   ├── BookingRequestDto.java
│   │   ├── TeamBookingRequestDto.java
│   │   └── LoginRequestDto.java
│   └── response/                   -- Outbound JSON responses (never leaking JPA entities)
│       ├── BookingResponseDto.java
│       ├── DeskRecommendationDto.java
│       ├── FloorDetailDto.java
│       └── AuthResponseDto.java
├── exception/           -- Robust error handling & Problem Details
│   ├── GlobalExceptionHandler.java -- @RestControllerAdvice RFC 7807 error handler
│   ├── ErrorResponseDto.java       -- Standardized RFC 7807 error payload
│   ├── DeskAlreadyBookedException.java
│   ├── AlreadyBookedException.java
│   ├── NoDeskAvailableException.java
│   ├── QuotaExceededException.java
│   ├── CutOffPassedException.java
│   ├── InvalidBookingDateException.java
│   ├── InvalidCheckInException.java
│   └── ResourceNotFoundException.java
├── model/               -- Rich Domain Entity models
│   ├── BaseEntity.java             -- @MappedSuperclass with id, audit timestamps, version
│   ├── Employee.java               -- User entity with role and timezone
│   ├── Team.java                   -- Team boundary configuration
│   ├── Floor.java                  -- Floor grid dimension, timezone, center seed coordinates
│   ├── Desk.java                   -- Desk coordinates (row, col), type (HOT, FIXED)
│   ├── TeamFloorQuota.java         -- Explicit team capacity per floor
│   ├── Booking.java                -- Reservation state machine (BOOKED, CHECKED_IN, CANCELLED, NO_SHOW)
│   └── enums/                      -- DeskType, BookingStatus, Role
├── repository/          -- Persistence layer with Spring Data JPA
│   ├── BookingRepository.java      -- Pessimistic locking queries (@Lock PESSIMISTIC_WRITE)
│   ├── DeskRepository.java         -- Floor spatial desk queries
│   ├── FloorRepository.java        -- Capacity and metadata queries
│   ├── TeamFloorQuotaRepository.java -- Quota row-locking queries
│   └── EmployeeRepository.java     -- User authentication queries
├── service/             -- Transactional Business Orchestration
│   ├── BookingService.java         -- Interface for single, walk-in, and team booking transactions
│   ├── DeskService.java            -- Desk availability and recommendation orchestration
│   ├── FloorService.java           -- Floor quotas and capacity headroom validation
│   ├── AuthService.java            -- Authentication & JWT issuance
│   ├── impl/                       -- Concrete service implementations (@Transactional)
│   └── scheduler/                  -- Resilient background workers
│       └── NoShowReleaseScheduler.java -- Automated idempotent grace-period sweep
├── strategy/            -- Strategy Pattern for Desk Allocation
│   ├── DeskAllocationStrategy.java -- Strategy interface for scoring candidate desks
│   ├── TeamNeighbourhoodStrategy.java -- Euclidean teammate proximity scoring
│   ├── CenterBasedStrategy.java    -- Deterministic floor centroid seed placement
│   └── AllocationStrategyFactory.java -- Dynamic strategy resolver
└── util/                -- Shared mathematical algorithms & utilities
    ├── SpatialMath.java            -- Squared Euclidean distance, centroid calculations
    ├── TimezoneUtil.java           -- Strict IANA timezone conversions & cutoff checks
    └── AppConstants.java           -- Centralized named constants (no magic values)
```

---

## Enterprise Evaluation Criteria & Interview Deep-Dive

This section maps directly to the **Evaluation Criteria (Plus Points)** outlined in the specification. These notes provide the complete technical rationale, complexity proofs, and architectural defenses needed for technical interviews.

```text
               ┌────────────────────────────────────────────────────────┐
               │    Enterprise Evaluation Criteria (Plus Points)        │
               └────────────────────────────────────────────────────────┘
                 │
                 ├── 1. Authentication & Security (Spring Security, JWT, RBAC)
                 ├── 2. Cost Estimation (Formal Time & Space Complexity)
                 ├── 3. System Failure Handling (ACID, Locks, Deadlock Prevention)
                 ├── 4. Object-Oriented Principles (Strategy, Polymorphism, SRP)
                 ├── 5. Documented Trade-Offs (Pessimistic vs Optimistic, etc.)
                 ├── 6. System Monitoring (Spring Actuator, Micrometer, MDC)
                 ├── 7. Caching Architecture (Redis 2-Tier, ACID Bypass Policy)
                 └── 8. Error & Exception Handling (RFC 7807 Problem Details)
```

---

### 1. Authentication & Security

#### Implementation Design
- **Protocol**: Stateless JSON Web Tokens (**JWT**) over HTTPS, integrated into **Spring Security 6**.
- **Password Protection**: Passwords hashed using `BCryptPasswordEncoder` with a work factor (log rounds) of `12`.
- **Role-Based Access Control (RBAC)**:
  - `ROLE_EMPLOYEE`: Can view available desks, book/cancel personal desks, and check in.
  - `ROLE_TEAM_COORDINATOR`: Elevated privilege to trigger atomic multi-person team bookings (`/api/bookings/team`).
  - `ROLE_ADMIN`: Ability to modify floor capacities, reassign fixed desks, and configure team quotas.
- **Security Chain**: Custom `JwtAuthenticationFilter` validates the bearer token signature, extracts identity claims (`sub` = `employeeId`, `role`), and populates the `SecurityContextHolder`.
- **Fine-Grained Authorization**: Applied at the method level using `@PreAuthorize("hasRole('TEAM_COORDINATOR')")`.

#### Interview Talking Points
> **Q: Why exclude `teamId` from JWT claims and query it from the database instead?**
> *"While `employeeId` and `role` are stable identity attributes suitable for stateless tokens, team membership is mutable. If an employee transfers from Team Alpha to Team Beta, a token containing `teamId: Alpha` would allow them to book against Alpha's quota until token expiration. By resolving current `team_id` from PostgreSQL (or cache-aside) inside the booking transaction, quota enforcement is guaranteed to be 100% fresh and accurate."*

---

### 2. Cost Estimation — Time and Space Complexity

A rigorous algorithmic complexity analysis across the core booking operations:

| Operation | Component / Routine | Time Complexity | Space Complexity | Explanation |
| :--- | :--- | :--- | :--- | :--- |
| **Desk Filtering** | `filterEligibleDesks` | $O(D)$ | $O(C)$ | Scans $D$ desks on a floor; filters out occupied, inactive, or restricted desks. Result is $C$ candidates ($C \le D \le 500$). |
| **Team Centroid** | `calculateCentroid` | $O(T)$ | $O(1)$ | Computes mean $(\bar{x}, \bar{y})$ of $T$ active teammate coordinates ($T \le 50$). |
| **Single Desk Recommendation** | `TeamNeighbourhoodStrategy` | $O(D + C \cdot T)$ | $O(C)$ | Filters $D$ desks ($O(D)$), then for each of $C$ candidate desks computes min teammate distance ($O(T)$) and centroid distance ($O(1)$). Identifies best desk deterministically ($O(C)$). |
| **Team Anchor-and-Expand** | Multi-seat group booking | $O(K \cdot C \log M)$ | $O(M)$ | Evaluates top $K=10$ anchor candidates. For each, selects $M-1$ closest desks using a min-heap or partial sort. |
| **Hierarchical Pessimistic Lock** | `SELECT ... FOR UPDATE` | $O(\log N)$ | $O(1)$ | B-tree index traversal on primary keys (Floor / Quota / Desk) in PostgreSQL ($N$ = total rows). |
| **Active Quota & Capacity Check** | `COUNT(...)` on active bookings | $O(\log N + K)$ | $O(1)$ | B-tree composite index range scan over active bookings on target floor/quota for the date ($K$ = matching rows). |
| **No-Show Auto Release Sweep** | Scheduled background worker | $O(B)$ | $O(1)$ | Direct indexed scan over overdue bookings (`WHERE status = 'BOOKED' AND check_in_deadline <= :nowUtc`). |

#### Mathematical Complexity & Latency Breakdown
- **Eliminating `Math.sqrt()`**: Using squared Euclidean distance $\Delta x^2 + \Delta y^2$ preserves strict spatial ordering without floating-point square root operations, running in a single clock cycle on modern CPU ALUs.
- **Microsecond Algorithm vs. Database Round-Trips**: For a standard enterprise floor with $D = 500$ desks, $T = 15$ teammates, and team booking size $M = 5$, the spatial ranking algorithm executes in **$< 1\text{ ms}$** in-memory. Total end-to-end transaction latency (network round-trips, floor/quota locks, fresh ranking query, desk lock, insert, commit) is typically **$10\text{--}30\text{ ms}$**.
- **Floor-Level Throughput**: A single floor comfortably supports **50–100 bookings/sec** under sustained peak contention, scaling horizontally across floors ($F \times 50\text{--}100\text{ TPS}$).

---

### 3. Handling System Failure Cases & Fault Tolerance

```text
       Contention Resolution & Dynamic In-Lock Ranking
       
  Thread A (Employee 1)                    Thread B (Employee 2)
           │                                        │
    1. BEGIN TX (READ COMMITTED)             1. BEGIN TX (READ COMMITTED)
           │                                        │
    2. LOCK Floor Row                        2. WAIT on Floor Row Lock
           │                                        :
    3. Verify Floor Hot Headroom                    :
    4. LOCK TeamFloorQuota Row                      :
    5. Verify Team Hot Quota                        :
    6. Fresh In-TX Ranking:                         :
       Rank 1 -> Desk 101                           :
           │                                        :
    7. LOCK Desk 101 (Available)                    :
    8. INSERT Booking (Desk 101)                    :
    9. COMMIT TX ──────────────────────────────────▶:
       (Releases Locks)                      2. ACQUIRES Floor Row Lock
                                             3. Verify Floor Hot Headroom
                                             4. ACQUIRES TeamFloorQuota Lock
                                             5. Verify Team Hot Quota
                                             6. Fresh In-TX Ranking:
                                                (Sees Desk 101 now BOOKED by Thread A)
                                                Rank 1 -> Desk 102 (Adjacent to Desk 101!)
                                             7. LOCK Desk 102 (Available)
                                             8. INSERT Booking (Desk 102)
                                             9. COMMIT TX (Success! Zero 409 Conflict!)
```

#### Fault-Tolerance Mechanisms

1. **Defensive Concurrency & Race Condition Elimination**:
   - **Floor & Quota Serialization Point (Design Trade-Off)**:
     Locking the parent `Floor` and `TeamFloorQuota` row serializes bookings on that specific floor during the critical section. This guarantees that aggregate invariants (team quotas and floor capacity headroom) are atomically verified.
   - **PostgreSQL Aborted Transaction Prevention**:
     In PostgreSQL, catching a `FOR UPDATE NOWAIT` failure or a unique violation marks the physical transaction aborted, preventing any further SQL statements from running. Our architecture avoids this pitfall completely: competing threads simply wait on the floor lock, acquire it, and run a **single fresh in-transaction ranking** against current DB state. Thread B seamlessly selects the next adjacent desk (Desk 102) without catching database exceptions or looping.
   - **Storage-Level Defense-in-Depth (Partial Unique Indexes)**:
     - `uq_active_desk_day`: `UNIQUE (desk_id, booking_date) WHERE status IN ('BOOKED', 'CHECKED_IN')` guarantees zero double-bookings even if application locking is bypassed. Crucially, partial indexing allows re-booking if a prior reservation is `CANCELLED` or `NO_SHOW`.
     - `uq_active_employee_day`: `UNIQUE (employee_id, booking_date) WHERE status IN ('BOOKED', 'CHECKED_IN')` guarantees an employee cannot hold two active bookings on the same date.
   - **Hierarchical Deadlock Elimination**:
     Forward booking transactions acquire locks in strict descending hierarchy: `Floor` $\to$ `TeamFloorQuota` $\to$ `Desk` (ordered ascending by `desk_id ASC`). Because every thread honors this identical sequence, circular wait conditions ($T_1 \to R_1 \to R_2$ vs $T_2 \to R_2 \to R_1$) are structurally eliminated. Cancellation and no-show releases operate as single-row updates on `bookings` (`UPDATE bookings SET status = ... WHERE id = :id`) and do not participate in multi-resource circular waits.

2. **Timezone-Proof Self-Healing Schedulers (Crash Recovery)**:
   - The `NoShowReleaseScheduler` does not store transient in-memory timers.
   - It queries state against pre-computed UTC `check_in_deadline` timestamps:
     ```sql
     UPDATE bookings 
     SET status = 'NO_SHOW', updated_at = :nowUtc
     WHERE status = 'BOOKED' 
       AND check_in_deadline <= :nowUtc;
     ```
   - **Check-in Race Prevention**: The update is conditioned on `status = 'BOOKED'`. If an employee checks in at 09:29:59 IST (`status = 'CHECKED_IN'`), the sweeper matches 0 rows, eliminating race conditions.
   - **Distributed Multi-Instance Safety**: Because the SQL statement is conditional and atomic at the row level, multiple application instances running the sweep simultaneously cannot double-process rows or corrupt state. Heavy distributed locking (ShedLock) is unnecessary.
   - **Testability**: All temporal decisions inject `java.time.Clock`, allowing deterministic time-travel assertions in automated tests.

3. **Transaction Boundary & Rollback**:
   - All booking methods are wrapped in `@Transactional(rollbackFor = Exception.class)`. Any infrastructure failure triggers an immediate clean rollback, preventing orphaned or partially written reservations.

4. **Target Production SLA & Disaster Recovery Blueprint**:
   - **PostgreSQL Write-Ahead Logging (WAL) & Archiving**: Synchronous commit to disk-backed WAL before transaction completion guarantees durability (D in ACID). Continuous WAL streaming to off-site disaster recovery storage prevents data loss.
   - **Point-In-Time Recovery (PITR)**: Enables rolling back the database to any specific microsecond prior to an operational anomaly or catastrophic failure.
   - **Automated Physical & Logical Backups**:
     - Daily full physical volume snapshots + nightly logical `pg_dump` exports.
     - Target SLA: **RPO (Recovery Point Objective) $\le 5$ minutes**, **RTO (Recovery Time Objective) $\le 15$ minutes**.
   - **Redis Cache Resilience**: Cache-aside decouples caching from correctness. Handled by a custom error handler, if Redis crashes, the application logs a warning and falls back directly to PostgreSQL with zero data loss and 100% booking correctness.

---

### 4. Object-Oriented Programming (OOPS) Principles

The implementation showcases clean OOP design and design patterns:

- **Encapsulation**:
  - Entity fields are strictly `private`. Business invariants (such as transitions from `BOOKED` $\to$ `CHECKED_IN` or `BOOKED` $\to$ `CANCELLED`) are protected inside domain methods on the `Booking` entity, preventing illegal state transitions.
  - Immutable Value Objects like `DeskCoordinate(int row, int col)` encapsulate coordinate distance logic.
- **Polymorphism & Strategy Pattern**:
  - The `DeskAllocationStrategy` interface exposes:
    ```java
    Desk allocate(List<Desk> availableDesks, AllocationContext context);
    ```
  - Polymorphic implementations:
    - `TeamNeighbourhoodStrategy`: Applied when active teammates are on the floor.
    - `CenterBasedStrategy`: Applied when seeding a new team or booking without teammates.
  - New strategies (e.g., `AccessibilityPriorityStrategy`, `ExecutiveZoneStrategy`) can be added without altering existing booking service code, adhering to the **Open/Closed Principle (OCP)**.
- **Inheritance & DRY**:
  - Shared domain properties (`id`, `createdAt`, `updatedAt`, `version`) are encapsulated in an abstract `@MappedSuperclass BaseEntity`.
- **Dependency Inversion Principle (DIP)**:
  - Controllers depend on Service interfaces; Services depend on Strategy interfaces and Repository interfaces, enabling complete decoupling and effortless mockability in unit tests.

---

### 5. Documented Trade-Offs

| Decision | Alternative Considered | Why We Chose This Solution | Interview Defense |
| :--- | :--- | :--- | :--- |
| **Pessimistic Locking** | Optimistic Locking (`@Version`) | Desk booking suffers from high contention surges (e.g., opening window at 9:00 AM). Optimistic locking causes transaction rollbacks, retry storms, and wasted CPU cycles. | *"Under high peak contention, pessimistic locking serializes the critical section cleanly. Because the transaction holds the lock only for a few milliseconds, lock wait time is negligible compared to repeated optimistic rollbacks."* |
| **Squared Euclidean Distance** | Graph-based A* routing / PostGIS | Graph routing requires modeling office walls, doors, and pathways. At a 500-desk scale, in-memory Euclidean distance computes in microseconds with no external GIS dependency. | *"Euclidean distance provides an intuitive, deterministic proxy for physical proximity. PostGIS or A* pathfinding adds infrastructure complexity without perceptible quality gain at 500 desks."* |
| **Two-Stage Allocation (Floor $\to$ Desk)** | Single 3D Optimization Model | Floor travel involves elevators/stairs (discrete barriers), whereas intra-floor distance is continuous. Comparing Floor 2 (Row 5) to Floor 3 (Row 5) geometrically is physically meaningless. | *"Decoupling floor selection from desk assignment ensures that floor quotas and team neighborhood coherence are enforced cleanly within the boundaries of a single physical floor."* |
| **Anchor-and-Expand Heuristic** | Integer Linear Programming (ILP) | Finding the absolute global minimum bounding circle for $M$ desks out of 500 is NP-hard. Brute force requires $\binom{500}{5} \approx 2.5 \times 10^{11}$ operations. | *"Anchor-and-expand with $K=10$ checks 10 best seed desks and finds adjacent neighbors in $O(K \cdot C \log M)$, returning an optimal-feeling cluster in $< 2\text{ ms}$ instead of seconds."* |

---

### 6. System Monitoring & Observability

Enterprise applications must provide deep visibility into operational health:

1. **Spring Boot Actuator Endpoints**:
   - `/actuator/health`: Liveness and readiness probes for container orchestrators (Kubernetes).
   - `/actuator/metrics`: JVM memory, thread pool exhaustion, DB connection pool (HikariCP) utilization.
   - `/actuator/info`: Git commit hash, build version, and environment details.
2. **Custom Domain Metrics via Micrometer**:
   - `smartdesk.bookings.total`: Counter tagged by `status` (`BOOKED`, `CONFLICT`, `REJECTED`).
   - `smartdesk.allocation.latency`: Timer measuring execution time of spatial algorithms.
   - `smartdesk.noshow.releases.total`: Counter tracking reclaimed capacity from no-shows.
   - `smartdesk.concurrency.conflicts`: Counter incremented whenever a thread encounters a locked desk.
3. **Structured Logging with MDC (Mapped Diagnostic Context)**:
   - Every incoming request generates a unique `traceId` and captures `employeeId`.
   - Log entries output JSON-formatted logs containing:
     ```json
     {
       "timestamp": "2026-09-23T10:15:30.123Z",
       "level": "INFO",
       "traceId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
       "employeeId": 42,
       "message": "Allocated desk D-104 for team 3 on floor 2. Latency: 1.4ms"
     }
     ```

---

### 7. Caching Strategy

The most important caching rule for this system:

> **Redis speeds up reads. PostgreSQL remains the single source of truth for booking and availability.**

This is critical because of our concurrency design. Stale cache must never influence whether a desk gets booked.

#### What We Cache vs. What We Don't

```text
                      Caching Decision Matrix

  ┌─────────────────────────────┬──────────────────────────────────┐
  │     CACHE IN REDIS ✅       │     NEVER CACHE ❌               │
  │                             │                                  │
  │  Floor metadata             │  Desk availability               │
  │  Desk layout/coordinates    │  Active bookings count           │
  │  Team information           │  Quota remaining at booking time │
  │  Employee → team mapping    │  Booking creation/commit         │
  │                             │  Pessimistic row locks           │
  └─────────────────────────────┴──────────────────────────────────┘
```

**Why not cache availability?** If Redis says "D42 → AVAILABLE" but Employee B just booked it, Employee A gets stale state. Our entire pessimistic locking mechanism becomes harder to reason about. Availability is always checked against PostgreSQL inside the booking transaction.

**Cache desk layout, not desk occupancy.** The desk layout cache stores *physical desk properties* (coordinates, type) — never occupancy status:

```json
[
  {"id": 10, "row": 2, "column": 3, "type": "HOT"},
  {"id": 11, "row": 2, "column": 4, "type": "HOT"},
  {"id": 20, "row": 3, "column": 3, "type": "FIXED", "reservedFor": 104}
]
```

Notice what's missing: `"available": true`. We deliberately exclude occupancy state from the cache.

#### Redis Key Structure

```text
Redis
│
├── floor:{floorId}              → floor metadata (capacity, name, number)
│
├── floor:{floorId}:desks        → desk layout (id, row, column, type)
│
├── employee:{employeeId}:team   → team membership
│
└── team:{teamId}                → team metadata (name, quota)
```

#### Cache-Aside Pattern

```text
GET floor:3
      │
      ▼
    Redis
   /     \
 HIT      MISS
 |          |
 ▼          ▼
return     PostgreSQL
             |
             ▼
          put Redis
             |
             ▼
           return
```

With Spring:

```java
@Cacheable(value = "floors", key = "#floorId")
public FloorDto getFloor(Long floorId) {
    return floorRepository.findById(floorId)
        .map(floorMapper::toDto)
        .orElseThrow(...);
}
```

#### TTL & Eviction Policy

| Cached Data | TTL | Eviction Trigger |
| :--- | ---: | :--- |
| Floor metadata | 30–60 min | Admin updates floor capacity or deactivates floor |
| Desk layout | 30–60 min | Admin adds/removes/moves desks |
| Team information | 15–30 min | Admin modifies team or quota config |
| Employee → team | 15–30 min | Employee transferred to another team |

Policy: **TTL + explicit invalidation** — not just one or the other. `@CacheEvict` fires on admin mutations; TTL provides a safety net for missed invalidations. Redis uses `volatile-lru` to prevent memory exhaustion.

#### What About Active Team Bookings?

Our allocation algorithm needs existing teammate coordinates. We *could* cache `team:10:bookings:2026-09-25`, but this data changes on every booking, cancellation, and no-show release. For a 500-desk assignment, querying PostgreSQL directly for active team bookings is completely reasonable and avoids invalidation complexity exactly where booking correctness matters most.

#### Where Redis Fits in the Booking Flow

```text
Booking Request
      │
      ▼
Floor metadata ─────────────── Redis (cached layout & coordinates)
      │
      ▼
Generate candidate list
      │
      ▼
PostgreSQL transaction ─────── DIRECT DB (READ COMMITTED)
      │
      ├─► Lock Floor Row (SELECT ... FOR UPDATE)
      │
      ├─► Verify floor hot capacity headroom
      │
      ├─► Resolve fresh team_id from Employee row (DIRECT DB, bypass token)
      │
      ├─► Lock TeamFloorQuota Row & verify team quota (DIRECT DB)
      │
      ├─► Query active teammate positions (DIRECT DB)
      │
      ├─► Fresh in-lock ranking: compute teammate proximity
      │
      ├─► Lock candidate desk (SELECT ... FOR UPDATE)
      │
      ├─► Insert booking (is_owner_booking, check_in_deadline)
      │
      ▼
COMMIT TX
```

Redis provides fast access to static floor layouts and desk coordinates. But **all mutable transactional state** — team membership, active bookings, quota locks, and seat availability — is resolved directly against PostgreSQL under parent row locks.

#### Cache Invalidation on Admin Updates

When an administrator changes desk layout or floor configuration:

```text
UPDATE DB
   ↓
Evict Redis key (@CacheEvict)
   ↓
Next request → cache miss → DB
   ↓
Repopulate cache
```

When somebody books a desk, we do **not** update `floor:{id}:desks` in Redis. That cache describes the *physical desk*, not its current occupancy. Booking events only affect PostgreSQL.

#### Handling Redis Outages Gracefully (`CustomCacheErrorHandler`)

By default, Spring Boot re-throws exceptions if Redis fails (`RedisConnectionFailureException`), which would cause client requests to crash even though data is intact in PostgreSQL. 

We configure a `CustomCacheErrorHandler extends SimpleCacheErrorHandler`:
```java
@Configuration
public class CacheConfig extends CachingConfigurerSupport {
    @Override
    public CacheErrorHandler errorHandler() {
        return new CustomCacheErrorHandler();
    }
}
```
- `handleCacheGetError`: Logs a warning and returns `null` (forcing a cache miss that transparently queries PostgreSQL).
- `handleCachePutError` & `handleCacheEvictError`: Logs a warning without aborting the write to PostgreSQL.

#### Protection Against Stale JWT Claims
While the signed JWT bearer token asserts the employee's identity (`sub = employeeId`), transactional quota and zone decisions do **not** trust token-embedded `teamId` blindly. Instead, the service verifies the current `team_id` against PostgreSQL (or cache-aside), preventing transferred employees from retaining stale quota privileges.

#### Interview Defense

> **Q: "Why cache desk layout but not availability?"**
> *"We cache relatively static, frequently accessed data — floor metadata, desk coordinates, and team definitions. We deliberately do not treat cached availability as authoritative because booking is concurrency-sensitive. Availability is revalidated inside a PostgreSQL transaction with row-level locking, so PostgreSQL remains the single source of truth. This gives us the performance benefit of caching without introducing stale-state bugs in the critical booking path."*

---

### 8. Error and Exception Handling Framework

#### Architectural Design
- Centralized exception management through `@RestControllerAdvice` (`GlobalExceptionHandler`).
- All error responses strictly implement the **RFC 7807 / RFC 9457 Problem Details** standard.

#### Standard Error Response Payload
```json
{
  "type": "https://smartdesk.company.com/errors/desk-already-booked",
  "title": "Desk Already Booked",
  "status": 409,
  "detail": "Desk 104 on Floor 2 was booked by another employee at 2026-09-23T10:15:00Z.",
  "instance": "/api/bookings",
  "errorCode": "DESK_CONFLICT",
  "timestamp": "2026-09-23T10:15:02.451Z"
}
```

#### Custom Domain Exception Taxonomy
- `DomainException` (Abstract Base)
  - `DeskAlreadyBookedException` $\to$ `409 Conflict` (Concurrent booking conflict mapped from partial unique index)
  - `NoDeskAvailableException` $\to$ `409 Conflict` (All candidate desks on the floor are exhausted)
  - `AlreadyBookedException` $\to$ `409 Conflict` (Employee already holds an active booking on that date)
  - `QuotaExceededException` $\to$ `422 Unprocessable Entity` (Team or floor quota headroom reached)
  - `CutOffPassedException` $\to$ `400 Bad Request` (Cancellation after cut-off time)
  - `InvalidBookingDateException` $\to$ `400 Bad Request` (Attempting to book outside advance window or after daily cut-off)
  - `InvalidCheckInException` $\to$ `400 Bad Request` (Attempting check-in outside grace window or after NO_SHOW)
  - `ResourceNotFoundException` $\to$ `404 Not Found` (Desk, Floor, or Employee ID does not exist)
  - `UnauthorizedOperationException` $\to$ `403 Forbidden` (Non-coordinator attempting team booking)

---

### Summary Checklist for Technical Interview Defense

When asked to explain this system in an interview:

1. **Start with the Core Problem**: *"In a hybrid office, employees want to sit together without seat hoarding or race conditions during morning peak hours."*
2. **Explain the 2-Stage Allocation**: *"We separate Floor Selection (hard business rules/quotas) from Desk Selection (spatial 2D teammate proximity). This keeps the spatial math focused and deterministic."*
3. **Highlight Concurrency Defense**: *"We don't rely on application flags alone. We enforce hierarchical locking (Floor/Quota $\to$ Desk ASC) backed by storage-level partial unique indexes (`uq_active_desk_day` and `uq_active_employee_day`). Dynamic re-evaluation inside the locked section eliminates stale placement without failed-transaction traps."*
4. **Walk Through Time & Space**: *"Filtering reduces candidates to $C \le 500$. In-memory squared Euclidean distance runs in $O(D + C \cdot T)$ in low single-digit milliseconds. For teams, anchor-and-expand with $K=10$ gives near-optimal clusters in $O(K \cdot C \log M)$ without NP-hard complexity."*
5. **Demonstrate Production Readiness**: *"The architecture is fully decoupled (Strategy pattern, DTO separation, RFC 7807 error responses, Redis caching with CustomCacheErrorHandler, Spring Actuator metrics, idempotent background sweeps, and Flyway database migrations)."*

---

## Implementation Status & Delivery Roadmap

To ensure total transparency between built deliverables and enterprise production blueprints:

| Feature / Architectural Component | Implementation Status | Scope / Test Coverage | Notes |
| :--- | :--- | :--- | :--- |
| **Flyway Database Migrations** | ✅ **Core Built** | `V1__init_schema.sql` covering tables, partial unique indexes, foreign keys, and check constraints. | Complete schema automation on boot. |
| **Storage-Level Defense-in-Depth** | ✅ **Core Built** | `uq_active_desk_day`, `uq_active_employee_day`, `chk_desk_fixed_owner`. | Validated with PostgreSQL Testcontainers. |
| **Floor & Quota Locking Serialization** | ✅ **Core Built** | Parent `Floor` & `TeamFloorQuota` row-level locks (`SELECT ... FOR UPDATE`). | Eliminates over-allocation races on aggregate quotas. |
| **In-Lock Fresh Spatial Ranking** | ✅ **Core Built** | `TeamNeighbourhoodStrategy` & `CenterBasedStrategy` re-evaluated under lock. | Eliminates stale ranking and aborted-transaction errors. |
| **Continuous Anytime Booking Lifecycle** | ✅ **Core Built** | Advance 14 business days + on-day pre-slot booking at any time. | Enforced in transactional application services. |
| **Dynamic Same-Day Grace Deadline** | ✅ **Core Built** | $\max(\text{start} + \text{grace}, \text{bookedAt} + \text{walkInGrace})$. | Prevents instant expiration of mid-day bookings. |
| **Timezone & Window Cut-Off Boundaries** | ✅ **Core Built** | India Standard Time (`Asia/Kolkata`), strict exclusive boundary (`requestTime < cutoffTime`). | Driven by injected `java.time.Clock`. |
| **Idempotent No-Show Auto-Release** | ✅ **Core Built** | Conditional atomic SQL: `UPDATE bookings SET status='NO_SHOW' WHERE ...`. | Crash-resilient; no distributed lock dependency needed. |
| **RFC 7807 Error Handling & Taxonomy** | ✅ **Core Built** | `@RestControllerAdvice` mapping custom domain exceptions to Problem Details JSON. | Full exception taxonomy implemented. |
| **Multi-Threaded Testcontainers Suite** | ✅ **Core Built** | 2-thread contention, 2-thread adjacent, 100-thread capacity, double-submit, and quota-after-no-show. | Real PostgreSQL 15 integration tests. |
| **Spring Boot Actuator & Micrometer** | ✅ **Core Built** | Health probes, HikariCP metrics, custom booking counters and timers. | Observable production instrumentation. |
| **Zero-Dependency Local Profile** | ✅ **Core Built** | `local` profile with in-memory `ConcurrentHashMap` cache manager. | Allows running without local Redis installation. |
| **Redis 2-Tier Caching Layer** | 🔷 **Production Blueprint** | Cache-aside layout caching with `CustomCacheErrorHandler` fallback. | Blueprint documented; local profile provides Redis-free operation. |
| **Atomic Multi-Member Team Booking** | 🔷 **Production Blueprint** | Coordinator `/api/bookings/team` anchor-and-expand group reservation. | Algorithmic logic and deadlock order designed. |
| **WAL Archiving & PITR Disaster Recovery**| 🔷 **Production Blueprint** | Continuous WAL archiving, physical snapshots, RPO $\le 5$m, RTO $\le 15$m. | Operational runbook documented. |

---

## Coding Standards & Guidelines

All codebase additions adhere strictly to the following standards:

### 1. Naming Conventions

| Identifier Type | Case Style | Example | Rule / Intent |
| :--- | :--- | :--- | :--- |
| **Classes & Interfaces** | `PascalCase` | `UserAccount`, `PaymentProcessor`, `DeskAllocationService` | Use nouns or noun phrases representing concepts or entities. |
| **Methods** | `camelCase` | `calculateTotal()`, `fetchData()`, `allocateDesk()` | Use verbs or verb phrases that describe actions or queries. |
| **Variables & Fields** | `camelCase` | `totalAmount`, `isPremiumUser`, `bookingRepository` | Use meaningful nouns; avoid single letters except in loop indices (`i`, `j`). |
| **Constants** | `UPPER_SNAKE_CASE` | `MAX_LOGIN_ATTEMPTS`, `DEFAULT_FLOOR_CAPACITY` | Must be declared with `static final` modifiers. |
| **Packages** | `lowercase` | `com.anurag.smartdesk.booking`, `com.company.project.module` | Reverse internet domain name, strictly all lowercase without underscores. |

### 2. Formatting & Layout

- **Braces**: Egyptian brackets style (`public void executeTask() { ... }`).
- **Indentation**: 4 spaces per block level (Oracle standard). No tabs.
- **Line Scope**: 80–100 characters max line length; exactly one statement per line.

### 3. Local Variables & Scope

- **Declare and Initialize Together**: Declare variables in the smallest possible scope right where first needed.
- **Encapsulation**: Private instance variables. Use `is...` for boolean getters (e.g., `isActive()`).

### 4. Code Structure & Commenting

- **Short Methods**: Under 20–50 lines; strictly adhere to Single Responsibility Principle.
- **Avoid Magic Values**: Extract raw numbers and literals to named `static final` constants.
- **Comments**:
  - `//` for normal inline explanations (explaining *why*, not *what*).
  - `/* ... */` for longer algorithmic and mathematical explanations.
  - `/** ... */` Javadoc on public APIs and interfaces.

---

*This README documents the design as of the initial implementation. It will be updated as features are built and tested.*
