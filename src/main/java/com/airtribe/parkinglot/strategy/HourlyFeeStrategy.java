package com.airtribe.parkinglot.strategy;

import com.airtribe.parkinglot.model.VehicleType;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

// Rate per hour by vehicle type. Free within the grace period, after that any
// part of an hour is charged as a full hour.
public class HourlyFeeStrategy implements FeeStrategy {
    private final Map<VehicleType, BigDecimal> hourlyRates;
    private final Duration gracePeriod;

    public HourlyFeeStrategy(Map<VehicleType, BigDecimal> hourlyRates, Duration gracePeriod) {
        for (VehicleType t : VehicleType.values()) {
            if (!hourlyRates.containsKey(t)) {
                throw new IllegalArgumentException("Missing hourly rate for " + t);
            }
        }
        this.hourlyRates = new EnumMap<>(hourlyRates);
        this.gracePeriod = gracePeriod;
    }

    public static HourlyFeeStrategy defaults() {
        return new HourlyFeeStrategy(Map.of(
                VehicleType.MOTORCYCLE, BigDecimal.valueOf(10),
                VehicleType.CAR, BigDecimal.valueOf(20),
                VehicleType.BUS, BigDecimal.valueOf(50)), Duration.ofMinutes(15));
    }

    @Override
    public BigDecimal calculate(VehicleType type, Duration stay) {
        if (stay.isNegative()) {
            throw new IllegalArgumentException("Negative stay");
        }
        if (stay.compareTo(gracePeriod) <= 0) {
            return BigDecimal.ZERO;
        }
        long seconds = stay.getSeconds() + (stay.getNano() > 0 ? 1 : 0);
        long hours = (seconds + 3599) / 3600;
        return hourlyRates.get(type).multiply(BigDecimal.valueOf(hours));
    }
}
