package com.airtribe.parkinglot.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class ParkingLot {
    private final List<ParkingFloor> floors;
    private final Map<SpotSize, AtomicInteger> freeBySize = new EnumMap<>(SpotSize.class);

    public ParkingLot(List<ParkingFloor> floors) {
        this.floors = List.copyOf(floors);
        for (SpotSize s : SpotSize.values()) {
            freeBySize.put(s, new AtomicInteger());
        }
        floors.forEach(f -> f.getSpots().forEach(sp -> freeBySize.get(sp.getSize()).incrementAndGet()));
    }

    // false if someone else got the spot first
    public boolean occupy(ParkingSpot spot) {
        if (!spot.tryOccupy()) {
            return false;
        }
        freeBySize.get(spot.getSize()).decrementAndGet();
        return true;
    }

    public void release(ParkingSpot spot) {
        if (spot.release()) {
            freeBySize.get(spot.getSize()).incrementAndGet();
        }
    }

    public Map<SpotSize, Integer> availability() {
        Map<SpotSize, Integer> snapshot = new EnumMap<>(SpotSize.class);
        freeBySize.forEach((k, v) -> snapshot.put(k, v.get()));
        return Collections.unmodifiableMap(snapshot);
    }

    public int availableFor(VehicleType type) {
        return freeBySize.entrySet().stream()
                .filter(e -> type.fitsIn(e.getKey()))
                .mapToInt(e -> e.getValue().get())
                .sum();
    }

    public List<ParkingFloor> getFloors() { return floors; }
}
