package engine.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameSnapshotTest {

    private static final String STATE = "{\"turn\":3,\"note\":\"a \\\"quoted\\\" <text> & more\"}";

    @Test
    void aNewSnapshotHasTheCurrentVersionAndIsCompatible() {
        Instant before = Instant.now();
        GameSnapshot snapshot = new GameSnapshot(STATE, "Ari", "Tavern");

        assertEquals(GameSnapshot.CURRENT_VERSION, snapshot.getVersion());
        assertEquals(2, GameSnapshot.CURRENT_VERSION);
        assertTrue(snapshot.isCompatible());
        assertFalse(snapshot.getCreatedAt().isBefore(before));
        assertEquals("Ari", snapshot.getCharacterName());
        assertEquals("Tavern", snapshot.getLocationName());
        assertEquals(STATE, snapshot.getStateJson());
    }

    @Test
    void toJsonAndFromJsonGiveTheSameSnapshotBack() throws IOException {
        GameSnapshot snapshot = new GameSnapshot(STATE, "Ari", "Tavern");

        GameSnapshot read = GameSnapshot.fromJson(snapshot.toJson());

        assertEquals(snapshot.getVersion(), read.getVersion());
        assertEquals(snapshot.getCreatedAt(), read.getCreatedAt());
        assertEquals("Ari", read.getCharacterName());
        assertEquals("Tavern", read.getLocationName());
        assertEquals(STATE, read.getStateJson());
        assertEquals(snapshot.toJson(), read.toJson());
        assertTrue(snapshot.toJson().contains("<text> & more"), "readable, no HTML escaping");
    }

    @Test
    void aSnapshotOfAnotherVersionIsReadButNotCompatible() throws IOException {
        String json = new GameSnapshot(STATE, "Ari", "Tavern").toJson().replace("\"version\": 2", "\"version\": 7");

        GameSnapshot read = GameSnapshot.fromJson(json);

        assertEquals(7, read.getVersion());
        assertFalse(read.isCompatible());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "{\"version\":1", "[1,2]", "null", "42",
            "{\"version\":1,\"createdAt\":\"2026-11-03T10:00:00Z\",\"characterName\":\"Ari\",\"locationName\":\"Tavern\"}",
            "{\"version\":1,\"createdAt\":\"yesterday\",\"characterName\":\"Ari\",\"locationName\":\"Tavern\",\"state\":{}}",
            "{\"version\":\"one\",\"createdAt\":\"2026-11-03T10:00:00Z\",\"characterName\":\"Ari\",\"locationName\":\"Tavern\",\"state\":{}}",
            "{\"createdAt\":\"2026-11-03T10:00:00Z\",\"characterName\":\"Ari\",\"locationName\":\"Tavern\",\"state\":{}}",
            "{\"version\":1,\"createdAt\":\"2026-11-03T10:00:00Z\",\"locationName\":\"Tavern\",\"state\":{}}",
            "{\"version\":1,\"createdAt\":\"2026-11-03T10:00:00Z\",\"characterName\":\"Ari\",\"locationName\":\"Tavern\",\"state\":[]}",
            "{\"version\":1,\"createdAt\":\"2026-11-03T10:00:00Z\",\"characterName\":\"Ari\",\"locationName\":\"Tavern\",\"state\":{}} trailing"})
    void anythingElseIsReportedAsAnIoException(String json) {
        assertThrows(IOException.class, () -> GameSnapshot.fromJson(json));
    }

    @Test
    void theStateMustBeAJsonObject() {
        assertThrows(IllegalArgumentException.class, () -> new GameSnapshot("[1]", "Ari", "Tavern"));
        assertThrows(IllegalArgumentException.class, () -> new GameSnapshot("{broken", "Ari", "Tavern"));
    }

    @Test
    void theSlotInfoNamesTheSlotCharacterLocationAndTime() {
        GameSnapshot snapshot = new GameSnapshot(STATE, "Ari", "Tavern");

        SaveSlotInfo info = snapshot.toSlotInfo("mygame");

        assertEquals("mygame", info.getSlot());
        assertEquals("Ari", info.getCharacterName());
        assertEquals("Tavern", info.getLocationName());
        assertEquals(snapshot.getCreatedAt(), info.getSavedAt());
    }
}
