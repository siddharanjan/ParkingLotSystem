package com.airtribe.parkinglot.strategy;

import com.airtribe.parkinglot.model.ParkingSpot;
import com.airtribe.parkinglot.model.SpotPool;
import com.airtribe.parkinglot.model.SpotSize;
import com.airtribe.parkinglot.model.VehicleType;

import java.util.Optional;

// smallest spot that fits first so large ones stay free for buses, then lowest floor, then id
public class BestFitStrategy implements SpotAllocationStrategy {
    @Override
    public Optional<ParkingSpot> acquire(SpotPool pool, VehicleType type) {
        for (SpotSize size : pool.compatibleSizes(type)) {
            Optional<ParkingSpot> spot = pool.pollFirst(size);
            if (spot.isPresent()) {
                return spot;
            }
        }
        return Optional.empty();
    }
}
