package com.toyrental.booking.repository;

import com.toyrental.booking.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, String> {

    Optional<Customer> findByPhone(String phone);

    boolean existsByPhone(String phone);

    boolean existsByEmail(String email);

}
