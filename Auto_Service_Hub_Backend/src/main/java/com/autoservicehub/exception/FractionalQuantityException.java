package com.autoservicehub.exception;

/**
 * A whole-number field arrived carrying a fractional part, e.g.
 * {@code "quantity": 2.9}.
 *
 * <p>Exists as its own type because Jackson reports it as a generic
 * deserialisation failure, and the caller deserves to be told which field and
 * what value was wrong rather than "Request body is malformed."
 *
 * <p>Raised during JSON binding, so it is thrown from a deserialiser and
 * surfaces to {@code GlobalExceptionHandler} wrapped in Jackson's own
 * exception; the handler walks the cause chain to find it. It is deliberately
 * not a {@link BusinessRuleException}: the value never reaches a business rule,
 * it fails to bind to the field's type at all.
 */
public class FractionalQuantityException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FractionalQuantityException(String message) {
        super(message);
    }
}