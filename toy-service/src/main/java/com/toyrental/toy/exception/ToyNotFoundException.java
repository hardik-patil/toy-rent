package com.toyrental.toy.exception;

public class ToyNotFoundException extends RuntimeException {

    public ToyNotFoundException(String toyId) {
        super("Toy not found: " + toyId);
    }

}
