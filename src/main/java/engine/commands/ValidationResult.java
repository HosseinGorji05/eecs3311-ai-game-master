package engine.commands;

/** Whether a command may run in the current state, and if not, why. */
public class ValidationResult {

    private final boolean valid;
    private final String reason;

    /**
     * @param reason why the command is invalid; may be {@code null} when {@code valid} is true
     * @throws IllegalArgumentException if the result is invalid and has no reason
     */
    public ValidationResult(boolean valid, String reason) {
        if (!valid && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("An invalid result needs a reason");
        }
        this.valid = valid;
        this.reason = reason;
    }

    public boolean isValid() {
        return valid;
    }

    /** The reason the command is invalid, or {@code null} if it is valid. */
    public String getReason() {
        return reason;
    }
}
