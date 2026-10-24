package com.airtribe.parkinglot;

import com.airtribe.parkinglot.model.ParkingFloor;
import com.airtribe.parkinglot.model.ParkingLot;
import com.airtribe.parkinglot.model.Ticket;
import com.airtribe.parkinglot.model.Vehicle;
import com.airtribe.parkinglot.model.VehicleType;
import com.airtribe.parkinglot.repository.InMemoryTicketRepository;
import com.airtribe.parkinglot.service.ParkingService;
import com.airtribe.parkinglot.strategy.BestFitStrategy;
import com.airtribe.parkinglot.strategy.HourlyFeeStrategy;

import java.time.Clock;
import java.util.List;

public class ParkingLotApp {
    public static void main(String[] args) {
        ParkingLot lot = new ParkingLot(List.of(
                ParkingFloor.of(0, 2, 3, 1),
                ParkingFloor.of(1, 2, 3, 1)));
        ParkingService service = new ParkingService(lot, new BestFitStrategy(),
                HourlyFeeStrategy.defaults(), new InMemoryTicketRepository(), Clock.systemUTC());

        Ticket car = service.checkIn(new Vehicle("KA01AB1234", VehicleType.CAR));
        Ticket bus = service.checkIn(new Vehicle("KA05ZZ9999", VehicleType.BUS));
        System.out.println(car.getId() + " -> " + car.getSpot() + ", " + bus.getId() + " -> " + bus.getSpot());
        System.out.println("Availability: " + service.availability());

        System.out.println("Car fee: " + service.checkOut(car.getId()));
        service.cancel(bus.getId());
        System.out.println("Availability: " + service.availability());
    }
}
