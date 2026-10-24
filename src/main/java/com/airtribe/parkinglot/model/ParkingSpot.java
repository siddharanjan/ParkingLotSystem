package com.airtribe.parkinglot.model;

import java.util.concurrent.atomic.AtomicBoolean;

// CAS on the flag so two threads can't grab the same spot, no big lock needed
public final class ParkingSpot {
    private final String id;
    private final int floor;
    private final SpotSize size;
    private final AtomicBoolean occupied = new AtomicBoolean(false);

    public ParkingSpot(String id, int floor, SpotSize size) {
        this.id = id;
        this.floor = floor;
        this.size = size;
    }

    public boolean tryOccupy() { return occupied.compareAndSet(false, true); }

    public boolean release() { return occupied.compareAndSet(true, false); }

    public boolean isFree() { return !occupied.get(); }

    public String getId() { return id; }

    public int getFloor() { return floor; }

    public SpotSize getSize() { return size; }

    @Override
    public String toString() { return id + "(" + size + ")"; }
}
