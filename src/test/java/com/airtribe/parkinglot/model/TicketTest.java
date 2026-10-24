package com.airtribe.parkinglot.model;

import com.airtribe.parkinglot.exception.InvalidTicketStateException;
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
        assertEquals(BigDecimal.ZERO, ticket.getFee());
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
        assertTrue(VehicleType.MOTORCYCLE.fitsIn(SpotSize.LARGE));
        assertTrue(VehicleType.CAR.fitsIn(SpotSize.MEDIUM));
        assertFalse(VehicleType.CAR.fitsIn(SpotSize.SMALL));
        assertFalse(VehicleType.BUS.fitsIn(SpotSize.MEDIUM));
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
    void parkingSpotCanOnlyBeClaimedOnce() {
        ParkingSpot spot = new ParkingSpot("F0-S1", 0, SpotSize.SMALL);
        assertTrue(spot.tryOccupy());
        assertFalse(spot.tryOccupy());
        assertFalse(spot.isFree());
        assertTrue(spot.release());
        assertFalse(spot.release());
        assertTrue(spot.isFree());
    }

    @Test
    void lotCountersFollowOccupyAndRelease() {
        ParkingLot lot = new ParkingLot(java.util.List.of(ParkingFloor.of(0, 1, 2, 1)));
        ParkingSpot med = lot.getFloors().get(0).getSpots().stream()
                .filter(s -> s.getSize() == SpotSize.MEDIUM).findFirst().get();

        assertEquals(3, lot.availableFor(VehicleType.CAR));
        assertEquals(4, lot.availableFor(VehicleType.MOTORCYCLE));
        assertEquals(1, lot.availableFor(VehicleType.BUS));

        assertTrue(lot.occupy(med));
        assertFalse(lot.occupy(med));
        assertEquals(2, lot.availableFor(VehicleType.CAR));
        assertEquals(1, lot.availability().get(SpotSize.MEDIUM));

        lot.release(med);
        lot.release(med); // second release shouldn't bump the counter
        assertEquals(2, lot.availability().get(SpotSize.MEDIUM));
    }
}
