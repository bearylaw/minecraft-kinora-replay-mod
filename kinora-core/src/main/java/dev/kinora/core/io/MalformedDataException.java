package dev.kinora.core.io;

/** Data that does not decode: truncated, corrupt, or written by an incompatible version. */
public class MalformedDataException extends RuntimeException {
    public MalformedDataException(String message) {
        super(message);
    }

    public MalformedDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
