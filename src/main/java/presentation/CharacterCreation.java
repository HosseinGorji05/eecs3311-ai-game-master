package presentation;

import java.util.Objects;

/**
 * The New Game screen's backstory step (F01, SD01), without a display: Generate Backstory creates the
 * character and shows its backstory, Regenerate asks for another, Start begins the game with the one shown.
 *
 * <p>The form has a version that every edit of the name, class or stats raises. An edit discards the shown
 * backstory (it belonged to the character as it was), and a backstory that arrives for an older version (the
 * player edited the form while it was being written) is ignored, so Start always begins with the character
 * the form shows. Used on the Swing thread only.
 */
final class CharacterCreation {

    private int version;
    private String backstory;

    /** The name, class or stats changed: the shown backstory no longer fits. */
    void formChanged() {
        version++;
        backstory = null;
    }

    /** The form version a backstory request is made for. */
    int version() {
        return version;
    }

    /**
     * A backstory arrived for the form as it was at {@code requestedVersion}.
     *
     * @return whether it is shown (the form has not changed since)
     */
    boolean backstoryWritten(int requestedVersion, String text) {
        Objects.requireNonNull(text, "text");
        if (requestedVersion != version) {
            return false;
        }
        backstory = text;
        return true;
    }

    /** The shown backstory, or {@code null} if none is shown. */
    String getBackstory() {
        return backstory;
    }

    boolean hasBackstory() {
        return backstory != null;
    }

    /** Generate Backstory: the form is valid and no controller call is pending. */
    boolean canGenerate(StatAllocation allocation, boolean busy) {
        return !busy && allocation.isValid();
    }

    /** Regenerate and Start: a backstory for the current form is shown and no controller call is pending. */
    boolean canRegenerateOrStart(boolean busy) {
        return !busy && backstory != null;
    }
}
