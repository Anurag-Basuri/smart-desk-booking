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
- [How to Run](#how-to-run)
- [Testing](#testing)

---

## How It Works

An authenticated employee opens the system and sees available desks for a given date and floor.

The system doesn't hand out desks randomly. It recommends desks based on where the employee's teammates are already sitting that day. If no teammates have booked yet, it picks a desk near the center of the floor — a deterministic starting point that's reproducible and easy to test.

The flow looks like this:

```
Employee logs in
    |
    v
Selects floor + date
    |
    v
System finds all eligible desks
    |
    v
Has teammates booked on this floor today?
    |
    +-- Yes --> Recommend desks near teammates
    |
    +-- No  --> Recommend desks near the floor center
    |
    v
Employee picks a desk (or accepts recommendation)
    |
    v
System locks the desk row, re-checks availability, books it
```

If the employee has a fixed/reserved desk on that floor, the system simply confirms their reserved desk — no algorithm needed.

---

## Desk Types

The system supports two types:

### Hot Desks

Shared pool. Any eligible employee can book any available hot desk, subject to quotas and capacity limits.

```
AVAILABLE  -->  BOOKED  -->  CHECKED_IN
                  |
                  v
              CANCELLED  (employee cancels before cut-off)
                  or
              NO_SHOW    (grace period expires without check-in)
                  |
                  v
              RELEASED   (desk returns to available pool)
```

### Fixed Desks

Reserved for a specific named employee. A fixed desk does not become a hot desk just because its owner hasn't booked it today.

```
RESERVED_FOR_EMPLOYEE
    |
    +-- Employee books it   --> BOOKED --> CHECKED_IN
    |
    +-- Employee cancels    --> RELEASED (temporarily available as hot desk)
    |
    +-- Employee no-shows   --> NO_SHOW --> RELEASED (after grace period)
    |
    +-- Employee doesn't book at all --> Desk stays reserved, NOT available to others
```

The important rule: a fixed desk is **protected** for its named employee. It only enters the hot desk pool if the employee explicitly releases it or fails to show up past the grace period. We never let someone else grab it just because the owner hasn't clicked "book" yet — that would cause conflicts when the owner walks in.

---

## Booking Rules

These are the hard rules. They must always be satisfied — no exceptions, no overrides from the algorithm.

| Rule | Detail |
|------|--------|
| One active booking per employee per date | An employee cannot hold two desks on the same day. Must cancel the first before booking another. |
| Desk availability | The desk must not already be booked by someone else for that date. |
| Desk type check | An employee can only book a fixed desk that is reserved for them specifically. Hot desks are open to anyone. |
| Team quota | If a cap exists (e.g., "Team A ≤ 8 desks on Floor 3"), the booking must not exceed it. |
| Floor capacity | If a floor has a max capacity (e.g., "Floor 3 max 60 people"), the booking must not exceed it. |
| Cancellation cut-off | Cancellations are allowed only before a defined cut-off time, interpreted in the employee's timezone. |
| No overlapping bookings for a desk | Enforced at the database level — a unique constraint on (desk_id, booking_date) prevents any code path from creating duplicates. |

These rules are enforced in the booking service **and** backed by database constraints. Even if someone bypasses the UI and sends raw POST requests, the database will reject invalid bookings.

---

## Team Neighbourhood Placement — The Algorithm

This is the core algorithmic piece. The goal: when an employee books a desk, prefer desks that are physically close to where their teammates are already sitting.

We are solving a **constrained spatial seat-assignment problem**, not a generic clustering problem. We don't use K-means, DBSCAN, or any ML-based approach. The team membership is already known from the database — we don't need to discover clusters.

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

No teammates booked yet, so there's no neighbourhood to grow. We fall back to:

> Pick the eligible desk closest to the center of the floor.

This gives the team a deterministic starting point. As more teammates book, the neighbourhood algorithm takes over.

To avoid multiple teams all seeding at the exact same spot, we add a small penalty for local occupancy — if the area around a desk is already heavily used, it scores slightly worse. This naturally distributes team seeds across the floor without any randomness.

### Team Booking — Booking Multiple Desks at Once

A designated team coordinator can book desks for multiple team members in a single request. The algorithm here is **anchor-and-expand**:

1. Determine how many hot desks are needed (fixed-desk employees are handled separately).
2. Find all eligible candidate desks on the floor.
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
| Individual booking | O(D × T) where D = eligible desks, T = team bookings | ≤ 500 × 500 = 250K distance calculations (worst case, usually much less) |
| Team booking | O(K × D × log D) | K=10, D ≤ 500 |

Both are well within real-time for a 500-desk floor.

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

This is the heart of the problem. Two employees tapping "book" on the last free desk at the same instant must not both succeed.

### The Approach: Pessimistic Row-Level Locking

We use `SELECT ... FOR UPDATE` inside a database transaction. Here's the exact sequence:

```
BEGIN TRANSACTION
    |
    v
Run allocation algorithm --> preferred desk = D10
    |
    v
SELECT * FROM desks WHERE id = D10 FOR UPDATE    <-- locks the row
    |
    v
Re-check: is D10 still available?
    |
    +-- Yes --> INSERT booking, COMMIT
    |
    +-- No  --> Try next-best candidate from the ranked list
```

The **re-check after acquiring the lock** is essential. While we were waiting for the lock, another transaction may have booked the desk. We never trust the algorithm's earlier availability check — the database state after locking is the only source of truth.

### Why pessimistic locking?

The scarce resource is the desk itself. When there's genuine contention (the last few desks on a popular floor), pessimistic locking serializes the competing requests cleanly. The loser waits briefly, then discovers the desk is taken and falls back to the next candidate.

Optimistic locking (version columns + retry) would also work, but under real contention it leads to more retries and wasted work. For desk booking — where the conflict window is small and the stakes are clear — pessimistic locking is simpler to reason about.

### What the loser sees

The losing request does **not** fail with a generic error. It falls back to the next-best candidate from the ranked list:

```
for each candidate in rankedCandidates:
    acquire lock on candidate
    if candidate is still available:
        create booking
        return success

throw NoDeskAvailableException   // only if ALL candidates are taken
```

For a 500-desk floor, there are usually plenty of alternatives.

### Database Constraint as a Safety Net

On top of the application-level locking, we add a unique constraint:

```sql
UNIQUE (desk_id, booking_date)
```

This is not instead of locking — it's alongside it. If any future code path somehow bypasses the lock-and-recheck pattern, PostgreSQL will still reject the duplicate. Defense in depth.

### Team Booking and Deadlock Prevention

When booking multiple desks for a team, we lock all selected desk rows inside one transaction. To prevent deadlocks when two team bookings happen simultaneously, we always acquire locks in **ascending desk ID order**:

```
Lock D10 --> Lock D11 --> Lock D12 --> Lock D20 --> Lock D21
```

Never in random or reversed order. Consistent lock ordering eliminates circular waits.

Team bookings are atomic: either all desks are booked or none are. We don't partially book a team.

### What "first" means

We define the winner as: **the transaction that successfully acquires the lock and commits first**. Not whoever tapped their phone 3 milliseconds earlier — we can't reliably determine UI tap order across network requests. The database's transaction scheduler is the arbiter.

Team proximity is a **soft preference**, not a reservation. Any eligible employee can compete for any eligible desk, and the first transaction to lock and commit wins. If the preferred desk is taken while waiting, the service re-evaluates the remaining candidates.

---

## No-Show Detection and Auto-Release

A booked desk that sits empty is wasted. The system detects no-shows and releases the desk back to the available pool.

### How It Works

```
Desk booked for 09:00
    |
    v
Grace period = 30 minutes (configurable)
    |
    v
09:30 arrives, employee hasn't checked in
    |
    v
Booking status --> NO_SHOW
    |
    v
Desk --> RELEASED back to the pool
```

For **fixed desks**, the same rule applies. A fixed desk is protected for its named employee, but if the employee doesn't show up within the grace period, the desk is released. Otherwise a fixed desk for an absent employee would sit empty all day while colleagues can't find seats.

The three ways a reserved desk gets released:
1. Employee explicitly cancels before the cut-off
2. Employee checks in (desk remains occupied as intended)
3. Grace period expires without check-in → auto-released

### Interaction with Quotas

A released desk doesn't blindly become available. Before the system re-offers it, it still checks:
- Does the requesting employee's team exceed their quota on this floor?
- Does the floor exceed its max capacity?

The no-show release mechanism feeds released desks back through the same eligibility pipeline as any other available desk.

### Implementation

A scheduled job runs periodically (e.g., every 5 minutes) to scan for bookings past their grace period that haven't been checked in. It marks them as NO_SHOW and releases the desk.

---

## Quotas and Capacity

The system enforces two kinds of limits:

### Team Quotas

"Team A can have at most 8 desks on Floor 3."

Checked before every booking. If Team A already has 8 active bookings on Floor 3, the 9th attempt is rejected — regardless of what the algorithm recommends.

### Floor Capacity

"Floor 3 can hold at most 60 people."

Checked before every booking. If 60 desks are already booked on Floor 3, no more bookings are accepted.

Both checks happen **inside the transaction**, after acquiring the lock and before creating the booking. This ensures the counts are accurate even under concurrent requests.

---

## Timezones and Cut-Off Windows

Booking cut-offs and time windows are interpreted in the **employee's own timezone**.

For example, if the cut-off for same-day booking is 8:00 AM:
- An employee in IST (UTC+5:30) must book before 8:00 AM IST
- An employee in PST (UTC-8) must book before 8:00 AM PST

All timestamps are stored in UTC in the database. Timezone conversion happens at the application layer when evaluating cut-off rules.

Edge case: a booking request arriving exactly at the cut-off time is treated as **past the cut-off** (strictly less than, not less-than-or-equal). This makes the behavior predictable at the boundary.

---

## Assumptions

These are decisions we made where the assignment didn't prescribe a specific answer. They're listed here so you know exactly where we exercised judgment.

> [!NOTE]
> Items marked with **[ASSUMPTION]** are not explicitly stated in the case study. They are reasonable design choices we made to keep the scope focused on the core challenges: concurrency, neighbourhood placement, quotas, and no-shows.

| # | Assumption | Why |
|---|-----------|-----|
| 1 | **[ASSUMPTION]** A booking covers a full workday slot (e.g., 09:00–18:00), not arbitrary time ranges. | The assignment says "date (and time window)" but doesn't specify the granularity. Arbitrary sub-hour windows would turn desk availability into a temporal interval-overlap problem, which distracts from the main challenges. We make the window configurable. |
| 2 | **[ASSUMPTION]** One active booking per employee per date. | Not stated in the PDF, but a sensible business rule. An employee shouldn't hold two desks on the same day. Enforced at the database level. |
| 3 | **[ASSUMPTION]** Team booking is initiated by a designated team coordinator, not by any team member for others. | The PDF mentions employees booking desks — it doesn't describe team-wide booking. We added this feature to demonstrate the neighbourhood algorithm for groups. Restricting it to a coordinator avoids conflicts (e.g., two people simultaneously booking different desks for the same teammate). |
| 4 | **[ASSUMPTION]** No manual "ungrouping" operation. | The team's neighbourhood is derived dynamically from active bookings. When a booking is cancelled, that desk simply stops being part of the neighbourhood. No explicit regroup/ungroup needed. |
| 5 | **[ASSUMPTION]** Deterministic allocation (not random). | The PDF doesn't require random allocation. Deterministic allocation is reproducible, testable, and easier to explain. Same inputs always produce the same recommendation. |
| 6 | **[ASSUMPTION]** A fixed desk that is not booked by its owner stays reserved — it does not become a hot desk. | The PDF says fixed desks are "reserved for a named person." We interpret this strictly: the desk only enters the hot pool through explicit cancellation or no-show auto-release, not by default. |
| 7 | **[ASSUMPTION]** The grace period for no-show detection is configurable (default: 30 minutes). | The PDF requires auto-release after a grace period but doesn't specify the duration. |
| 8 | **[ASSUMPTION]** "Exactly at the cut-off" counts as past the cut-off. | The PDF says to handle cut-off edge cases predictably. We chose strict-less-than. |
| 9 | **[ASSUMPTION]** Floor center is used as the seed point for teams with no existing bookings. | The PDF doesn't specify where to start placing a new team. Center-based seeding is deterministic and distributes teams naturally. |
| 10 | **[ASSUMPTION]** Scoring weights (α for team proximity, β for compactness) are configuration constants, not exposed in the API. | Internal tuning knobs, not user-facing settings. |

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

```
employees
---------
id              BIGINT PK
name            VARCHAR
email           VARCHAR UNIQUE
team_id         BIGINT FK -> teams
timezone        VARCHAR          -- e.g. "Asia/Kolkata"
is_coordinator  BOOLEAN          -- can make team bookings
```

```
teams
-----
id              BIGINT PK
name            VARCHAR
```

```
floors
------
id              BIGINT PK
name            VARCHAR
max_capacity    INT              -- e.g. 60
center_row      INT              -- floor center coordinates
center_col      INT              -- for seed placement
```

```
desks
-----
id              BIGINT PK
floor_id        BIGINT FK -> floors
row_pos         INT              -- x coordinate on floor map
col_pos         INT              -- y coordinate on floor map
desk_type       ENUM('HOT', 'FIXED')
reserved_for    BIGINT FK -> employees (nullable, only for FIXED desks)
is_active       BOOLEAN          -- can be set false for maintenance
```

```
bookings
--------
id              BIGINT PK
desk_id         BIGINT FK -> desks
employee_id     BIGINT FK -> employees
booking_date    DATE
start_time      TIME
end_time        TIME
status          ENUM('BOOKED', 'CHECKED_IN', 'CANCELLED', 'NO_SHOW', 'RELEASED')
created_at      TIMESTAMP
checked_in_at   TIMESTAMP (nullable)
cancelled_at    TIMESTAMP (nullable)

UNIQUE (desk_id, booking_date)  -- prevents double booking at DB level
```

```
team_floor_quotas
-----------------
id              BIGINT PK
team_id         BIGINT FK -> teams
floor_id        BIGINT FK -> floors
max_desks       INT              -- e.g. 8
```

### ER Relationships

```
teams 1---* employees
floors 1---* desks
employees 1---* bookings
desks 1---* bookings
teams *---* floors (through team_floor_quotas)
desks *---1 employees (reserved_for, nullable)
```

---

## How to Run

### Prerequisites

- Java 17+
- PostgreSQL 14+
- Maven 3.8+

### Setup

1. Clone the repository:
   ```bash
   git clone https://github.com/<your-username>/smart-desk-booking.git
   cd smart-desk-booking
   ```

2. Create the database:
   ```sql
   CREATE DATABASE smart_desk_booking;
   ```

3. Configure the database connection in `src/main/resources/application.properties`:
   ```properties
   spring.datasource.url=jdbc:postgresql://localhost:5432/smart_desk_booking
   spring.datasource.username=your_username
   spring.datasource.password=your_password
   ```

4. Run the application:
   ```bash
   ./mvnw spring-boot:run
   ```

The application starts on `http://localhost:8080`.

### Sample Data

The application ships with a data initializer that creates a sample floor with desks, a few teams, and employees for quick testing.

---

## Testing

### Unit Tests

```bash
./mvnw test
```

Key test areas:
- **Allocation algorithm**: given a set of team bookings and available desks, does the algorithm return the expected desk?
- **Quota enforcement**: booking rejected when team/floor limit is reached
- **Cut-off enforcement**: cancellation rejected after the cut-off time

### Concurrency Test

The most important test. We prove that two simultaneous booking requests for the last available desk result in exactly one winner:

```java
@Test
void twoSimultaneousBookings_onlyOneWins() {
    // Setup: one available desk, two employees
    // Launch two threads, both try to book the same desk
    // Assert: exactly one booking exists in the database
    // Assert: the other employee received a fallback or rejection
}
```

This test uses `CountDownLatch` to synchronize two threads and verify the pessimistic locking behavior under real concurrency.

### Integration Tests

- Book → Cancel → Re-book flow
- No-show auto-release after grace period
- Team booking: all-or-nothing atomicity
- Timezone cut-off edge cases

---

## Project Structure

```
src/main/java/com/.../smartdeskbooking/
├── controller/          -- REST controllers
├── service/             -- Business logic
│   ├── BookingService
│   └── NoShowScheduler
├── strategy/            -- Desk allocation strategies
│   ├── DeskAllocationStrategy (interface)
│   ├── TeamNeighbourhoodStrategy
│   └── CenterBasedStrategy
├── model/               -- JPA entities
│   ├── Employee
│   ├── Team
│   ├── Floor
│   ├── Desk
│   └── Booking
├── repository/          -- Spring Data JPA repositories
├── dto/                 -- Request/Response DTOs
├── exception/           -- Custom exceptions
└── config/              -- App configuration
```

---

*This README documents the design as of the initial implementation. It will be updated as features are built and tested.*
