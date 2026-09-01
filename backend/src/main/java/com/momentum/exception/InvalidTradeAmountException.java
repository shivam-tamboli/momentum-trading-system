package com.momentum.exception;

public class InvalidTradeAmountException extends RuntimeException {

    public InvalidTradeAmountException(String message) {
        super(message);
    }
}
