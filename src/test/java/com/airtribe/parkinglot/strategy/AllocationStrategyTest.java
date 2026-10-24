package com.airtribe.parkinglot.strategy;

import com.airtribe.parkinglot.exception.ParkingLotFullException;
import com.airtribe.parkinglot.model.ParkingFloor;
import com.airtribe.parkinglot.model.ParkingLot;
import com.airtribe.parkinglot.model.ParkingSpot;
import com.airtribe.parkinglot.model.SpotSize;
import com.airtribe.parkinglot.model.VehicleType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AllocationStrategyTest {
    // floor 0: M1 L1   floor 1: S1 M1 L1
    private final List<ParkingFloor> floors = List.of(
            ParkingFloor.of(0, 0, 1, 1),
            ParkingFloor.of(1, 1, 1, 1));

    // keeps acquiring until the lot says full, gives back the order spots were handed out
    private static List<String> drain(ParkingLot lot, SpotAllocationStrategy s, VehicleType t) {
        List<String> out = new ArrayList<>();
        while (true) {
            try {
                out.add(lot.acquire(t, s).getId());
            } catch (ParkingLotFullException e) {
                return out;
            }
        }
    }

    private List<String> ids(SpotAllocationStrategy s, VehicleType t) {
        return drain(new ParkingLot(floors), s, t);
    }

    @Test
    void bestFitOrdersBySizeThenFloor() {
        assertEquals(List.of("F1-S1", "F0-M1", "F1-M1", "F0-L1", "F1-L1"),
                ids(new BestFitStrategy(), VehicleType.MOTORCYCLE));
    }

    @Test
    void nearestFloorOrdersByFloorThenSize() {
        assertEquals(List.of("F0-M1", "F0-L1", "F1-S1", "F1-M1", "F1-L1"),
                ids(new NearestFloorFirstStrategy(), VehicleType.MOTORCYCLE));
    }

    @Test
    void onlyCompatibleSpotsAreOffered() {
        assertEquals(List.of("F0-M1", "F1-M1", "F0-L1", "F1-L1"), ids(new BestFitStrategy(), VehicleType.CAR));
        assertEquals(List.of("F0-L1", "F1-L1"), ids(new BestFitStrategy(), VehicleType.BUS));
    }

    @Test
    void takenSpotsAreSkipped() {
        ParkingLot lot = new ParkingLot(floors);
        BestFitStrategy best = new BestFitStrategy();
        assertEquals("F1-S1", lot.acquire(VehicleType.MOTORCYCLE, best).getId());
        assertEquals("F0-M1", lot.acquire(VehicleType.MOTORCYCLE, best).getId());
    }

    @Test
    void fullLotGivesNothing() {
        ParkingLot lot = new ParkingLot(floors);
        drain(lot, new BestFitStrategy(), VehicleType.MOTORCYCLE);
        assertThrows(ParkingLotFullException.class, () -> lot.acquire(VehicleType.MOTORCYCLE, new BestFitStrategy()));
        assertThrows(ParkingLotFullException.class, () -> lot.acquire(VehicleType.MOTORCYCLE, new NearestFloorFirstStrategy()));
    }

    @Test
    void noFloorsGivesNothing() {
        ParkingLot empty = new ParkingLot(List.of());
        assertThrows(ParkingLotFullException.class, () -> empty.acquire(VehicleType.CAR, new BestFitStrategy()));
        assertThrows(ParkingLotFullException.class, () -> empty.acquire(VehicleType.CAR, new NearestFloorFirstStrategy()));
    }

    @Test
    void busFindsNothingWhenOnlyLargeSpotsAreTaken() {
        ParkingLot lot = new ParkingLot(floors);
        BestFitStrategy best = new BestFitStrategy();
        lot.acquire(VehicleType.BUS, best);
        lot.acquire(VehicleType.BUS, best);
        assertThrows(ParkingLotFullException.class, () -> lot.acquire(VehicleType.BUS, best));
        // cars can still use the medium ones
        assertEquals(List.of("F0-M1", "F1-M1"), drain(lot, best, VehicleType.CAR));
    }

    @Test
    void nearestFloorHandlesCarsAndBuses() {
        assertEquals(List.of("F0-M1", "F0-L1", "F1-M1", "F1-L1"),
                ids(new NearestFloorFirstStrategy(), VehicleType.CAR));
        assertEquals(List.of("F0-L1", "F1-L1"), ids(new NearestFloorFirstStrategy(), VehicleType.BUS));
    }

    @Test
    void sameSizeAndFloorTiesBrokenBySpotId() {
        ParkingLot one = new ParkingLot(List.of(ParkingFloor.of(0, 0, 3, 0)));
        assertEquals(List.of("F0-M1", "F0-M2", "F0-M3"), drain(one, new BestFitStrategy(), VehicleType.CAR));
    }

    @Test
    void acquireAndReleaseMoveTheCounters() {
        ParkingLot lot = new ParkingLot(floors);
        ParkingSpot s = lot.acquire(VehicleType.CAR, new BestFitStrategy());
        assertEquals(1, lot.availability().get(SpotSize.MEDIUM));
        lot.release(s);
        assertEquals(2, lot.availability().get(SpotSize.MEDIUM));
        assertEquals(s, lot.acquire(VehicleType.CAR, new BestFitStrategy()), "freed spot is first in line again");
    }

    @Test
    void motorcyclesCanBeKeptOutOfLargeSpots() {
        ParkingLot strict = new ParkingLot(floors, false);
        assertEquals(List.of("F1-S1", "F0-M1", "F1-M1"), drain(strict, new BestFitStrategy(), VehicleType.MOTORCYCLE));
        assertEquals(2, strict.availableFor(VehicleType.BUS));
    }

    @Test
    void parallelAcquiresNeverHandOutTheSameSpot() throws Exception {
        for (SpotAllocationStrategy strategy : List.of(new BestFitStrategy(), new NearestFloorFirstStrategy())) {
            ParkingLot lot = new ParkingLot(List.of(ParkingFloor.of(0, 0, 20, 0), ParkingFloor.of(1, 0, 20, 0)));
            java.util.Set<String> got = java.util.concurrent.ConcurrentHashMap.newKeySet();
            java.util.concurrent.atomic.AtomicInteger full = new java.util.concurrent.atomic.AtomicInteger();
            Thread[] threads = new Thread[80];
            for (int i = 0; i < threads.length; i++) {
                threads[i] = new Thread(() -> {
                    try { got.add(lot.acquire(VehicleType.CAR, strategy).getId()); }
                    catch (ParkingLotFullException e) { full.incrementAndGet(); }
                });
            }
            for (Thread t : threads) t.start();
            for (Thread t : threads) t.join();
            assertEquals(40, got.size());
            assertEquals(40, full.get());
            assertEquals(0, lot.availability().get(SpotSize.MEDIUM));
        }
    }
}
