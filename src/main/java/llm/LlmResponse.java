package llm;

/**
 * A language model's answer: its text when the call worked, or an error (no connection, timeout,
 * no answer left) when it did not.
 */
public class LlmResponse {

    private final String text;
    private final boolean ok;
    private final String error;

    /**
     * @param text  the answer, or {@code null} if the call failed
     * @param error why the call failed, or {@code null} if it worked
     * @throws IllegalArgumentException if a successful response has no text or a failed one no error
     */
    public LlmResponse(String text, boolean ok, String error) {
        if (ok && text == null) {
            throw new IllegalArgumentException("A successful response needs text");
        }
        if (!ok && (error == null || error.isBlank())) {
            throw new IllegalArgumentException("A failed response needs an error");
        }
        this.text = text;
        this.ok = ok;
        this.error = error;
    }

    /** The answer, or {@code null} if the call failed. */
    public String getText() {
        return text;
    }

    public boolean isOk() {
        return ok;
    }

    /** Why the call failed, or {@code null} if it worked. */
    public String getError() {
        return error;
    }
}
