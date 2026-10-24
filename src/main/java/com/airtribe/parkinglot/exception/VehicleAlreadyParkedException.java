package com.airtribe.parkinglot.exception;

public class VehicleAlreadyParkedException extends ParkingLotException {
    public VehicleAlreadyParkedException(String message) {
        super(message);
    }
}
