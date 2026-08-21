package com.toyrental.toy.repository;

import com.toyrental.toy.entity.AvailabilityAction;
import com.toyrental.toy.entity.ToyAvailabilityLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToyAvailabilityLogRepository extends JpaRepository<ToyAvailabilityLog, String> {

    boolean existsByToyIdAndBookingIdAndAction(String toyId, String bookingId, AvailabilityAction action);

}
