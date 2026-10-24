# Smart Parking Lot

Low-level design of a multi-floor parking lot backend: spot allocation, check-in/out, fee calculation, live availability.

## Build & test
    mvn test                 # 60 unit tests
    mvn package && java -jar target/parkinglot-1.0.0.jar

## Layout (`com.airtribe.parkinglot`)
| Package | Contents |
|---|---|
| `model` | `ParkingLot`, `ParkingFloor`, `ParkingSpot`, `Vehicle`, `Ticket`, enums |
| `strategy` | `SpotAllocationStrategy` (`BestFit`, `NearestFloorFirst`), `FeeStrategy` (`HourlyFee`) |
| `service` | `ParkingService` – check-in, check-out, cancel, availability |
| `repository` | `TicketRepository` + in-memory impl |
| `exception` | lot full, already parked, ticket not found, invalid ticket state |

Relational schema: [`src/main/resources/schema.sql`](../src/main/resources/schema.sql).

Flow and class diagrams: [FLOWS.md](FLOWS.md).

## Key decisions
- **Allocation**: strategy returns ordered candidates; service claims the first one it wins via CAS.
- **Concurrency**: no global lock. Spot claim = `AtomicBoolean` CAS; one-active-ticket-per-plate = atomic `ConcurrentHashMap.compute`; ticket closure synchronized so exactly one checkout/cancel wins.
- **Ticket lifecycle**: `ACTIVE → COMPLETED | CANCELLED`; anything else throws `InvalidTicketStateException`.
- **Fees**: per-hour rate by vehicle type, 15-min grace, started hours round up. Cancelled tickets cost 0.
- **Time**: `Clock` is injected so tests are deterministic.
