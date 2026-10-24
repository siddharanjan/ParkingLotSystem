package com.airtribe.parkinglot.model;

import java.util.List;

public enum VehicleType {
    MOTORCYCLE(List.of(SpotSize.SMALL, SpotSize.MEDIUM, SpotSize.LARGE)),
    CAR(List.of(SpotSize.MEDIUM, SpotSize.LARGE)),
    BUS(List.of(SpotSize.LARGE));

    private final List<SpotSize> compatibleSizes;

    VehicleType(List<SpotSize> compatibleSizes) {
        this.compatibleSizes = compatibleSizes;
    }

    // spot sizes this vehicle can use, tightest fit first
    public List<SpotSize> compatibleSizesBestFirst() {
        return compatibleSizes;
    }
}
