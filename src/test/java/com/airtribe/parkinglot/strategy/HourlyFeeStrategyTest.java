package com.airtribe.parkinglot.strategy;

import com.airtribe.parkinglot.model.VehicleType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HourlyFeeStrategyTest {
    private final HourlyFeeStrategy fees = HourlyFeeStrategy.defaults(); // 10 / 20 / 50, 15 min grace

    private BigDecimal fee(VehicleType t, Duration d) { return fees.calculate(t, d); }

    // compare by value so 40 and 40.00 are the same
    private static void assertFee(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void freeWithinGrace() {
        assertFee(BigDecimal.ZERO, fee(VehicleType.CAR, Duration.ZERO));
        assertFee(BigDecimal.ZERO, fee(VehicleType.CAR, Duration.ofMinutes(15)));
    }

    @Test
    void justOverGraceBillsOneHour() {
        assertFee(BigDecimal.valueOf(20), fee(VehicleType.CAR, Duration.ofMinutes(15).plusSeconds(1)));
    }

    @Test
    void exactHourBillsOneHourAndStartedHourRoundsUp() {
        assertFee(BigDecimal.valueOf(20), fee(VehicleType.CAR, Duration.ofHours(1)));
        assertFee(BigDecimal.valueOf(40), fee(VehicleType.CAR, Duration.ofHours(1).plusSeconds(1)));
    }

    @Test
    void rateDependsOnVehicleType() {
        Duration threeHours = Duration.ofHours(3);
        assertFee(BigDecimal.valueOf(30), fee(VehicleType.MOTORCYCLE, threeHours));
        assertFee(BigDecimal.valueOf(60), fee(VehicleType.CAR, threeHours));
        assertFee(BigDecimal.valueOf(150), fee(VehicleType.BUS, threeHours));
    }

    @Test
    void rejectsNegativeStayAndIncompleteRates() {
        assertThrows(IllegalArgumentException.class, () -> fee(VehicleType.CAR, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> new HourlyFeeStrategy(Map.of(VehicleType.CAR, BigDecimal.ONE), Duration.ZERO));
    }

    @Test
    void customGracePeriodIsUsed() {
        HourlyFeeStrategy f = new HourlyFeeStrategy(Map.of(
                VehicleType.MOTORCYCLE, BigDecimal.TEN,
                VehicleType.CAR, BigDecimal.TEN,
                VehicleType.BUS, BigDecimal.TEN), Duration.ofMinutes(30));
        assertFee(BigDecimal.ZERO, f.calculate(VehicleType.CAR, Duration.ofMinutes(30)));
        assertFee(BigDecimal.TEN, f.calculate(VehicleType.CAR, Duration.ofMinutes(31)));
    }

    @Test
    void zeroGraceChargesImmediately() {
        HourlyFeeStrategy f = new HourlyFeeStrategy(Map.of(
                VehicleType.MOTORCYCLE, BigDecimal.TEN,
                VehicleType.CAR, BigDecimal.TEN,
                VehicleType.BUS, BigDecimal.TEN), Duration.ZERO);
        assertFee(BigDecimal.ZERO, f.calculate(VehicleType.CAR, Duration.ZERO));
        assertFee(BigDecimal.TEN, f.calculate(VehicleType.CAR, Duration.ofNanos(1)));
    }

    @Test
    void nanosecondsPastAnHourRoundUp() {
        assertFee(BigDecimal.valueOf(20), fee(VehicleType.CAR, Duration.ofHours(1)));
        assertFee(BigDecimal.valueOf(40), fee(VehicleType.CAR, Duration.ofHours(1).plusNanos(1)));
    }

    @Test
    void justUnderGraceInNanosIsFree() {
        assertFee(BigDecimal.ZERO, fee(VehicleType.BUS, Duration.ofMinutes(15).minusNanos(1)));
    }

    @Test
    void longStaysScaleLinearly() {
        assertFee(BigDecimal.valueOf(24 * 7 * 20L), fee(VehicleType.CAR, Duration.ofDays(7)));
        assertFee(BigDecimal.valueOf(24 * 30 * 50L), fee(VehicleType.BUS, Duration.ofDays(30)));
    }

    @Test
    void feesAreRoundedToTwoDecimals() {
        assertEquals(2, fee(VehicleType.CAR, Duration.ofHours(2)).scale());
        assertEquals(2, fee(VehicleType.CAR, Duration.ZERO).scale());
        HourlyFeeStrategy odd = new HourlyFeeStrategy(Map.of(
                VehicleType.MOTORCYCLE, new BigDecimal("0.005"),
                VehicleType.CAR, new BigDecimal("0.005"),
                VehicleType.BUS, new BigDecimal("0.005")), Duration.ZERO);
        assertEquals(new BigDecimal("0.01"), odd.calculate(VehicleType.CAR, Duration.ofHours(1))); // half up
    }

    @Test
    void nullRateMapIsRejected() {
        assertThrows(NullPointerException.class, () -> new HourlyFeeStrategy(null, Duration.ZERO));
    }
}
