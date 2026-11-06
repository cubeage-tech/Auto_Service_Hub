package com.autoservicehub.exception;

/**
 * Raised when a Government / Aadhaar ID is submitted but the server has no
 * usable encryption key (see {@code GovernmentIdEncryptionService}).
 *
 * <p>This is a <em>server configuration</em> fault, not a client fault: the
 * request is well-formed and the caller is authorised, but the application is
 * deliberately unable to store the value in plaintext. It is therefore mapped
 * to 503 SERVICE_UNAVAILABLE with an actionable message rather than falling
 * through to the generic 500 handler, which would report an opaque
 * "Something went wrong" for a problem an operator can fix by setting
 * {@code MECHANIC_GOVID_ENCRYPTION_KEY}.
 *
 * <p>No Government ID material is carried in this exception.
 */
public class GovernmentIdKeyNotConfiguredException extends RuntimeException {

    public GovernmentIdKeyNotConfiguredException(String message) {
        super(message);
    }
}