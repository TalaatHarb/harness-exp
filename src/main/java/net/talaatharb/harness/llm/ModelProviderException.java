package net.talaatharb.harness.llm;

public class ModelProviderException extends Exception {
    private static final long serialVersionUID = 1L;

    public ModelProviderException(String message) {
        super(message);
    }

    public ModelProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
