package com.airtribe.parkinglot.strategy;

import com.airtribe.parkinglot.model.VehicleType;

import java.math.BigDecimal;
import java.time.Duration;

public interface FeeStrategy {
    BigDecimal calculate(VehicleType type, Duration stay);
}
