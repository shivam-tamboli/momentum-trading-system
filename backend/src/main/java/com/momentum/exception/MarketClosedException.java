package com.momentum.exception;

public class MarketClosedException extends RuntimeException {

    public MarketClosedException(String message) {
        super(message);
    }
}
