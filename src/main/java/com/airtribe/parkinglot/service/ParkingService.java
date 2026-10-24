package com.airtribe.parkinglot.service;

import com.airtribe.parkinglot.exception.CancellationNotAllowedException;
import com.airtribe.parkinglot.exception.TicketNotFoundException;
import com.airtribe.parkinglot.exception.VehicleAlreadyParkedException;
import com.airtribe.parkinglot.model.ParkingLot;
import com.airtribe.parkinglot.model.ParkingSpot;
import com.airtribe.parkinglot.model.SpotSize;
import com.airtribe.parkinglot.model.Ticket;
import com.airtribe.parkinglot.model.TicketStatus;
import com.airtribe.parkinglot.model.Vehicle;
import com.airtribe.parkinglot.repository.TicketRepository;
import com.airtribe.parkinglot.strategy.FeeStrategy;
import com.airtribe.parkinglot.strategy.SpotAllocationStrategy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

// Handles check in / check out.
// Spots come out of the lot's pool (atomic), a plate can only be in activePlates once,
// and Ticket guards its own state. No global lock so different vehicles don't wait on each other.
public class ParkingService {
    private static final Logger LOG = Logger.getLogger(ParkingService.class.getName());
    public static final Duration DEFAULT_CANCELLATION_WINDOW = Duration.ofMinutes(5);

    private final ParkingLot lot;
    private final SpotAllocationStrategy allocationStrategy;
    private final FeeStrategy feeStrategy;
    private final TicketRepository tickets;
    private final Clock clock;
    private final Duration cancellationWindow;

    private final Set<String> activePlates = ConcurrentHashMap.newKeySet();

    public ParkingService(ParkingLot lot, SpotAllocationStrategy allocationStrategy,
                          FeeStrategy feeStrategy, TicketRepository tickets, Clock clock) {
        this(lot, allocationStrategy, feeStrategy, tickets, clock, DEFAULT_CANCELLATION_WINDOW);
    }

    public ParkingService(ParkingLot lot, SpotAllocationStrategy allocationStrategy,
                          FeeStrategy feeStrategy, TicketRepository tickets, Clock clock,
                          Duration cancellationWindow) {
        this.lot = lot;
        this.allocationStrategy = allocationStrategy;
        this.feeStrategy = feeStrategy;
        this.tickets = tickets;
        this.clock = clock;
        this.cancellationWindow = cancellationWindow;
    }

    public Ticket checkIn(Vehicle vehicle) {
        String plate = vehicle.getLicensePlate();
        if (!activePlates.add(plate)) {
            throw new VehicleAlreadyParkedException(plate + " is already parked");
        }
        ParkingSpot spot = null;
        try {
            spot = lot.acquire(vehicle.getType(), allocationStrategy);
            Ticket ticket = new Ticket(UUID.randomUUID().toString(), vehicle, spot, Instant.now(clock));
            tickets.save(ticket);
            return ticket;
        } catch (RuntimeException e) {
            // undo everything so a failed entry doesn't leak the spot or lock the plate
            if (spot != null) {
                lot.release(spot);
            }
            activePlates.remove(plate);
            throw e;
        }
    }

    // returns the fee
    public BigDecimal checkOut(String ticketId) {
        Ticket ticket = find(ticketId);
        Instant now = Instant.now(clock);
        Instant exit = now;
        if (now.isBefore(ticket.getEntryTime())) {
            // clock went backwards, don't trap the car in the lot over it
            LOG.warning("Clock is behind entry time for ticket " + ticketId + " (now=" + now
                    + ", entry=" + ticket.getEntryTime() + "), using entry time as exit");
            exit = ticket.getEntryTime();
        }
        BigDecimal fee = feeStrategy.calculate(ticket.getVehicle().getType(), ticket.getDuration(exit));
        ticket.complete(exit, fee); // throws if already closed
        release(ticket);
        return fee;
    }

    // for tickets made by mistake, no fee. Only allowed shortly after entry.
    public void cancel(String ticketId) {
        Ticket ticket = find(ticketId);
        Instant now = Instant.now(clock);
        if (ticket.getStatus() == TicketStatus.ACTIVE
                && Duration.between(ticket.getEntryTime(), now).compareTo(cancellationWindow) > 0) {
            throw new CancellationNotAllowedException("Ticket " + ticketId
                    + " is older than the " + cancellationWindow.toMinutes() + " minute cancellation window");
        }
        ticket.cancel(now); // throws if already closed
        release(ticket);
    }

    // only reached by the caller that won complete()/cancel(), so no double release
    private void release(Ticket ticket) {
        activePlates.remove(ticket.getVehicle().getLicensePlate());
        lot.release(ticket.getSpot());
    }

    private Ticket find(String ticketId) {
        return tickets.findById(ticketId)
                .orElseThrow(() -> new TicketNotFoundException("Unknown ticket " + ticketId));
    }

    // not an atomic snapshot, see SpotPool.availability
    public Map<SpotSize, Integer> availability() { return lot.availability(); }
}
