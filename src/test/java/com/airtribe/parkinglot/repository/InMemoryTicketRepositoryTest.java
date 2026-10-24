package com.airtribe.parkinglot.repository;

import com.airtribe.parkinglot.model.ParkingSpot;
import com.airtribe.parkinglot.model.SpotSize;
import com.airtribe.parkinglot.model.Ticket;
import com.airtribe.parkinglot.model.Vehicle;
import com.airtribe.parkinglot.model.VehicleType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryTicketRepositoryTest {
    private final InMemoryTicketRepository repo = new InMemoryTicketRepository();

    private Ticket ticket(String id) {
        return new Ticket(id, new Vehicle("KA01", VehicleType.CAR),
                new ParkingSpot("F0-M1", 0, SpotSize.MEDIUM), Instant.parse("2026-01-01T10:00:00Z"));
    }

    @Test
    void savedTicketCanBeFound() {
        Ticket t = ticket("T-1");
        repo.save(t);
        assertSame(t, repo.findById("T-1").orElseThrow());
    }

    @Test
    void unknownIdIsEmpty() {
        assertTrue(repo.findById("nope").isEmpty());
    }

    @Test
    void savingSameIdReplacesOldTicket() {
        repo.save(ticket("T-1"));
        Ticket newer = ticket("T-1");
        repo.save(newer);
        assertSame(newer, repo.findById("T-1").orElseThrow());
    }
}
