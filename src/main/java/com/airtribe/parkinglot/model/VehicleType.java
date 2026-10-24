package com.airtribe.parkinglot.model;

public enum VehicleType {
    MOTORCYCLE(SpotSize.SMALL),
    CAR(SpotSize.MEDIUM),
    BUS(SpotSize.LARGE);

    private final SpotSize minimumSpotSize;

    VehicleType(SpotSize minimumSpotSize) {
        this.minimumSpotSize = minimumSpotSize;
    }

    // bigger spots are fine too, e.g. a bike can use a large spot
    public boolean fitsIn(SpotSize size) {
        return size.ordinal() >= minimumSpotSize.ordinal();
    }
}
