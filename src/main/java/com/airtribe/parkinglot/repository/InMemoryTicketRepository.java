package com.airtribe.parkinglot.repository;

import com.airtribe.parkinglot.model.Ticket;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryTicketRepository implements TicketRepository {
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    @Override
    public void save(Ticket ticket) { tickets.put(ticket.getId(), ticket); }

    @Override
    public Optional<Ticket> findById(String id) { return Optional.ofNullable(tickets.get(id)); }
}
