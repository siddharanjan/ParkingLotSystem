package com.airtribe.parkinglot.repository;

import com.airtribe.parkinglot.model.Ticket;

import java.util.Optional;

public interface TicketRepository {
    void save(Ticket ticket);

    Optional<Ticket> findById(String id);
}
