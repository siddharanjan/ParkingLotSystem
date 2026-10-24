package com.airtribe.parkinglot.exception;

public class CancellationNotAllowedException extends ParkingLotException {
    public CancellationNotAllowedException(String message) {
        super(message);
    }
}
