package net.talaatharb.harness.tools.dynamic;

/** Creation failure whose message is meant to be shown to the model so it can fix the source and retry. */
public class DynamicToolException extends Exception {
    private static final long serialVersionUID = 1L;

    public DynamicToolException(String message) {
        super(message);
    }

    public DynamicToolException(String message, Throwable cause) {
        super(message, cause);
    }
}