package engine.persistence;

import java.time.Instant;
import java.util.Objects;

/** What the load menu shows about one save slot: its name, the character, where they are and when it was saved. */
public class SaveSlotInfo {

    private final String slot;
    private final String characterName;
    private final String locationName;
    private final Instant savedAt;

    public SaveSlotInfo(String slot, String characterName, String locationName, Instant savedAt) {
        this.slot = Objects.requireNonNull(slot, "slot");
        this.characterName = Objects.requireNonNull(characterName, "characterName");
        this.locationName = Objects.requireNonNull(locationName, "locationName");
        this.savedAt = Objects.requireNonNull(savedAt, "savedAt");
    }

    public String getSlot() {
        return slot;
    }

    public String getCharacterName() {
        return characterName;
    }

    public String getLocationName() {
        return locationName;
    }

    public Instant getSavedAt() {
        return savedAt;
    }
}
