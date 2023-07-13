package tech.cae.nativeloader;

public class NativeLoaderException extends Exception {

    public NativeLoaderException(String message) {
        super(message);
    }

    public NativeLoaderException(String message, Throwable cause) {
        super(message, cause);
    }
}
