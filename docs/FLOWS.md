# Flows and diagrams

Mermaid diagrams for the parking lot. GitHub and IntelliJ (Markdown plugin) render them.

## 1. Class overview

```mermaid
classDiagram
    class ParkingService {
        +checkIn(Vehicle) Ticket
        +checkOut(ticketId) BigDecimal
        +cancel(ticketId)
        +availability() Map
    }
    class ParkingLot {
        +acquire(VehicleType, strategy) ParkingSpot
        +release(ParkingSpot)
        +availability() Map
        +availableFor(VehicleType) int
        +addAvailabilityListener(Consumer)
    }
    class SpotPool {
        -free spots per size, sorted
        -AtomicInteger count per size
        +pollFirst(size) Optional
        +peekFirst(size) Optional
        +take(spot) boolean
        +release(spot)
    }
    class ParkingFloor
    class ParkingSpot
    class Ticket {
        -TicketStatus status
        +complete(exit, fee)
        +cancel(at)
    }
    class Vehicle
    class SpotAllocationStrategy {
        <<interface>>
        +acquire(pool, type) Optional~ParkingSpot~
    }
    class FeeStrategy {
        <<interface>>
        +calculate(type, stay) BigDecimal
    }
    class TicketRepository {
        <<interface>>
        +save(Ticket)
        +findById(id) Optional
    }
    SpotAllocationStrategy <|.. BestFitStrategy
    SpotAllocationStrategy <|.. NearestFloorFirstStrategy
    FeeStrategy <|.. HourlyFeeStrategy
    TicketRepository <|.. InMemoryTicketRepository

    ParkingService --> ParkingLot
    ParkingService --> SpotAllocationStrategy
    ParkingService --> FeeStrategy
    ParkingService --> TicketRepository
    ParkingLot --> SpotPool
    SpotAllocationStrategy ..> SpotPool : takes spots from
    ParkingLot "1" o-- "many" ParkingFloor
    ParkingFloor "1" o-- "many" ParkingSpot
    Ticket --> Vehicle
    Ticket --> ParkingSpot
```

## 2. Check-in

```mermaid
flowchart TD
    A([Vehicle arrives]) --> B{"activePlates.add(plate)<br/>false if already there"}
    B -- already parked --> X1[/VehicleAlreadyParkedException/]
    B -- added --> D["lot.acquire(type, strategy)<br/>takes best free spot from SpotPool"]
    D -- nothing fits --> R
    D -- got spot --> G["Create Ticket (UUID id)<br/>status ACTIVE, entry time from Clock"]
    G --> H["tickets.save(ticket)"]
    H -- throws --> R
    H -- ok --> I([Return ticket])
    R["Rollback: release spot if one was taken,<br/>activePlates.remove(plate)"] --> X2[/Rethrow: ParkingLotFullException or the save error/]
```

Because the rollback is in a `catch`, a failed entry (lot full, or a repository error) never leaks a spot or leaves the plate stuck as "parked".

## 3. Check-out

```mermaid
flowchart TD
    A([checkOut ticketId]) --> B[Find ticket in repository]
    B --> C{Found?}
    C -- no --> X1[/TicketNotFoundException/]
    C -- yes --> D["now = Clock.now()"]
    D --> D2{now before entry time?}
    D2 -- yes --> D3["log warning, exit = entry time<br/>(fee will be 0)"]
    D2 -- no --> E
    D3 --> E["fee = feeStrategy.calculate(type, exit - entry)"]
    E --> F["ticket.complete(exit, fee)<br/>synchronized"]
    F --> G{Status was ACTIVE?}
    G -- no --> X2[/InvalidTicketStateException/]
    G -- yes --> H[Status becomes COMPLETED]
    H --> I[Remove plate from activePlates]
    I --> J["lot.release(spot)<br/>back into the pool, counter +1"]
    J --> K([Return fee])
```

Only the caller that wins `ticket.complete()` reaches the release step, so a spot can't be released twice.

### Cancel

```mermaid
flowchart TD
    A([cancel ticketId]) --> B[Find ticket]
    B --> C{"ACTIVE and older than<br/>cancellation window (5 min)?"}
    C -- yes --> X1[/CancellationNotAllowedException/]
    C -- no --> D["ticket.cancel(now)<br/>fee 0, status CANCELLED"]
    D -- not ACTIVE --> X2[/InvalidTicketStateException/]
    D --> E[Release plate and spot]
```

## 4. Ticket lifecycle

```mermaid
stateDiagram-v2
    [*] --> ACTIVE: checkIn
    ACTIVE --> COMPLETED: checkOut (fee charged)
    ACTIVE --> CANCELLED: cancel (fee 0)
    COMPLETED --> [*]
    CANCELLED --> [*]
```

Any other transition throws `InvalidTicketStateException`.

## 5. Two cars racing for the last spot

```mermaid
sequenceDiagram
    participant T1 as Thread 1 (car A)
    participant T2 as Thread 2 (car B)
    participant P as SpotPool (MEDIUM set)

    T1->>P: pollFirst(MEDIUM)
    T2->>P: pollFirst(MEDIUM)
    P-->>T1: F0-M1 (removed from set)
    P-->>T2: empty
    Note over T2: tries next compatible size,<br/>then ParkingLotFullException
```

`pollFirst` / `remove` on the concurrent set is atomic, so only one thread can get a given spot.

## 6. Fee calculation

```mermaid
flowchart TD
    A([stay, vehicleType]) --> B{stay negative?}
    B -- yes --> X[/IllegalArgumentException/]
    B -- no --> C{"stay <= grace<br/>(default 15 min)"}
    C -- yes --> Z([Fee = 0])
    C -- no --> D["hours = ceil(stay in seconds / 3600)<br/>any started hour counts"]
    D --> E(["Fee = hourlyRate[type] x hours<br/>scale 2, HALF_UP"])
```

Default rates per hour: motorcycle 10, car 20, bus 50.

| Stay (car) | Fee |
|---|---|
| 15 min | 0.00 |
| 15 min 1 s | 20.00 |
| 1 h | 20.00 |
| 1 h 1 s | 40.00 |

## 7. Spot allocation orders

`SpotPool` keeps free spots in one sorted set per size (lowest floor, then id). A strategy chooses which sets to take from.

| Strategy | How it picks | Why |
|---|---|---|
| `BestFitStrategy` | smallest compatible size that has a free spot, then lowest floor, then id | keeps large spots free for buses |
| `NearestFloorFirstStrategy` | looks at the head of each compatible size, takes the lowest floor (then size, then id), retries if someone else got it first | shortest walk to the exit |

Compatible sizes, best fit first: motorcycle `SMALL, MEDIUM, LARGE` (no `LARGE` if the lot is built with `motorcyclesMayUseLarge = false`), car `MEDIUM, LARGE`, bus `LARGE`.

## 8. Data model

```mermaid
erDiagram
    PARKING_FLOOR ||--o{ PARKING_SPOT : has
    PARKING_SPOT ||--o{ TICKET : "used by"
    VEHICLE ||--o{ TICKET : holds

    PARKING_FLOOR { int floor_number PK }
    PARKING_SPOT {
        string spot_id PK
        int floor_number FK
        string size
    }
    VEHICLE {
        string license_plate PK
        string vehicle_type
    }
    TICKET {
        string ticket_id PK
        string license_plate FK
        string spot_id FK
        string vehicle_type
        decimal applied_hourly_rate
        timestamptz entry_time
        timestamptz exit_time
        decimal fee
        string status
    }
    FEE_RATE {
        string vehicle_type PK
        timestamptz valid_from PK
        timestamptz valid_to
        decimal hourly_rate
        int grace_minutes
    }
```

Partial unique indexes on `ticket` allow only one `ACTIVE` ticket per plate and per spot. A spot has no `occupied` column: an `ACTIVE` ticket is what makes it taken. `ticket` also has checks for `exit_time >= entry_time` and for status/exit/fee consistency, and the rate used is copied onto the ticket. See `src/main/resources/schema.sql`.
