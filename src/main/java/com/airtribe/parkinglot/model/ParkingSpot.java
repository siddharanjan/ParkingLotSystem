package com.airtribe.parkinglot.model;

// Just the bay itself. Whether it's free or not is tracked by SpotPool.
public final class ParkingSpot {
    private final String id;
    private final int floor;
    private final SpotSize size;

    public ParkingSpot(String id, int floor, SpotSize size) {
        this.id = id;
        this.floor = floor;
        this.size = size;
    }

    public String getId() { return id; }

    public int getFloor() { return floor; }

    public SpotSize getSize() { return size; }

    @Override
    public String toString() { return id + "(" + size + ")"; }
}
