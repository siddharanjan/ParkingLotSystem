-- Reference relational schema for the in-memory model (Postgres flavoured).
CREATE TABLE parking_floor (
    floor_number INT PRIMARY KEY
);

-- No "occupied" column on purpose. A spot is taken exactly when it has an ACTIVE ticket
-- (see uq_active_spot below), so there is only one source of truth to keep in sync.
CREATE TABLE parking_spot (
    spot_id      VARCHAR(20) PRIMARY KEY,
    floor_number INT         NOT NULL REFERENCES parking_floor (floor_number),
    size         VARCHAR(10) NOT NULL CHECK (size IN ('SMALL', 'MEDIUM', 'LARGE'))
);
CREATE INDEX idx_spot_size_floor ON parking_spot (size, floor_number);

CREATE TABLE vehicle (
    license_plate VARCHAR(20) PRIMARY KEY,
    vehicle_type  VARCHAR(12) NOT NULL CHECK (vehicle_type IN ('MOTORCYCLE', 'CAR', 'BUS'))
);

-- Rates can change over time, so each row is valid for a period. Only one row per type
-- should cover any given moment.
CREATE TABLE fee_rate (
    vehicle_type  VARCHAR(12)   NOT NULL CHECK (vehicle_type IN ('MOTORCYCLE', 'CAR', 'BUS')),
    valid_from    TIMESTAMPTZ   NOT NULL,
    valid_to      TIMESTAMPTZ,                       -- NULL = still current
    hourly_rate   DECIMAL(10,2) NOT NULL,
    grace_minutes INT           NOT NULL DEFAULT 15,
    PRIMARY KEY (vehicle_type, valid_from),
    CHECK (valid_to IS NULL OR valid_to > valid_from)
);

CREATE TABLE ticket (
    ticket_id          UUID          PRIMARY KEY,
    license_plate      VARCHAR(20)   NOT NULL REFERENCES vehicle (license_plate),
    spot_id            VARCHAR(20)   NOT NULL REFERENCES parking_spot (spot_id),
    -- copied at entry so history stays right if the vehicle row or the rates change later
    vehicle_type       VARCHAR(12)   NOT NULL CHECK (vehicle_type IN ('MOTORCYCLE', 'CAR', 'BUS')),
    applied_hourly_rate DECIMAL(10,2) NOT NULL,
    entry_time         TIMESTAMPTZ   NOT NULL,
    exit_time          TIMESTAMPTZ,
    fee                DECIMAL(10,2),
    status             VARCHAR(10)   NOT NULL CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')),
    CHECK (exit_time IS NULL OR exit_time >= entry_time),
    -- ACTIVE: not closed yet. Anything else: closed with an exit time and a fee.
    CHECK ((status = 'ACTIVE'  AND exit_time IS NULL     AND fee IS NULL)
        OR (status <> 'ACTIVE' AND exit_time IS NOT NULL AND fee IS NOT NULL)),
    CHECK (status <> 'CANCELLED' OR fee = 0)
);
-- A vehicle can hold only one active ticket, and a spot can serve only one active ticket.
-- These are the final backstop if two app nodes race: the loser gets a constraint violation,
-- which the app turns into VehicleAlreadyParkedException / retries another spot.
CREATE UNIQUE INDEX uq_active_vehicle ON ticket (license_plate) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uq_active_spot    ON ticket (spot_id)       WHERE status = 'ACTIVE';
-- history lookups for one vehicle, newest first
CREATE INDEX idx_ticket_plate_entry ON ticket (license_plate, entry_time DESC);
