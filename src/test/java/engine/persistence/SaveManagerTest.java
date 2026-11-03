package engine.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SaveManagerTest {

    @TempDir
    Path root;

    private Path directory() {
        return root.resolve("saves");
    }

    private static GameSnapshot snapshot(String character, String location) {
        return new GameSnapshot("{\"turn\":1}", character, location);
    }

    /** A save file written by hand, with a chosen time. */
    private void writeFile(String fileName, String character, String location, String savedAt) throws IOException {
        Files.createDirectories(directory());
        Files.writeString(directory().resolve(fileName), "{\"version\":1,\"createdAt\":\"" + savedAt
                + "\",\"characterName\":\"" + character + "\",\"locationName\":\"" + location + "\",\"state\":{}}");
    }

    @Test
    void theDefaultDirectoryIsSaves() {
        assertEquals(Path.of("saves"), new SaveManager().getSaveDirectory());
    }

    @Test
    void aSavedSnapshotIsLoadedBackAndTheDirectoryIsCreated() throws IOException {
        SaveManager saves = new SaveManager(directory());
        GameSnapshot snapshot = snapshot("Ari", "Tavern");

        saves.save("first-game_1", snapshot);
        GameSnapshot loaded = saves.load("first-game_1");

        assertTrue(Files.isRegularFile(directory().resolve("first-game_1.json")));
        assertEquals(snapshot.toJson(), loaded.toJson());
        try (var files = Files.list(directory())) {
            assertEquals(1, files.count(), "no temporary file is left behind");
        }
    }

    @Test
    void savingAgainReplacesTheSlotAndNamesIgnoreCase() throws IOException {
        SaveManager saves = new SaveManager(directory());
        saves.save("Slot", snapshot("Ari", "Tavern"));

        saves.save("SLOT", snapshot("Ari", "Old Mill"));

        assertEquals("Old Mill", saves.load("slot").getLocationName());
        assertEquals(List.of("slot"), saves.listSlots().stream().map(SaveSlotInfo::getSlot).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "../x", "..", ".", "a/b", "a\\b", "a.b", "a b", "save:1", "ümlaut",
            "abcdefghijklmnopqrstuvwxyz1234567"})
    void invalidSlotNamesAreRefusedForSavingAndLoading(String slot) throws IOException {
        SaveManager saves = new SaveManager(directory());

        assertFalse(SaveManager.isValidSlot(slot));
        assertThrows(IllegalArgumentException.class, () -> saves.save(slot, snapshot("Ari", "Tavern")));
        assertThrows(IllegalArgumentException.class, () -> saves.load(slot));
        assertFalse(Files.exists(root.resolve("x.json")));
        assertFalse(Files.exists(directory()), "nothing was written");
    }

    @Test
    void validSlotNamesUseLettersDigitsHyphensAndUnderscores() {
        assertTrue(SaveManager.isValidSlot("a"));
        assertTrue(SaveManager.isValidSlot("My_Game-2"));
        assertTrue(SaveManager.isValidSlot("x".repeat(SaveManager.MAX_SLOT_LENGTH)));
        assertFalse(SaveManager.isValidSlot(null));
    }

    @Test
    void aMissingSaveIsAClearIoException() {
        SaveManager saves = new SaveManager(directory());

        IOException missing = assertThrows(IOException.class, () -> saves.load("Nothing"));

        assertEquals("There is no save named \"nothing\".", missing.getMessage());
    }

    @Test
    void aDamagedFileIsAnIoException() throws IOException {
        Files.createDirectories(directory());
        Files.writeString(directory().resolve("broken.json"), "{\"version\":1,\"createdAt\":");

        IOException damaged = assertThrows(IOException.class, () -> new SaveManager(directory()).load("broken"));

        assertEquals("The save file is damaged or not a saved game.", damaged.getMessage());
    }

    @Test
    void theSlotsAreListedNewestFirstAndDamagedOrForeignFilesAreLeftOut() throws IOException {
        writeFile("old.json", "Ari", "Village Square", "2026-11-01T09:00:00Z");
        writeFile("newest.json", "Vex", "Wolf Den", "2026-11-03T18:30:00Z");
        writeFile("middle.json", "Ari", "Tavern", "2026-11-02T12:00:00Z");
        writeFile("Upper.json", "Ari", "Tavern", "2026-11-04T12:00:00Z");   // not a stored slot name
        writeFile("not.a.slot.json", "Ari", "Tavern", "2026-11-04T12:00:00Z");
        Files.writeString(directory().resolve("broken.json"), "{oops");
        Files.writeString(directory().resolve("notes.txt"), "hello");

        List<SaveSlotInfo> slots = new SaveManager(directory()).listSlots();

        assertEquals(List.of("newest", "middle", "old"), slots.stream().map(SaveSlotInfo::getSlot).toList());
        assertEquals("Vex", slots.get(0).getCharacterName());
        assertEquals("Wolf Den", slots.get(0).getLocationName());
        assertEquals("2026-11-03T18:30:00Z", slots.get(0).getSavedAt().toString());
        assertEquals("Tavern", slots.get(1).getLocationName());
    }

    @Test
    void withoutADirectoryThereAreNoSaves() {
        assertTrue(new SaveManager(directory()).listSlots().isEmpty());
    }

    @Test
    void aSaveDirectoryThatIsAFileFailsWithAClearMessage() throws IOException {
        Path file = root.resolve("file");
        Files.writeString(file, "not a directory");

        IOException failed = assertThrows(IOException.class, () -> new SaveManager(file).save("game", snapshot("Ari", "Tavern")));

        assertTrue(failed.getMessage().startsWith("the file could not be written ("), failed.getMessage());
        assertEquals("not a directory", Files.readString(file));
    }

    @Test
    void aFailedWriteKeepsTheExistingSave() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "needs POSIX permissions");
        assumeTrue(!"root".equals(System.getProperty("user.name")), "root may write anywhere");
        SaveManager saves = new SaveManager(directory());
        saves.save("game", snapshot("Ari", "Tavern"));
        String before = Files.readString(directory().resolve("game.json"), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(directory(), PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            IOException failed = assertThrows(IOException.class, () -> saves.save("game", snapshot("Ari", "Wolf Den")));

            assertTrue(failed.getMessage().contains("access denied"), failed.getMessage());
            assertEquals(before, Files.readString(directory().resolve("game.json"), StandardCharsets.UTF_8));
            assertEquals("Tavern", saves.load("game").getLocationName(), "the old save still loads");
        } finally {
            Files.setPosixFilePermissions(directory(), PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }
}
