package com.airtribe.parkinglot.model;

import com.airtribe.parkinglot.exception.InvalidTicketStateException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

// ACTIVE can go to COMPLETED or CANCELLED, nothing else.
// synchronized so only one thread can close a ticket.
public final class Ticket {
    private final String id;
    private final Vehicle vehicle;
    private final ParkingSpot spot;
    private final Instant entryTime;

    private TicketStatus status = TicketStatus.ACTIVE;
    private Instant exitTime;
    private BigDecimal fee;

    public Ticket(String id, Vehicle vehicle, ParkingSpot spot, Instant entryTime) {
        this.id = id;
        this.vehicle = vehicle;
        this.spot = spot;
        this.entryTime = entryTime;
    }

    public synchronized void complete(Instant exitTime, BigDecimal fee) {
        requireActive("complete");
        // ParkingService clamps the exit time, so this only fires for direct callers
        if (exitTime.isBefore(entryTime)) {
            throw new IllegalArgumentException("Exit time precedes entry time");
        }
        this.status = TicketStatus.COMPLETED;
        this.exitTime = exitTime;
        this.fee = fee;
    }

    public synchronized void cancel(Instant at) {
        requireActive("cancel");
        this.status = TicketStatus.CANCELLED;
        this.exitTime = at;
        this.fee = BigDecimal.ZERO.setScale(2);
    }

    private void requireActive(String action) {
        if (status != TicketStatus.ACTIVE) {
            throw new InvalidTicketStateException(
                    "Cannot " + action + " ticket " + id + " in state " + status);
        }
    }

    public synchronized Duration getDuration(Instant now) {
        return Duration.between(entryTime, exitTime != null ? exitTime : now);
    }

    public String getId() { return id; }

    public Vehicle getVehicle() { return vehicle; }

    public ParkingSpot getSpot() { return spot; }

    public Instant getEntryTime() { return entryTime; }

    public synchronized TicketStatus getStatus() { return status; }

    public synchronized Instant getExitTime() { return exitTime; }

    public synchronized BigDecimal getFee() { return fee; }
}
