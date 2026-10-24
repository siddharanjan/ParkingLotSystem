# Smart Parking Lot

Low-level design of a multi-floor parking lot backend: spot allocation, check-in/out, fee calculation, live availability.

## Build & test
    mvn test                 # 70 unit tests
    mvn package && java -jar target/parkinglot-1.0.0.jar

## Layout (`com.airtribe.parkinglot`)
| Package | Contents |
|---|---|
| `model` | `ParkingLot`, `SpotPool` (free spots per size), `ParkingFloor`, `ParkingSpot`, `Vehicle`, `Ticket`, enums |
| `strategy` | `SpotAllocationStrategy` (`BestFit`, `NearestFloorFirst`), `FeeStrategy` (`HourlyFee`) |
| `service` | `ParkingService` – check-in, check-out, cancel, availability |
| `repository` | `TicketRepository` + in-memory impl |
| `exception` | lot full, already parked, ticket not found, invalid ticket state, cancellation not allowed |

Relational schema: [`src/main/resources/schema.sql`](../src/main/resources/schema.sql).

Flow and class diagrams: [FLOWS.md](FLOWS.md).

## Key decisions
- **Allocation**: `SpotPool` keeps one sorted set of free spots per size (lowest floor first). A strategy picks which set(s) to take from; taking a spot is an atomic remove, so there is no scan or sort per entry and two threads can't get the same spot. A counter per size gives O(1) availability.
- **Concurrency**: no global lock. One-active-ticket-per-plate = `add` on a concurrent set; ticket closure is synchronized so exactly one checkout/cancel wins, and only that caller releases the spot.
- **Failed check-in**: if anything after the spot claim throws (e.g. the repository save), the spot goes back to the pool and the plate is freed, then the exception is rethrown.
- **Ticket lifecycle**: `ACTIVE -> COMPLETED | CANCELLED`; anything else throws `InvalidTicketStateException`.
- **Fees**: per-hour rate by vehicle type, 15-min grace, started hours round up, result rounded to 2 decimals (HALF_UP). Compare fees with `compareTo` in tests, not `equals`.
- **Cancel**: free, but only within a window after entry (default 5 min, configurable). Later than that you must check out and pay, otherwise cancel would be a way to skip the fee.
- **Clock skew**: if the clock is behind the entry time at check-out, the exit time is clamped to the entry time (fee 0) and a warning is logged, so the car can still leave.
- **Ticket ids**: random UUIDs, so they don't collide after a restart and can't be guessed.
- **Time**: `Clock` is injected so tests are deterministic.
- **Availability**: `availability()` reads one counter per size, so it isn't a single atomic snapshot while traffic is moving. `ParkingLot.addAvailabilityListener` gives push style updates.
- **Motorcycles**: by default they can overflow into large spots. `new ParkingLot(floors, false)` keeps them out.
- **`Vehicle.equals`**: plate only, since a plate can't be two vehicles.

## Scaling to multiple nodes
Everything above is concurrency inside one JVM. With two or more app nodes the in-memory pool and plate set are no longer shared, so the database has to be the arbiter. This is not implemented here, it is the plan:
- Claim a spot in one transaction: `SELECT ... FROM parking_spot WHERE size IN (...) AND spot_id NOT IN (active tickets) ORDER BY ... FOR UPDATE SKIP LOCKED LIMIT 1`, then insert the ticket. With Spring Data JPA that is a `@Lock(PESSIMISTIC_WRITE)` query with the lock timeout hint `-2` (Hibernate's SKIP LOCKED). Different nodes then get different rows instead of queueing on the same one.
- Keep `uq_active_vehicle` and `uq_active_spot` as the backstop. A unique violation on the vehicle index becomes `VehicleAlreadyParkedException`.
- Ticket ids are already UUIDs, so nodes don't need to coordinate a sequence.
- Availability counts become a query (or a cached counter refreshed from it).
- No `occupied` flag in the schema: an ACTIVE ticket is what makes a spot taken.
