package com.airtribe.parkinglot.strategy;

import com.airtribe.parkinglot.model.ParkingFloor;
import com.airtribe.parkinglot.model.ParkingSpot;
import com.airtribe.parkinglot.model.VehicleType;

import java.util.Comparator;
import java.util.List;

// smallest spot that fits first so large ones stay free for buses, then lowest floor, then id
public class BestFitStrategy implements SpotAllocationStrategy {
    private static final Comparator<ParkingSpot> ORDER = Comparator
            .comparing(ParkingSpot::getSize)
            .thenComparingInt(ParkingSpot::getFloor)
            .thenComparing(ParkingSpot::getId);

    @Override
    public List<ParkingSpot> candidates(List<ParkingFloor> floors, VehicleType type) {
        return floors.stream()
                .flatMap(f -> f.getSpots().stream())
                .filter(ParkingSpot::isFree)
                .filter(s -> type.fitsIn(s.getSize()))
                .sorted(ORDER)
                .toList();
    }
}
