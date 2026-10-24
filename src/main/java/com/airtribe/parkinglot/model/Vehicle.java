package com.airtribe.parkinglot.model;

import java.util.Objects;

public final class Vehicle {
    private final String licensePlate;
    private final VehicleType type;

    public Vehicle(String licensePlate, VehicleType type) {
        if (licensePlate == null || licensePlate.isBlank()) {
            throw new IllegalArgumentException("License plate is required");
        }
        this.licensePlate = licensePlate.trim().toUpperCase();
        this.type = Objects.requireNonNull(type, "type");
    }

    public String getLicensePlate() { return licensePlate; }

    public VehicleType getType() { return type; }

    @Override
    public boolean equals(Object o) {
        return o instanceof Vehicle v && v.licensePlate.equals(licensePlate);
    }

    @Override
    public int hashCode() { return licensePlate.hashCode(); }

    @Override
    public String toString() { return type + "[" + licensePlate + "]"; }
}
