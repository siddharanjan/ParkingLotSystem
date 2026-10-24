package com.airtribe.parkinglot.service;

import com.airtribe.parkinglot.exception.ParkingLotFullException;
import com.airtribe.parkinglot.exception.TicketNotFoundException;
import com.airtribe.parkinglot.exception.VehicleAlreadyParkedException;
import com.airtribe.parkinglot.model.ParkingLot;
import com.airtribe.parkinglot.model.ParkingSpot;
import com.airtribe.parkinglot.model.SpotSize;
import com.airtribe.parkinglot.model.Ticket;
import com.airtribe.parkinglot.model.Vehicle;
import com.airtribe.parkinglot.repository.TicketRepository;
import com.airtribe.parkinglot.strategy.FeeStrategy;
import com.airtribe.parkinglot.strategy.SpotAllocationStrategy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// Handles check in / check out.
// Spots are taken with CAS, the "already parked" check happens inside
// activeByPlate.compute so it's atomic per plate, and Ticket guards its own state.
// No global lock so different vehicles don't wait on each other.
public class ParkingService {
    private final ParkingLot lot;
    private final SpotAllocationStrategy allocationStrategy;
    private final FeeStrategy feeStrategy;
    private final TicketRepository tickets;
    private final Clock clock;

    private final Map<String, Ticket> activeByPlate = new ConcurrentHashMap<>();
    private final AtomicLong ticketSequence = new AtomicLong();

    public ParkingService(ParkingLot lot, SpotAllocationStrategy allocationStrategy,
                          FeeStrategy feeStrategy, TicketRepository tickets, Clock clock) {
        this.lot = lot;
        this.allocationStrategy = allocationStrategy;
        this.feeStrategy = feeStrategy;
        this.tickets = tickets;
        this.clock = clock;
    }

    public Ticket checkIn(Vehicle vehicle) {
        Ticket ticket = activeByPlate.compute(vehicle.getLicensePlate(), (plate, existing) -> {
            if (existing != null) {
                throw new VehicleAlreadyParkedException(plate + " is already parked in " + existing.getSpot());
            }
            return new Ticket("T-" + ticketSequence.incrementAndGet(), vehicle,
                    claimSpot(vehicle), Instant.now(clock));
        });
        tickets.save(ticket);
        return ticket;
    }

    private ParkingSpot claimSpot(Vehicle vehicle) {
        for (ParkingSpot spot : allocationStrategy.candidates(lot.getFloors(), vehicle.getType())) {
            if (lot.occupy(spot)) {
                return spot;
            }
        }
        throw new ParkingLotFullException("No spot available for " + vehicle);
    }

    // returns the fee
    public BigDecimal checkOut(String ticketId) {
        Ticket ticket = find(ticketId);
        Instant now = Instant.now(clock);
        BigDecimal fee = feeStrategy.calculate(ticket.getVehicle().getType(), ticket.getDuration(now));
        ticket.complete(now, fee); // throws if already closed
        release(ticket);
        return fee;
    }

    // for tickets made by mistake, no fee
    public void cancel(String ticketId) {
        Ticket ticket = find(ticketId);
        ticket.cancel(Instant.now(clock));
        release(ticket);
    }

    private void release(Ticket ticket) {
        activeByPlate.remove(ticket.getVehicle().getLicensePlate(), ticket);
        lot.release(ticket.getSpot());
    }

    private Ticket find(String ticketId) {
        return tickets.findById(ticketId)
                .orElseThrow(() -> new TicketNotFoundException("Unknown ticket " + ticketId));
    }

    public Map<SpotSize, Integer> availability() { return lot.availability(); }
}
