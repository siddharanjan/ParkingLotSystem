package com.airtribe.parkinglot.strategy;

import com.airtribe.parkinglot.model.ParkingSpot;
import com.airtribe.parkinglot.model.SpotPool;
import com.airtribe.parkinglot.model.VehicleType;

import java.util.Comparator;
import java.util.Optional;

// lowest floor first (closest to exit), then smallest size that fits
public class NearestFloorFirstStrategy implements SpotAllocationStrategy {
    private static final Comparator<ParkingSpot> ORDER = Comparator
            .comparingInt(ParkingSpot::getFloor)
            .thenComparing(ParkingSpot::getSize)
            .thenComparing(ParkingSpot::getId);

    @Override
    public Optional<ParkingSpot> acquire(SpotPool pool, VehicleType type) {
        while (true) {
            // each size's set is already sorted by floor, so only the heads matter
            Optional<ParkingSpot> best = pool.compatibleSizes(type).stream()
                    .map(pool::peekFirst)
                    .flatMap(Optional::stream)
                    .min(ORDER);
            if (best.isEmpty()) {
                return Optional.empty();
            }
            // someone else may have taken it since we peeked, then look again
            if (pool.take(best.get())) {
                return best;
            }
        }
    }
}
