package com.airtribe.parkinglot.model;

import com.airtribe.parkinglot.exception.ParkingLotFullException;
import com.airtribe.parkinglot.strategy.SpotAllocationStrategy;

import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;

// Free spots, one sorted set per size (lowest floor first). A spot is "claimed" by
// removing it from its set, which is atomic, so two threads can't get the same one.
// Counters are kept separately because the set's size() is O(n).
public final class SpotPool {
    private static final Comparator<ParkingSpot> FLOOR_THEN_ID = Comparator
            .comparingInt(ParkingSpot::getFloor)
            .thenComparing(ParkingSpot::getId);

    private final Map<SpotSize, ConcurrentSkipListSet<ParkingSpot>> free = new EnumMap<>(SpotSize.class);
    private final Map<SpotSize, AtomicInteger> counts = new EnumMap<>(SpotSize.class);
    private final boolean motorcyclesMayUseLarge;

    public SpotPool(List<ParkingFloor> floors, boolean motorcyclesMayUseLarge) {
        this.motorcyclesMayUseLarge = motorcyclesMayUseLarge;
        for (SpotSize s : SpotSize.values()) {
            free.put(s, new ConcurrentSkipListSet<>(FLOOR_THEN_ID));
            counts.put(s, new AtomicInteger());
        }
        for (ParkingFloor f : floors) {
            for (ParkingSpot spot : f.getSpots()) {
                release(spot);
            }
        }
    }

    // sizes this vehicle may use, best fit first, after applying the lot's overflow policy
    public List<SpotSize> compatibleSizes(VehicleType type) {
        List<SpotSize> sizes = type.compatibleSizesBestFirst();
        if (type == VehicleType.MOTORCYCLE && !motorcyclesMayUseLarge) {
            return sizes.stream().filter(s -> s != SpotSize.LARGE).toList();
        }
        return sizes;
    }

    public ParkingSpot acquire(VehicleType type, SpotAllocationStrategy strategy) {
        return strategy.acquire(this, type)
                .orElseThrow(() -> new ParkingLotFullException("No spot available for " + type));
    }

    public Optional<ParkingSpot> pollFirst(SpotSize size) {
        ParkingSpot spot = free.get(size).pollFirst();
        if (spot == null) {
            return Optional.empty();
        }
        counts.get(size).decrementAndGet();
        return Optional.of(spot);
    }

    public Optional<ParkingSpot> peekFirst(SpotSize size) {
        try {
            return Optional.of(free.get(size).first());
        } catch (java.util.NoSuchElementException e) {
            return Optional.empty();
        }
    }

    // true only for the one caller that actually removed it
    public boolean take(ParkingSpot spot) {
        if (free.get(spot.getSize()).remove(spot)) {
            counts.get(spot.getSize()).decrementAndGet();
            return true;
        }
        return false;
    }

    // adding a spot that's already free is a no-op, so a double release can't inflate the counter
    public void release(ParkingSpot spot) {
        if (free.get(spot.getSize()).add(spot)) {
            counts.get(spot.getSize()).incrementAndGet();
        }
    }

    // Each size is read separately, so under load this is not one consistent snapshot.
    public Map<SpotSize, Integer> availability() {
        Map<SpotSize, Integer> snapshot = new EnumMap<>(SpotSize.class);
        counts.forEach((k, v) -> snapshot.put(k, v.get()));
        return Collections.unmodifiableMap(snapshot);
    }

    public int availableFor(VehicleType type) {
        return compatibleSizes(type).stream().mapToInt(s -> counts.get(s).get()).sum();
    }
}
