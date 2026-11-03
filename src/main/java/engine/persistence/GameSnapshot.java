package engine.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * A saved game: the full game state as JSON, with a format version, the time it was made and the
 * character's name and location for the load menu (F10, SD08).
 *
 * <p>Pattern role: <b>Memento</b> of the Memento pattern. Only {@code GameState} (the Originator)
 * writes and reads {@link #getStateJson()}; {@code SaveManager} (the Caretaker) only stores
 * {@link #toJson()} and reads it back with {@link #fromJson}. A file looks like this:
 * <pre>
 * {"version":1,"createdAt":"2026-11-03T14:05:00Z","characterName":"Ari","locationName":"Tavern",
 *  "state":{...}}
 * </pre>
 * {@link #isCompatible()} tells whether this game can read the state (same {@link #CURRENT_VERSION}).
 */
public class GameSnapshot {

    /** The format version this game writes and reads. */
    public static final int CURRENT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final int version;
    private final Instant createdAt;
    private final String characterName;
    private final String locationName;
    private final String stateJson;

    /**
     * A snapshot of the current version, made now.
     *
     * @throws IllegalArgumentException if {@code stateJson} is not a JSON object
     */
    public GameSnapshot(String stateJson, String characterName, String locationName) {
        this(CURRENT_VERSION, Instant.now(), characterName, locationName, requireObject(stateJson));
    }

    private GameSnapshot(int version, Instant createdAt, String characterName, String locationName, String stateJson) {
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.characterName = Objects.requireNonNull(characterName, "characterName");
        this.locationName = Objects.requireNonNull(locationName, "locationName");
        this.stateJson = stateJson;
    }

    /**
     * Reads a snapshot written by {@link #toJson()}. The state is not checked here (that is the
     * Originator's job); a snapshot of another version is returned so {@link #isCompatible()} can
     * report it.
     *
     * @throws IOException if the text is not JSON or misses the version, time, names or state
     */
    public static GameSnapshot fromJson(String json) throws IOException {
        try {
            JsonElement root = JsonParser.parseString(Objects.requireNonNull(json, "json"));
            if (!root.isJsonObject()) {
                throw new IOException("The save file is damaged or not a saved game.");
            }
            JsonObject object = root.getAsJsonObject();
            JsonElement state = object.get("state");
            if (state == null || !state.isJsonObject()) {
                throw new IOException("The save file has no game state.");
            }
            return new GameSnapshot(required(object, "version").getAsInt(),
                    Instant.parse(required(object, "createdAt").getAsString()),
                    required(object, "characterName").getAsString(),
                    required(object, "locationName").getAsString(),
                    state.toString());
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException | NumberFormatException
                 | DateTimeParseException damaged) {
            throw new IOException("The save file is damaged or not a saved game.", damaged);
        }
    }

    /** The whole snapshot as readable JSON, the state included as an object. */
    public String toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("version", version);
        object.addProperty("createdAt", createdAt.toString());
        object.addProperty("characterName", characterName);
        object.addProperty("locationName", locationName);
        object.add("state", JsonParser.parseString(stateJson));
        return GSON.toJson(object) + "\n";
    }

    /** Whether this game can restore the snapshot: it was written in {@link #CURRENT_VERSION}. */
    public boolean isCompatible() {
        return version == CURRENT_VERSION;
    }

    /** What the load menu shows for this snapshot stored under {@code slot}. */
    public SaveSlotInfo toSlotInfo(String slot) {
        return new SaveSlotInfo(slot, characterName, locationName, createdAt);
    }

    public int getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCharacterName() {
        return characterName;
    }

    public String getLocationName() {
        return locationName;
    }

    /** The game state as JSON, for the Originator. */
    public String getStateJson() {
        return stateJson;
    }

    private static JsonElement required(JsonObject object, String field) throws IOException {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new IOException("The save file has no " + field + ".");
        }
        return value;
    }

    private static String requireObject(String stateJson) {
        try {
            if (JsonParser.parseString(Objects.requireNonNull(stateJson, "stateJson")).isJsonObject()) {
                return stateJson;
            }
        } catch (JsonParseException notJson) {
            throw new IllegalArgumentException("The state is not JSON", notJson);
        }
        throw new IllegalArgumentException("The state is not a JSON object");
    }
}
