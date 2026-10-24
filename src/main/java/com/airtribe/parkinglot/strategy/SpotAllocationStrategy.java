package com.airtribe.parkinglot.strategy;

import com.airtribe.parkinglot.model.ParkingFloor;
import com.airtribe.parkinglot.model.ParkingSpot;
import com.airtribe.parkinglot.model.VehicleType;

import java.util.List;

public interface SpotAllocationStrategy {
    // Free spots that fit, best first. Caller tries them in order, so if another
    // thread grabs one in the meantime it just moves on to the next.
    List<ParkingSpot> candidates(List<ParkingFloor> floors, VehicleType type);
}
