package com.airtribe.parkinglot.strategy;

import com.airtribe.parkinglot.model.ParkingSpot;
import com.airtribe.parkinglot.model.SpotPool;
import com.airtribe.parkinglot.model.VehicleType;

import java.util.Optional;

public interface SpotAllocationStrategy {
    // Claims and returns a spot, or empty if nothing fits. The returned spot is already
    // removed from the pool.
    Optional<ParkingSpot> acquire(SpotPool pool, VehicleType type);
}
