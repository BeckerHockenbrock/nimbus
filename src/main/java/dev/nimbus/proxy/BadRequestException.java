package dev.nimbus.proxy;

/** A client error found while validating a request: status 400 or 414, plus a human-readable reason. */
public final class BadRequestException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int status;

    public BadRequestException(int status, String reason) {
        super(reason);
        if (status != 400 && status != 414) {
            throw new IllegalArgumentException("status must be 400 or 414, got " + status);
        }
        this.status = status;
    }

    public int status() {
        return status;
    }

    public String reason() {
        return getMessage();
    }
}
