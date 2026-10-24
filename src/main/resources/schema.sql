-- Reference relational schema for the in-memory model.
CREATE TABLE parking_floor (
    floor_number INT PRIMARY KEY
);

CREATE TABLE parking_spot (
    spot_id      VARCHAR(20) PRIMARY KEY,
    floor_number INT         NOT NULL REFERENCES parking_floor (floor_number),
    size         VARCHAR(10) NOT NULL CHECK (size IN ('SMALL', 'MEDIUM', 'LARGE')),
    occupied     BOOLEAN     NOT NULL DEFAULT FALSE,
    version      BIGINT      NOT NULL DEFAULT 0   -- optimistic lock; mirrors the CAS in ParkingSpot
);
CREATE INDEX idx_spot_free ON parking_spot (size, floor_number) WHERE occupied = FALSE;

CREATE TABLE vehicle (
    license_plate VARCHAR(20) PRIMARY KEY,
    vehicle_type  VARCHAR(12) NOT NULL CHECK (vehicle_type IN ('MOTORCYCLE', 'CAR', 'BUS'))
);

CREATE TABLE ticket (
    ticket_id     VARCHAR(20)   PRIMARY KEY,
    license_plate VARCHAR(20)   NOT NULL REFERENCES vehicle (license_plate),
    spot_id       VARCHAR(20)   NOT NULL REFERENCES parking_spot (spot_id),
    entry_time    TIMESTAMP     NOT NULL,
    exit_time     TIMESTAMP,
    fee           DECIMAL(10,2),
    status        VARCHAR(10)   NOT NULL CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED'))
);
-- A vehicle can hold only one active ticket, and a spot can serve only one active ticket.
CREATE UNIQUE INDEX uq_active_vehicle ON ticket (license_plate) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uq_active_spot    ON ticket (spot_id)       WHERE status = 'ACTIVE';

CREATE TABLE fee_rate (
    vehicle_type VARCHAR(12)   PRIMARY KEY,
    hourly_rate  DECIMAL(10,2) NOT NULL,
    grace_minutes INT          NOT NULL DEFAULT 15
);