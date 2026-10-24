package com.airtribe.parkinglot.strategy;

import com.airtribe.parkinglot.model.ParkingFloor;
import com.airtribe.parkinglot.model.ParkingSpot;
import com.airtribe.parkinglot.model.VehicleType;
import org.junit.jupiter.api.Test;
import com.airtribe.parkinglot.model.SpotSize;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AllocationStrategyTest {
    // floor 0: M1 L1   floor 1: S1 M1 L1
    private final List<ParkingFloor> floors = List.of(
            ParkingFloor.of(0, 0, 1, 1),
            ParkingFloor.of(1, 1, 1, 1));

    private List<String> ids(SpotAllocationStrategy s, VehicleType t) {
        return s.candidates(floors, t).stream().map(ParkingSpot::getId).toList();
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
    void occupiedSpotsAreSkipped() {
        floors.get(1).getSpots().stream().filter(s -> s.getId().equals("F1-S1")).findFirst().get().tryOccupy();
        assertEquals("F0-M1", ids(new BestFitStrategy(), VehicleType.MOTORCYCLE).get(0));
    }

    private static void fill(List<ParkingFloor> floors) {
        floors.forEach(f -> f.getSpots().forEach(ParkingSpot::tryOccupy));
    }

    @Test
    void fullLotGivesNoCandidates() {
        fill(floors);
        assertTrue(ids(new BestFitStrategy(), VehicleType.MOTORCYCLE).isEmpty());
        assertTrue(ids(new NearestFloorFirstStrategy(), VehicleType.MOTORCYCLE).isEmpty());
    }

    @Test
    void noFloorsGivesNoCandidates() {
        assertTrue(new BestFitStrategy().candidates(List.of(), VehicleType.CAR).isEmpty());
        assertTrue(new NearestFloorFirstStrategy().candidates(List.of(), VehicleType.CAR).isEmpty());
    }

    @Test
    void busFindsNothingWhenOnlyLargeSpotsAreTaken() {
        floors.forEach(f -> f.getSpots().stream()
                .filter(s -> s.getSize() == SpotSize.LARGE).forEach(ParkingSpot::tryOccupy));
        assertTrue(ids(new BestFitStrategy(), VehicleType.BUS).isEmpty());
        // cars can still use the medium ones
        assertEquals(List.of("F0-M1", "F1-M1"), ids(new BestFitStrategy(), VehicleType.CAR));
    }

    @Test
    void nearestFloorHandlesCarsAndBuses() {
        assertEquals(List.of("F0-M1", "F0-L1", "F1-M1", "F1-L1"),
                ids(new NearestFloorFirstStrategy(), VehicleType.CAR));
        assertEquals(List.of("F0-L1", "F1-L1"), ids(new NearestFloorFirstStrategy(), VehicleType.BUS));
    }

    @Test
    void sameSizeAndFloorTiesBrokenBySpotId() {
        List<ParkingFloor> one = List.of(ParkingFloor.of(0, 0, 3, 0));
        List<String> got = new BestFitStrategy().candidates(one, VehicleType.CAR).stream()
                .map(ParkingSpot::getId).toList();
        assertEquals(List.of("F0-M1", "F0-M2", "F0-M3"), got);
    }

    @Test
    void strategiesDontTouchOccupancy() {
        ids(new BestFitStrategy(), VehicleType.CAR);
        assertEquals(5, new BestFitStrategy().candidates(floors, VehicleType.MOTORCYCLE).size());
    }
}
