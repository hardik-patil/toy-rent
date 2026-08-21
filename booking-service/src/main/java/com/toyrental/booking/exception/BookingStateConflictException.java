package com.toyrental.booking.exception;

public class BookingStateConflictException extends RuntimeException {

    public BookingStateConflictException(String message) {
        super(message);
    }

}
