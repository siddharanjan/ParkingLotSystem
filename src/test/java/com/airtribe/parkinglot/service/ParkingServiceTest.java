package com.airtribe.parkinglot.service;

import com.airtribe.parkinglot.exception.*;
import com.airtribe.parkinglot.model.*;
import com.airtribe.parkinglot.repository.InMemoryTicketRepository;
import com.airtribe.parkinglot.strategy.BestFitStrategy;
import com.airtribe.parkinglot.strategy.HourlyFeeStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.airtribe.parkinglot.strategy.NearestFloorFirstStrategy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ParkingServiceTest {
    // clock we can move forward by hand
    static class TestClock extends Clock {
        private volatile Instant now = Instant.parse("2026-01-01T10:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private TestClock clock;
    private ParkingService service;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        service = newService(ParkingFloor.of(0, 1, 1, 1)); // 1 small, 1 medium, 1 large
    }

    private ParkingService newService(ParkingFloor... floors) {
        return new ParkingService(new ParkingLot(List.of(floors)), new BestFitStrategy(),
                HourlyFeeStrategy.defaults(), new InMemoryTicketRepository(), clock);
    }

    private static Vehicle car(String plate) { return new Vehicle(plate, VehicleType.CAR); }

    @Test
    void assignsSpotBySizeAndUpdatesAvailability() {
        Ticket moto = service.checkIn(new Vehicle("M1", VehicleType.MOTORCYCLE));
        Ticket c = service.checkIn(car("C1"));
        Ticket bus = service.checkIn(new Vehicle("B1", VehicleType.BUS));

        assertEquals(SpotSize.SMALL, moto.getSpot().getSize());
        assertEquals(SpotSize.MEDIUM, c.getSpot().getSize());
        assertEquals(SpotSize.LARGE, bus.getSpot().getSize());
        assertEquals(0, service.availability().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void smallVehicleOverflowsIntoLargerSpotButBusNeverIntoSmaller() {
        service.checkIn(car("C1"));
        service.checkIn(car("C2")); // medium taken -> large
        assertThrows(ParkingLotFullException.class, () -> service.checkIn(new Vehicle("B1", VehicleType.BUS)));
    }

    @Test
    void fullLotRejectsEntryWithoutSideEffects() {
        service.checkIn(car("C1"));
        service.checkIn(car("C2"));
        assertThrows(ParkingLotFullException.class, () -> service.checkIn(car("C3")));
        assertEquals(1, service.availability().get(SpotSize.SMALL)); // untouched
        // failed check in shouldnt leave the plate marked as parked
        assertThrows(ParkingLotFullException.class, () -> service.checkIn(car("C3")));
    }

    @Test
    void checkOutChargesFeeFreesSpotAndClosesTicket() {
        Ticket t = service.checkIn(car("C1"));
        clock.advance(Duration.ofMinutes(90));

        assertEquals(BigDecimal.valueOf(40), service.checkOut(t.getId()));
        assertEquals(TicketStatus.COMPLETED, t.getStatus());
        assertEquals(clock.instant(), t.getExitTime());
        assertEquals(1, service.availability().get(SpotSize.MEDIUM));
    }

    @Test
    void freedSpotIsReusable() {
        Ticket t = service.checkIn(car("C1"));
        service.checkOut(t.getId());
        assertSame(t.getSpot(), service.checkIn(car("C2")).getSpot());
    }

    @Test
    void sameVehicleCannotEnterTwiceButCanReturnAfterExit() {
        Ticket t = service.checkIn(car("C1"));
        assertThrows(VehicleAlreadyParkedException.class, () -> service.checkIn(car("c1 ")));
        service.checkOut(t.getId());
        assertDoesNotThrow(() -> service.checkIn(car("C1")));
    }

    @Test
    void doubleCheckOutIsRejected() {
        Ticket t = service.checkIn(car("C1"));
        service.checkOut(t.getId());
        assertThrows(InvalidTicketStateException.class, () -> service.checkOut(t.getId()));
        assertEquals(1, service.availability().get(SpotSize.MEDIUM)); // not double-released
    }

    @Test
    void cancelFreesSpotChargesNothingAndBlocksCheckOut() {
        Ticket t = service.checkIn(car("C1"));
        clock.advance(Duration.ofHours(5));
        service.cancel(t.getId());

        assertEquals(TicketStatus.CANCELLED, t.getStatus());
        assertEquals(BigDecimal.ZERO, t.getFee());
        assertEquals(1, service.availability().get(SpotSize.MEDIUM));
        assertThrows(InvalidTicketStateException.class, () -> service.checkOut(t.getId()));
        assertThrows(InvalidTicketStateException.class, () -> service.cancel(t.getId()));
        assertDoesNotThrow(() -> service.checkIn(car("C1")));
    }

    @Test
    void cannotCancelCompletedTicket() {
        Ticket t = service.checkIn(car("C1"));
        service.checkOut(t.getId());
        assertThrows(InvalidTicketStateException.class, () -> service.cancel(t.getId()));
        assertEquals(TicketStatus.COMPLETED, t.getStatus());
    }

    @Test
    void unknownTicketIsReported() {
        assertThrows(TicketNotFoundException.class, () -> service.checkOut("nope"));
        assertThrows(TicketNotFoundException.class, () -> service.cancel("nope"));
    }

    // concurrency

    private static void runConcurrently(int threads, Callable<?> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads), go = new CountDownLatch(1);
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> { ready.countDown(); go.await(); return task.call(); }));
        }
        ready.await();
        go.countDown();
        for (Future<?> f : futures) { try { f.get(); } catch (ExecutionException ignored) { } }
        pool.shutdown();
    }

    @Test
    void concurrentEntriesNeverOversellSpots() throws Exception {
        ParkingService s = newService(ParkingFloor.of(0, 0, 10, 0));
        int threads = 100;
        AtomicInteger ids = new AtomicInteger(), ok = new AtomicInteger(), full = new AtomicInteger();
        java.util.Set<String> spots = ConcurrentHashMap.newKeySet();

        runConcurrently(threads, () -> {
            try {
                spots.add(s.checkIn(car("P" + ids.incrementAndGet())).getSpot().getId());
                ok.incrementAndGet();
            } catch (ParkingLotFullException e) {
                full.incrementAndGet();
            }
            return null;
        });

        assertEquals(10, ok.get());
        assertEquals(90, full.get());
        assertEquals(10, spots.size(), "every ticket must hold a distinct spot");
        assertEquals(0, s.availability().get(SpotSize.MEDIUM));
    }

    @Test
    void concurrentCheckOutOfSameTicketSucceedsExactlyOnce() throws Exception {
        Ticket t = service.checkIn(car("C1"));
        AtomicInteger ok = new AtomicInteger();

        runConcurrently(50, () -> {
            try { service.checkOut(t.getId()); ok.incrementAndGet(); }
            catch (InvalidTicketStateException ignored) { }
            return null;
        });

        assertEquals(1, ok.get());
        assertEquals(1, service.availability().get(SpotSize.MEDIUM));
    }

    @Test
    void concurrentEntriesOfSamePlateAdmitOnlyOne() throws Exception {
        AtomicInteger ok = new AtomicInteger();
        runConcurrently(30, () -> {
            try { service.checkIn(car("SAME")); ok.incrementAndGet(); }
            catch (VehicleAlreadyParkedException ignored) { }
            return null;
        });
        assertEquals(1, ok.get());
        int free = service.availability().values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(2, free, "only one of three spots should be consumed");
    }

    @Test
    void worksWithNearestFloorStrategy() {
        ParkingService s = new ParkingService(
                new ParkingLot(List.of(ParkingFloor.of(0, 0, 1, 0), ParkingFloor.of(1, 1, 1, 0))),
                new NearestFloorFirstStrategy(), HourlyFeeStrategy.defaults(),
                new InMemoryTicketRepository(), clock);

        // bike goes to the lowest floor even though it's a medium spot, best fit would pick F1-S1
        Ticket bike = s.checkIn(new Vehicle("M1", VehicleType.MOTORCYCLE));
        assertEquals("F0-M1", bike.getSpot().getId());

        Ticket c = s.checkIn(car("C1"));
        assertEquals("F1-M1", c.getSpot().getId());

        assertThrows(ParkingLotFullException.class, () -> s.checkIn(car("C2")));
        s.cancel(bike.getId());
        assertEquals("F0-M1", s.checkIn(car("C2")).getSpot().getId());
    }

    @Test
    void feeUsesVehicleTypeAndElapsedTime() {
        Ticket bus = service.checkIn(new Vehicle("B1", VehicleType.BUS));
        clock.advance(Duration.ofMinutes(61));
        assertEquals(BigDecimal.valueOf(100), service.checkOut(bus.getId()));
    }

    @Test
    void stayWithinGraceIsFree() {
        Ticket t = service.checkIn(car("C1"));
        clock.advance(Duration.ofMinutes(10));
        assertEquals(BigDecimal.ZERO, service.checkOut(t.getId()));
    }

    @Test
    void plateIsCaseAndWhitespaceInsensitiveForDuplicateCheck() {
        service.checkIn(car("ka01"));
        assertThrows(VehicleAlreadyParkedException.class, () -> service.checkIn(car("  KA01 ")));
    }

    @Test
    void cancelledVehicleCanEnterAgain() {
        Ticket t = service.checkIn(car("C1"));
        service.cancel(t.getId());
        assertNotNull(service.checkIn(car("C1")));
    }

    @Test
    void cancelOfUnknownTicketIsReported() {
        assertThrows(TicketNotFoundException.class, () -> service.cancel("T-999"));
    }

    @Test
    void ticketIdsAreUnique() {
        Ticket a = service.checkIn(car("C1"));
        Ticket b = service.checkIn(new Vehicle("M1", VehicleType.MOTORCYCLE));
        assertNotEquals(a.getId(), b.getId());
    }

    @Test
    void mixedConcurrentEntriesAndExitsKeepCountsConsistent() throws Exception {
        ParkingService s = newService(ParkingFloor.of(0, 0, 10, 0));
        // 5 cars already parked, will leave while 20 new ones try to get in
        List<Ticket> parked = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) parked.add(s.checkIn(car("OLD" + i)));

        AtomicInteger counter = new AtomicInteger();
        AtomicInteger entered = new AtomicInteger();
        runConcurrently(25, () -> {
            int n = counter.getAndIncrement();
            if (n < 5) {
                s.checkOut(parked.get(n).getId());
            } else {
                try { s.checkIn(car("NEW" + n)); entered.incrementAndGet(); }
                catch (ParkingLotFullException ignored) { }
            }
            return null;
        });

        // 10 spots total, the 5 old cars left, so free = 10 - entered
        assertTrue(entered.get() >= 5 && entered.get() <= 10);
        assertEquals(10 - entered.get(), s.availability().get(SpotSize.MEDIUM));
    }
}
