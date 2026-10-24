package com.airtribe.parkinglot.service;

import com.airtribe.parkinglot.exception.*;
import com.airtribe.parkinglot.model.*;
import com.airtribe.parkinglot.repository.InMemoryTicketRepository;
import com.airtribe.parkinglot.repository.TicketRepository;
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
        void rewind(Duration d) { now = now.minus(d); }
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

    // compare by value so 40 and 40.00 are the same
    private static void assertFee(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual), "expected " + expected + " but was " + actual);
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

        assertFee(BigDecimal.valueOf(40), service.checkOut(t.getId()));
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
        clock.advance(Duration.ofMinutes(4));
        service.cancel(t.getId());

        assertEquals(TicketStatus.CANCELLED, t.getStatus());
        assertFee(BigDecimal.ZERO, t.getFee());
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

    @Test
    void lateCancelIsRejectedAndTicketStaysActive() {
        Ticket t = service.checkIn(car("C1"));
        clock.advance(Duration.ofHours(5));
        assertThrows(CancellationNotAllowedException.class, () -> service.cancel(t.getId()));
        assertEquals(TicketStatus.ACTIVE, t.getStatus());
        assertEquals(0, service.availability().get(SpotSize.MEDIUM));
        // the normal way out still works and charges
        assertFee(BigDecimal.valueOf(100), service.checkOut(t.getId()));
    }

    @Test
    void cancelWindowIsInclusiveAndConfigurable() {
        ParkingService s = new ParkingService(new ParkingLot(List.of(ParkingFloor.of(0, 0, 2, 0))),
                new BestFitStrategy(), HourlyFeeStrategy.defaults(), new InMemoryTicketRepository(), clock,
                Duration.ofMinutes(1));
        Ticket a = s.checkIn(car("A"));
        Ticket b = s.checkIn(car("B"));
        clock.advance(Duration.ofMinutes(1));
        assertDoesNotThrow(() -> s.cancel(a.getId())); // exactly on the limit
        clock.advance(Duration.ofSeconds(1));
        assertThrows(CancellationNotAllowedException.class, () -> s.cancel(b.getId()));
    }

    @Test
    void clockGoingBackwardsDoesNotTrapTheCar() {
        Ticket t = service.checkIn(car("C1"));
        clock.rewind(Duration.ofMinutes(30));

        BigDecimal fee = assertDoesNotThrow(() -> service.checkOut(t.getId()));

        assertFee(BigDecimal.ZERO, fee);
        assertEquals(TicketStatus.COMPLETED, t.getStatus());
        assertEquals(t.getEntryTime(), t.getExitTime(), "exit is clamped to entry");
        assertEquals(1, service.availability().get(SpotSize.MEDIUM));
    }

    // repo that blows up on save, to check the check in gets rolled back
    private static class FailingRepository implements TicketRepository {
        boolean fail = true;
        private final InMemoryTicketRepository real = new InMemoryTicketRepository();
        @Override public void save(Ticket t) {
            if (fail) throw new IllegalStateException("db down");
            real.save(t);
        }
        @Override public java.util.Optional<Ticket> findById(String id) { return real.findById(id); }
    }

    @Test
    void failedSaveReleasesSpotAndPlate() {
        FailingRepository repo = new FailingRepository();
        ParkingService s = new ParkingService(new ParkingLot(List.of(ParkingFloor.of(0, 1, 1, 1))),
                new BestFitStrategy(), HourlyFeeStrategy.defaults(), repo, clock);
        var before = s.availability();

        assertThrows(IllegalStateException.class, () -> s.checkIn(car("C1")));

        assertEquals(before, s.availability());
        repo.fail = false;
        Ticket t = assertDoesNotThrow(() -> s.checkIn(car("C1")), "plate must not be stuck");
        assertEquals(SpotSize.MEDIUM, t.getSpot().getSize());
    }

    @Test
    void idsAreNotGuessableSequences() {
        String a = service.checkIn(car("C1")).getId();
        String b = service.checkIn(new Vehicle("M1", VehicleType.MOTORCYCLE)).getId();
        assertDoesNotThrow(() -> java.util.UUID.fromString(a));
        assertDoesNotThrow(() -> java.util.UUID.fromString(b));
    }

    @Test
    void motorcycleNeverTakesLargeSpotWhenPolicyIsOff() {
        ParkingService s = new ParkingService(new ParkingLot(List.of(ParkingFloor.of(0, 0, 0, 1)), false),
                new BestFitStrategy(), HourlyFeeStrategy.defaults(), new InMemoryTicketRepository(), clock);
        assertThrows(ParkingLotFullException.class, () -> s.checkIn(new Vehicle("M1", VehicleType.MOTORCYCLE)));
        assertDoesNotThrow(() -> s.checkIn(new Vehicle("B1", VehicleType.BUS)));
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
        List<Throwable> failures = new java.util.ArrayList<>();
        for (Future<?> f : futures) {
            try { f.get(); } catch (ExecutionException e) { failures.add(e.getCause()); }
        }
        pool.shutdown();
        // tasks catch the exceptions they expect, anything that gets here is a real problem
        if (!failures.isEmpty()) {
            AssertionError err = new AssertionError(failures.size() + " task(s) failed, first: " + failures.get(0));
            err.initCause(failures.get(0));
            throw err;
        }
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
        assertFee(BigDecimal.valueOf(100), service.checkOut(bus.getId()));
    }

    @Test
    void stayWithinGraceIsFree() {
        Ticket t = service.checkIn(car("C1"));
        clock.advance(Duration.ofMinutes(10));
        assertFee(BigDecimal.ZERO, service.checkOut(t.getId()));
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
        List<Ticket> issued = new CopyOnWriteArrayList<>();
        runConcurrently(25, () -> {
            int n = counter.getAndIncrement();
            if (n < 5) {
                s.checkOut(parked.get(n).getId());
            } else {
                try { issued.add(s.checkIn(car("NEW" + n))); }
                catch (ParkingLotFullException ignored) { }
            }
            return null;
        });

        // 10 spots, 5 were already free and 5 get freed, so anywhere from 5 to 10 newcomers fit
        assertTrue(issued.size() >= 5 && issued.size() <= 10, "entered " + issued.size());
        assertEquals(issued.size(), issued.stream().map(t -> t.getSpot().getId()).distinct().count(),
                "tickets must hold distinct spots");
        // exact: every spot not held by an issued ticket is free, and the old cars are all gone
        assertEquals(10 - issued.size(), s.availability().get(SpotSize.MEDIUM));
        parked.forEach(t -> assertEquals(TicketStatus.COMPLETED, t.getStatus()));
    }
}
