package com.toyrental.booking.dto;

import com.toyrental.booking.entity.Customer;

import java.time.LocalDateTime;

public record CustomerResponse(
        String id,
        String name,
        String phone,
        String email,
        String area,
        String flat,
        String building,
        String city,
        String pincode,
        LocalDateTime createdAt
) {

    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(
                customer.getId(),
                customer.getName(),
                customer.getPhone(),
                customer.getEmail(),
                customer.getArea(),
                customer.getFlat(),
                customer.getBuilding(),
                customer.getCity(),
                customer.getPincode(),
                customer.getCreatedAt()
        );
    }
}
