package com.airtribe.parkinglot.model;

import com.airtribe.parkinglot.exception.InvalidTicketStateException;
import com.airtribe.parkinglot.strategy.BestFitStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TicketTest {
    private final Instant entry = Instant.parse("2026-01-01T10:00:00Z");
    private Ticket ticket;

    @BeforeEach
    void setUp() {
        ticket = new Ticket("T-1", new Vehicle("ka01", VehicleType.CAR),
                new ParkingSpot("F0-M1", 0, SpotSize.MEDIUM), entry);
    }

    @Test
    void startsActive() {
        assertEquals(TicketStatus.ACTIVE, ticket.getStatus());
        assertNull(ticket.getExitTime());
    }

    @Test
    void completeRecordsExitAndFee() {
        ticket.complete(entry.plusSeconds(3600), BigDecimal.TEN);
        assertEquals(TicketStatus.COMPLETED, ticket.getStatus());
        assertEquals(BigDecimal.TEN, ticket.getFee());
        assertEquals(Duration.ofHours(1), ticket.getDuration(entry.plusSeconds(99999)));
    }

    @Test
    void cancelZeroesFee() {
        ticket.cancel(entry.plusSeconds(60));
        assertEquals(TicketStatus.CANCELLED, ticket.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(ticket.getFee()));
    }

    @Test
    void cannotCompleteTwice() {
        ticket.complete(entry.plusSeconds(60), BigDecimal.ONE);
        assertThrows(InvalidTicketStateException.class, () -> ticket.complete(entry.plusSeconds(120), BigDecimal.ONE));
    }

    @Test
    void cannotCancelAfterComplete() {
        ticket.complete(entry.plusSeconds(60), BigDecimal.ONE);
        assertThrows(InvalidTicketStateException.class, () -> ticket.cancel(entry.plusSeconds(120)));
    }

    @Test
    void cannotCompleteAfterCancel() {
        ticket.cancel(entry.plusSeconds(60));
        assertThrows(InvalidTicketStateException.class, () -> ticket.complete(entry.plusSeconds(120), BigDecimal.ONE));
        assertEquals(TicketStatus.CANCELLED, ticket.getStatus());
    }

    @Test
    void rejectsExitBeforeEntry() {
        assertThrows(IllegalArgumentException.class, () -> ticket.complete(entry.minusSeconds(1), BigDecimal.ONE));
        assertEquals(TicketStatus.ACTIVE, ticket.getStatus());
    }

    @Test
    void vehicleNormalisesPlate() {
        assertEquals(new Vehicle("KA01", VehicleType.CAR), new Vehicle("  ka01 ", VehicleType.CAR));
        assertThrows(IllegalArgumentException.class, () -> new Vehicle(" ", VehicleType.CAR));
    }

    @Test
    void vehicleTypeFitsLargerSpotsOnly() {
        assertEquals(java.util.List.of(SpotSize.SMALL, SpotSize.MEDIUM, SpotSize.LARGE),
                VehicleType.MOTORCYCLE.compatibleSizesBestFirst());
        assertEquals(java.util.List.of(SpotSize.MEDIUM, SpotSize.LARGE), VehicleType.CAR.compatibleSizesBestFirst());
        assertEquals(java.util.List.of(SpotSize.LARGE), VehicleType.BUS.compatibleSizesBestFirst());
    }

    @Test
    void vehicleRejectsNullPlateAndNullType() {
        assertThrows(IllegalArgumentException.class, () -> new Vehicle(null, VehicleType.CAR));
        assertThrows(IllegalArgumentException.class, () -> new Vehicle("", VehicleType.CAR));
        assertThrows(NullPointerException.class, () -> new Vehicle("KA01", null));
    }

    @Test
    void durationOfActiveTicketUsesNowAndClosedTicketUsesExit() {
        assertEquals(Duration.ofMinutes(10), ticket.getDuration(entry.plus(Duration.ofMinutes(10))));
        ticket.complete(entry.plus(Duration.ofMinutes(20)), BigDecimal.ONE);
        assertEquals(Duration.ofMinutes(20), ticket.getDuration(entry.plus(Duration.ofHours(5))));
    }

    @Test
    void exitAtExactlyEntryTimeIsAllowed() {
        ticket.complete(entry, BigDecimal.ZERO);
        assertEquals(TicketStatus.COMPLETED, ticket.getStatus());
    }

    @Test
    void failedCompleteLeavesTicketActive() {
        assertThrows(IllegalArgumentException.class, () -> ticket.complete(entry.minusSeconds(1), BigDecimal.ONE));
        assertEquals(TicketStatus.ACTIVE, ticket.getStatus());
        assertNull(ticket.getExitTime());
    }

    @Test
    void poolHandsOutEachSpotOnce() {
        ParkingSpot spot = new ParkingSpot("F0-S1", 0, SpotSize.SMALL);
        SpotPool pool = new SpotPool(java.util.List.of(new ParkingFloor(0, java.util.List.of(spot))), true);
        assertTrue(pool.take(spot));
        assertFalse(pool.take(spot));
        assertTrue(pool.pollFirst(SpotSize.SMALL).isEmpty());
        pool.release(spot);
        pool.release(spot); // second release shouldn't bump the counter
        assertEquals(1, pool.availability().get(SpotSize.SMALL));
    }

    @Test
    void lotCountersFollowAcquireAndRelease() {
        ParkingLot lot = new ParkingLot(java.util.List.of(ParkingFloor.of(0, 1, 2, 1)));
        BestFitStrategy best = new BestFitStrategy();

        assertEquals(3, lot.availableFor(VehicleType.CAR));
        assertEquals(4, lot.availableFor(VehicleType.MOTORCYCLE));
        assertEquals(1, lot.availableFor(VehicleType.BUS));

        ParkingSpot med = lot.acquire(VehicleType.CAR, best);
        assertEquals(SpotSize.MEDIUM, med.getSize());
        assertEquals(2, lot.availableFor(VehicleType.CAR));
        assertEquals(1, lot.availability().get(SpotSize.MEDIUM));

        lot.release(med);
        lot.release(med);
        assertEquals(2, lot.availability().get(SpotSize.MEDIUM));
    }

    @Test
    void listenersHearAboutEveryChange() {
        ParkingLot lot = new ParkingLot(java.util.List.of(ParkingFloor.of(0, 0, 1, 0)));
        java.util.List<Integer> seen = new java.util.ArrayList<>();
        lot.addAvailabilityListener(a -> seen.add(a.get(SpotSize.MEDIUM)));
        ParkingSpot s = lot.acquire(VehicleType.CAR, new BestFitStrategy());
        lot.release(s);
        assertEquals(java.util.List.of(0, 1), seen);
    }
}
