package com.toyrental.booking.repository;

import com.toyrental.booking.entity.Payment;
import com.toyrental.booking.entity.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    List<Payment> findByBookingId(String bookingId);

    List<Payment> findByRazorpayOrderIdAndStatus(String razorpayOrderId, PaymentStatus status);

}
