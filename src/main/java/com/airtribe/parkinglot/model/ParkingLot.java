package com.airtribe.parkinglot.model;

import com.airtribe.parkinglot.strategy.SpotAllocationStrategy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class ParkingLot {
    private final List<ParkingFloor> floors;
    private final SpotPool pool;
    private final List<Consumer<Map<SpotSize, Integer>>> listeners = new CopyOnWriteArrayList<>();

    public ParkingLot(List<ParkingFloor> floors) {
        this(floors, true);
    }

    // set motorcyclesMayUseLarge to false to keep large bays for buses and cars only
    public ParkingLot(List<ParkingFloor> floors, boolean motorcyclesMayUseLarge) {
        this.floors = List.copyOf(floors);
        this.pool = new SpotPool(this.floors, motorcyclesMayUseLarge);
    }

    // throws ParkingLotFullException if nothing fits
    public ParkingSpot acquire(VehicleType type, SpotAllocationStrategy strategy) {
        ParkingSpot spot = pool.acquire(type, strategy);
        notifyListeners();
        return spot;
    }

    // Only call this from whoever won ticket.complete() / ticket.cancel()
    public void release(ParkingSpot spot) {
        pool.release(spot);
        notifyListeners();
    }

    // Called after every acquire/release with the new counts, for push style updates.
    // Keep listeners quick, they run on the caller's thread.
    public void addAvailabilityListener(Consumer<Map<SpotSize, Integer>> listener) {
        listeners.add(listener);
    }

    private void notifyListeners() {
        if (!listeners.isEmpty()) {
            Map<SpotSize, Integer> now = pool.availability();
            listeners.forEach(l -> l.accept(now));
        }
    }

    // not an atomic snapshot, see SpotPool.availability
    public Map<SpotSize, Integer> availability() { return pool.availability(); }

    public int availableFor(VehicleType type) { return pool.availableFor(type); }

    public List<ParkingFloor> getFloors() { return floors; }
}
