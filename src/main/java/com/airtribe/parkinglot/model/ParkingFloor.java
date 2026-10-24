package com.airtribe.parkinglot.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ParkingFloor {
    private final int number;
    private final List<ParkingSpot> spots;

    public ParkingFloor(int number, List<ParkingSpot> spots) {
        this.number = number;
        this.spots = List.copyOf(spots);
    }

    // spot ids look like F0-S1, F0-M2 etc
    public static ParkingFloor of(int number, int small, int medium, int large) {
        List<ParkingSpot> spots = new ArrayList<>();
        add(spots, number, SpotSize.SMALL, small);
        add(spots, number, SpotSize.MEDIUM, medium);
        add(spots, number, SpotSize.LARGE, large);
        return new ParkingFloor(number, spots);
    }

    private static void add(List<ParkingSpot> out, int floor, SpotSize size, int count) {
        for (int i = 1; i <= count; i++) {
            out.add(new ParkingSpot("F" + floor + "-" + size.name().charAt(0) + i, floor, size));
        }
    }

    public int getNumber() { return number; }

    public List<ParkingSpot> getSpots() { return Collections.unmodifiableList(spots); }
}
