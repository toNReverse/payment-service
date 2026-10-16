package io.github.tonreverse.payment.payment;

import java.io.Serial;

/** The payment provider failed or could not be reached. The API answers 502, and the client may retry. */
public final class PaymentGatewayException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }

    public PaymentGatewayException(String message) {
        super(message);
    }
}
