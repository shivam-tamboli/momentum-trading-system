package com.momentum.exception;

// Thrown by EmailService.doSend when the underlying SMTP call fails. The regular business-email
// paths (send()) catch this immediately and swallow it — a notification failure must never break
// trading/scoring. /admin/test-email is the one caller that lets it propagate, since surfacing the
// exact SMTP error is the entire point of that endpoint.
public class EmailSendException extends RuntimeException {

    public EmailSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
