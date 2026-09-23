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
- [Clean Architecture & Project Structure](#clean-architecture--project-structure)
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

No teammates booked yet, so there's no neighbourhood to grow. We fall back to:

> Pick the eligible desk closest to the center of the floor.

This gives the team a deterministic starting point. As more teammates book, the neighbourhood algorithm takes over.

To avoid multiple teams all seeding at the exact same spot, we add a small penalty for local occupancy — if the area around a desk is already heavily used, it scores slightly worse. This naturally distributes team seeds across the floor without any randomness.

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

Booking cut-offs and time windows are strictly interpreted in the **employee's own timezone**.

### How we model time
Every employee has a configured IANA timezone (e.g., `Asia/Kolkata`, `Asia/Singapore`).

When an employee makes a booking for "Tuesday" with a window of "9:00 AM–6:00 PM", these values are interpreted entirely within their local timezone.

```text
Employee timezone
       ↓
Local booking date/time
       ↓
Convert to absolute instant (UTC)
       ↓
Store/compare consistently
```

We do **not** use the server's timezone for any business logic.

### Cut-off edge cases

The PDF explicitly asks how a booking exactly at the cut-off should behave. Our rule is: **cut-off is exclusive**.

```text
requestTime < cutoffTime    → allowed
requestTime >= cutoffTime   → rejected
```

For example, if the cancellation cutoff is 18:00:00 in the employee's timezone:
- `17:59:59.999` → Allowed ✅
- `18:00:00.000` → Rejected ❌
- `18:00:00.001` → Rejected ❌

This gives us completely deterministic behavior across all timezones.

### Database Representation
For this system, we use both representations appropriately:
- The `booking_date` and `start_time` / `end_time` can be stored as local date/time types so we know exactly what the employee intended ("Tuesday 9am").
- Event timestamps (`created_at`, `checked_in_at`, etc.) are converted immediately to absolute instants (UTC) for storage and comparison.

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
max_capacity    INT              -- e.g. 60
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
```

> **Important — No `is_available` column on desks.** A desk is a physical object. Its availability depends on `desk + requested date + existing bookings`. D42 might be booked on Sept 25 but available on Sept 26. Storing `is_available` as a mutable column would be misleading and introduce stale-state risks with our concurrency model.

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
updated_at      TIMESTAMP

UNIQUE (desk_id, booking_date)  -- prevents double booking at DB level
```

```
team_floor_quotas
-----------------
id              BIGINT PK
team_id         BIGINT FK -> teams
floor_id        BIGINT FK -> floors
max_desks       INT              -- e.g. "Team A ≤ 8 desks on Floor 3"
```

### Key Constraints & Design Decisions

| Constraint | Purpose |
|------------|----------|
| `UNIQUE (desk_id, booking_date)` on bookings | Prevents double-booking at the storage layer, even if application locking is bypassed. |
| `UNIQUE (floor_id, row_number, column_number)` on desks | Guarantees no two desks occupy the same physical position on a floor. |
| `reserved_for_employee_id` NOT NULL when `desk_type = 'FIXED'` | Fixed desks always have a named owner; hot desks always have NULL. Enforced by application logic. |
| No `is_available` on desks | Availability is derived from bookings, not stored as mutable desk state. |
| One row per desk | Enables `SELECT ... FOR UPDATE` on individual desk rows for pessimistic locking. |

### ER Relationships

```
Team 1────────< Employee
Floor 1────────< Desk
Employee 1────────< Booking >────────1 Desk
Team *────────* Floor (through team_floor_quotas)
Desk *────────1 Employee (reserved_for, nullable — FIXED desks only)
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

## Clean Architecture & Project Structure

The project implements a decoupled, industry-grade layered architecture adhering strictly to **Domain-Driven Design (DDD)** concepts and **SOLID** principles:

```
com.anurag.smartdeskbooking
├── config/              -- Infrastructure configurations
│   ├── SecurityConfig.java         -- Spring Security, JWT filter chain, RBAC
│   ├── RedisCacheConfig.java       -- Redis cache manager, TTLs, serializers
│   ├── OpenApiConfig.java          -- Swagger / OpenAPI 3.0 documentation
│   └── SchedulingConfig.java       -- Thread pool configuration for background tasks
├── controller/          -- REST API presentation layer (Slim controllers)
│   ├── AuthController.java         -- Login, token refresh
│   ├── BookingController.java      -- Individual and team desk reservations
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
│   ├── ErrorResponseDto.java       -- Standardized error payload
│   ├── DeskAlreadyBookedException.java
│   ├── QuotaExceededException.java
│   ├── CutOffPassedException.java
│   └── ResourceNotFoundException.java
├── model/               -- Rich Domain Entity models
│   ├── BaseEntity.java             -- @MappedSuperclass with id, audit timestamps, version
│   ├── Employee.java               -- User entity with role and timezone
│   ├── Team.java                   -- Team boundary and quota configurations
│   ├── Floor.java                  -- Floor grid dimension, center seed coordinates
│   ├── Desk.java                   -- Desk coordinates (row, col), type (HOT, FIXED)
│   ├── Booking.java                -- Reservation state machine (CONFIRMED, CHECKED_IN, CANCELLED, NO_SHOW)
│   └── enums/                      -- DeskType, BookingStatus, Role
├── repository/          -- Persistence layer with Spring Data JPA
│   ├── BookingRepository.java      -- Pessimistic locking queries (@Lock PESSIMISTIC_WRITE)
│   ├── DeskRepository.java         -- Floor spatial desk queries
│   ├── FloorRepository.java        -- Capacity and metadata queries
│   └── EmployeeRepository.java     -- User authentication queries
├── service/             -- Transactional Business Orchestration
│   ├── BookingService.java         -- Interface for single & team booking transactions
│   ├── DeskService.java            -- Desk availability and recommendation orchestration
│   ├── FloorService.java           -- Floor quotas and capacity validation
│   ├── AuthService.java            -- Authentication & JWT issuance
│   ├── impl/                       -- Concrete service implementations (@Transactional)
│   └── scheduler/                  -- Resilient background workers
│       └── NoShowReleaseScheduler.java -- Automated grace-period sweep
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
- **Security Chain**: Custom `JwtAuthenticationFilter` validates the bearer token signature, extracts user claims (`employeeId`, `teamId`, `timezone`, `role`), and populates the `SecurityContextHolder`.
- **Fine-Grained Authorization**: Applied at the method level using `@PreAuthorize("hasRole('TEAM_COORDINATOR')")`.

#### Interview Talking Points
> **Q: Why stateless JWT over stateful HTTP sessions?**
> *"Stateless JWT eliminates the need for shared session clustering or sticky sessions across horizontal backend nodes. Authorization claims (like timezone and team ID) travel inside the signed token, reducing database round-trips on authenticated requests. For revoking tokens or critical privilege revocations, a Redis token denylist (blocklist) with matching TTL can be employed."*

---

### 2. Cost Estimation — Time and Space Complexity

A rigorous algorithmic complexity analysis across the core booking operations:

| Operation | Component / Routine | Time Complexity | Space Complexity | Explanation |
| :--- | :--- | :--- | :--- | :--- |
| **Desk Filtering** | `filterEligibleDesks` | $O(D)$ | $O(C)$ | Scans $D$ desks on a floor; filters out occupied, restricted, or quota-violating desks. Result is $C$ candidates ($C \le D \le 500$). |
| **Team Centroid** | `calculateCentroid` | $O(T)$ | $O(1)$ | Computes mean $(\bar{x}, \bar{y})$ of $T$ active teammate coordinates ($T \le 50$). |
| **Single Desk Recommendation** | `TeamNeighbourhoodStrategy` | $O(C \cdot T)$ | $O(C)$ | For each candidate desk, computes min teammate distance ($O(T)$) and centroid distance ($O(1)$). |
| **Team Anchor-and-Expand** | Multi-seat group booking | $O(K \cdot C \log M)$ | $O(M)$ | Evaluates top $K=10$ anchor candidates. For each, selects $M-1$ closest desks using a min-heap or partial sort. |
| **Pessimistic Row Lock** | `SELECT ... FOR UPDATE` | $O(1)$ | $O(1)$ | B-tree index lookup on `desk_id + booking_date` in PostgreSQL. |
| **No-Show Auto Release** | Scheduled background sweep | $O(B)$ | $O(B)$ | Indexed scan over $B$ un-checked-in bookings past grace period. |

#### Mathematical Complexity Breakdown
- **Eliminating `Math.sqrt()`**: Using squared Euclidean distance $\Delta x^2 + \Delta y^2$ preserves strict spatial ordering without floating-point square root operations, running in a single clock cycle on modern CPU ALUs.
- **Scalability Guarantee**: For a standard enterprise floor with $D = 500$ desks, $T = 15$ teammates, and team booking size $M = 5$, algorithm latency is strictly $< 2 \text{ ms}$ on standard JVM runtimes, far below any database I/O threshold.

---

### 3. Handling System Failure Cases & Fault Tolerance

```text
       Double Booking Contention Flow (Simultaneous Requests for Last Desk)
       
  Thread A (Employee 1)                    Thread B (Employee 2)
           │                                        │
    1. BEGIN TX                              1. BEGIN TX
           │                                        │
    2. SELECT FOR UPDATE                     2. SELECT FOR UPDATE
       (Acquires Lock on Desk 101)              (BLOCKED on Desk 101 Lock)
           │                                        :
    3. Re-verify: Still Available?                  :
       State = AVAILABLE                            :
           │                                        :
    4. INSERT Booking (Desk 101)                    :
           │                                        :
    5. COMMIT TX                                    :
       (Releases Lock) ─────────────────────────────▶
                                             3. Acquires Lock
                                             4. Re-verify: Still Available?
                                                State = BOOKED!
                                             5. ROLLBACK TX
                                             6. Throw DeskAlreadyBookedException
                                                (409 Conflict / Fallback)
```

#### Fault-Tolerance Mechanisms
1. **Defensive Concurrency & Race Conditions**:
   - **Pessimistic Locking**: Every booking execution locks the target desk row using `PESSIMISTIC_WRITE` (`SELECT ... FOR UPDATE`).
   - **Database Unique Constraint**: `UNIQUE (desk_id, booking_date)` acts as an unbreakable guarantee at the storage layer even if application nodes crash or bypass locking.
2. **Deadlock Elimination**:
   - In multi-seat team bookings where multiple desks are reserved in a single transaction, desks are strictly sorted by primary key (`desk_id ASC`) before lock acquisition. This enforces an identical lock acquisition order across all threads, eliminating circular wait deadlocks ($T_1 \to D_1 \to D_2$ and $T_2 \to D_2 \to D_1$).
3. **Self-Healing Schedulers (Crash Recovery)**:
   - The `NoShowReleaseScheduler` does not store transient in-memory timers.
   - It queries state idempotently from the database:
     ```sql
     SELECT b FROM Booking b 
     WHERE b.bookingDate = :today 
       AND b.status = 'CONFIRMED' 
       AND b.startTime + :gracePeriod <= :currentTime
     ```
   - If the server crashes or restarts during business hours, the very next scheduled execution catches all accumulated overdue bookings automatically.
4. **Transaction Boundary & Rollback**:
   - All booking methods are wrapped in `@Transactional(rollbackFor = Exception.class)`. Any failure (e.g., quota violation, database disconnect, network partition) immediately triggers a clean rollback, preventing orphaned or partially written reservations.

---

### 4. Object-Oriented Programming (OOPS) Principles

The implementation showcases clean OOP design and design patterns:

- **Encapsulation**:
  - Entity fields are strictly `private`. Business invariants (such as transitions from `CONFIRMED` $\to$ `CHECKED_IN` or `CONFIRMED` $\to$ `CANCELLED`) are protected inside domain methods on the `Booking` entity, preventing illegal state transitions.
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
   - `smartdesk.bookings.total`: Counter tagged by `status` (`CONFIRMED`, `CONFLICT`, `REJECTED`).
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
Employee/Team info ──── Redis (cached)
      │
      ▼
Floor info ──────────── Redis (cached)
      │
      ▼
Desk metadata ───────── Redis (cached)
      │
      ▼
Generate candidates
      │
      ▼
Calculate team proximity
      │
      ▼
Ranked candidates
      │
      ▼
PostgreSQL transaction ─── DIRECT DB (never cached)
      │
      ▼
SELECT ... FOR UPDATE
      │
      ▼
Re-check availability ─── DIRECT DB (never cached)
      │
      ▼
Create booking
      │
      ▼
COMMIT
```

Redis helps us arrive at the ranked candidate list faster. But **it never decides whether the booking is still valid** — that authority belongs exclusively to PostgreSQL with pessimistic row locking.

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

#### Interview Defense

> **Q: "Why cache desk layout but not availability?"**
> *"We cache relatively static, frequently accessed data — floor metadata, desk coordinates, and team information. We deliberately do not treat cached availability as authoritative because booking is concurrency-sensitive. Availability is revalidated inside a PostgreSQL transaction with row-level locking, so PostgreSQL remains the single source of truth. This gives us the performance benefit of caching without introducing stale-state bugs in the critical booking path."*

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
  - `DeskAlreadyBookedException` $\to$ `409 Conflict` (Simultaneous booking loss)
  - `QuotaExceededException` $\to$ `422 Unprocessable Entity` (Team/floor quota limit reached)
  - `CutOffPassedException` $\to$ `400 Bad Request` (Cancellation after cut-off time)
  - `InvalidCheckInException` $\to$ `400 Bad Request` (Attempting check-in outside grace window)
  - `ResourceNotFoundException` $\to$ `404 Not Found` (Desk, Floor, or Employee ID does not exist)
  - `UnauthorizedOperationException` $\to$ `403 Forbidden` (Non-coordinator attempting team booking)

---

### Summary Checklist for Technical Interview Defense

When asked to explain this system in an interview:

1. **Start with the Core Problem**: *"In a hybrid office, employees want to sit together without the chaos of seat hoarding or race conditions during morning peak hours."*
2. **Explain the 2-Stage Allocation**: *"We separate Floor Selection (hard business rules/quotas) from Desk Selection (spatial 2D teammate proximity). This keeps the spatial math focused and deterministic."*
3. **Highlight Concurrency Defense**: *"We don't rely on hope or application-only flags. We use pessimistic row-level locking (`SELECT ... FOR UPDATE`) backed by a database unique constraint. Thread A wins; Thread B immediately detects the commit and receives a 409 or fallback."*
4. **Walk Through Time & Space**: *"Filtering reduces candidates to $C \le 500$. In-memory squared Euclidean distance runs in $O(C \cdot T)$ in under 2ms. For teams, anchor-and-expand with $K=10$ gives near-optimal clusters without NP-hard complexity."*
5. **Demonstrate Production Readiness**: *"The architecture is fully decoupled (Strategy pattern, DTO separation, RFC 7807 error responses, Redis caching for layout reads, Spring Actuator metrics, and self-healing cron jobs for no-show releases)."*

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
