package com.airtribe.parkinglot.strategy;

import com.airtribe.parkinglot.model.ParkingFloor;
import com.airtribe.parkinglot.model.ParkingSpot;
import com.airtribe.parkinglot.model.VehicleType;

import java.util.Comparator;
import java.util.List;

// lowest floor first (closest to exit), then smallest size that fits
public class NearestFloorFirstStrategy implements SpotAllocationStrategy {
    private static final Comparator<ParkingSpot> ORDER = Comparator
            .comparingInt(ParkingSpot::getFloor)
            .thenComparing(ParkingSpot::getSize)
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
