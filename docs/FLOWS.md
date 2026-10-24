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
        +occupy(ParkingSpot) boolean
        +release(ParkingSpot)
        +availability() Map
        +availableFor(VehicleType) int
    }
    class ParkingFloor
    class ParkingSpot {
        -AtomicBoolean occupied
        +tryOccupy() boolean
        +release() boolean
    }
    class Ticket {
        -TicketStatus status
        +complete(exit, fee)
        +cancel(at)
    }
    class Vehicle
    class SpotAllocationStrategy {
        <<interface>>
        +candidates(floors, type) List
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
    ParkingLot "1" o-- "many" ParkingFloor
    ParkingFloor "1" o-- "many" ParkingSpot
    Ticket --> Vehicle
    Ticket --> ParkingSpot
```

## 2. Check-in

```mermaid
flowchart TD
    A([Vehicle arrives]) --> B["activeByPlate.compute(plate)"]
    B --> C{Plate already<br/>has an active ticket?}
    C -- yes --> X1[/VehicleAlreadyParkedException/]
    C -- no --> D["strategy.candidates(floors, vehicleType)<br/>free + compatible spots, best first"]
    D --> E{Next candidate?}
    E -- none left --> X2[/ParkingLotFullException/]
    E -- yes --> F{"lot.occupy(spot)<br/>CAS false to true"}
    F -- lost the race --> E
    F -- won --> G["Create Ticket T-n<br/>status ACTIVE, entry time from Clock"]
    G --> H[Save in TicketRepository]
    H --> I([Return ticket])
```

If the compute block throws, nothing is stored for that plate, so a failed entry leaves no phantom "already parked" record.

## 3. Check-out

```mermaid
flowchart TD
    A([checkOut ticketId]) --> B[Find ticket in repository]
    B --> C{Found?}
    C -- no --> X1[/TicketNotFoundException/]
    C -- yes --> D["now = Clock.now()"]
    D --> E["fee = feeStrategy.calculate(type, now - entry)"]
    E --> F["ticket.complete(now, fee)<br/>synchronized"]
    F --> G{Status was ACTIVE?}
    G -- no --> X2[/InvalidTicketStateException/]
    G -- yes --> H[Status becomes COMPLETED]
    H --> I[Remove plate from activeByPlate]
    I --> J["lot.release(spot)<br/>CAS true to false, counter +1"]
    J --> K([Return fee])
```

Cancel is the same flow without the fee step: `ticket.cancel(now)` sets status `CANCELLED` and fee 0, then the spot is released.

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
    participant S as ParkingService
    participant P as ParkingSpot F0-M1

    T1->>S: checkIn(A)
    T2->>S: checkIn(B)
    S->>P: tryOccupy() from T1
    S->>P: tryOccupy() from T2
    P-->>T1: true (won)
    P-->>T2: false (lost)
    Note over T2,S: T2 moves to its next candidate,<br/>or throws ParkingLotFullException
    S-->>T1: Ticket T-1
```

## 6. Fee calculation

```mermaid
flowchart TD
    A([stay, vehicleType]) --> B{stay negative?}
    B -- yes --> X[/IllegalArgumentException/]
    B -- no --> C{"stay <= grace<br/>(default 15 min)"}
    C -- yes --> Z([Fee = 0])
    C -- no --> D["hours = ceil(stay in seconds / 3600)<br/>any started hour counts"]
    D --> E(["Fee = hourlyRate[type] x hours"])
```

Default rates per hour: motorcycle 10, car 20, bus 50.

| Stay (car) | Fee |
|---|---|
| 15 min | 0 |
| 15 min 1 s | 20 |
| 1 h | 20 |
| 1 h 1 s | 40 |

## 7. Spot allocation orders

Both strategies filter to free spots that fit the vehicle, then sort.

| Strategy | Sort order | Why |
|---|---|---|
| `BestFitStrategy` | size, then floor, then id | keeps large spots free for buses |
| `NearestFloorFirstStrategy` | floor, then size, then id | shortest walk to the exit |

A vehicle fits any spot at least as big as its minimum size: motorcycle takes small, medium or large; car takes medium or large; bus takes large only.

## 8. Data model

```mermaid
erDiagram
    PARKING_FLOOR ||--o{ PARKING_SPOT : has
    PARKING_SPOT ||--o{ TICKET : "used by"
    VEHICLE ||--o{ TICKET : holds
    VEHICLE }o--|| FEE_RATE : "billed at (by type)"

    PARKING_FLOOR { int floor_number PK }
    PARKING_SPOT {
        string spot_id PK
        int floor_number FK
        string size
        boolean occupied
        long version
    }
    VEHICLE {
        string license_plate PK
        string vehicle_type
    }
    TICKET {
        string ticket_id PK
        string license_plate FK
        string spot_id FK
        timestamp entry_time
        timestamp exit_time
        decimal fee
        string status
    }
    FEE_RATE {
        string vehicle_type PK
        decimal hourly_rate
        int grace_minutes
    }
```

Partial unique indexes on `ticket` allow only one `ACTIVE` ticket per plate and per spot. See `src/main/resources/schema.sql`.
