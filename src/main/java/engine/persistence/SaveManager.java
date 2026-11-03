package engine.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stores saved games as JSON files, one per slot, in a save directory (F10, SD08).
 *
 * <p>Pattern role: <b>Caretaker</b> of the Memento pattern: it writes a snapshot's
 * {@link GameSnapshot#toJson()} and reads it back with {@link GameSnapshot#fromJson}, and never looks
 * inside the game state.
 *
 * <p>A slot name is 1 to {@value #MAX_SLOT_LENGTH} letters, digits, hyphens and underscores; it is
 * stored in lower case as {@code <slot>.json}, so no name can leave the save directory and two names
 * that differ only in case are the same slot on every system. A save is first written to a temporary
 * file in the same directory and then moved over the old file, so a failed write never destroys an
 * existing save.
 */
public class SaveManager {

    /** Where saves go by default, relative to the working directory. */
    public static final Path DEFAULT_DIRECTORY = Path.of("saves");
    /** Longest slot name. */
    public static final int MAX_SLOT_LENGTH = 32;
    static final String EXTENSION = ".json";
    private static final Pattern SLOT = Pattern.compile("[A-Za-z0-9_-]{1," + MAX_SLOT_LENGTH + "}");
    static final String INVALID_SLOT = "A save name has 1 to " + MAX_SLOT_LENGTH
            + " letters, digits, hyphens (-) or underscores (_).";

    private final Path saveDirectory;

    /** A save manager using {@link #DEFAULT_DIRECTORY}. */
    public SaveManager() {
        this(DEFAULT_DIRECTORY);
    }

    public SaveManager(Path saveDirectory) {
        this.saveDirectory = Objects.requireNonNull(saveDirectory, "saveDirectory");
    }

    /** Whether {@code slot} is a valid slot name. */
    public static boolean isValidSlot(String slot) {
        return slot != null && SLOT.matcher(slot).matches();
    }

    /**
     * Writes the snapshot to the slot, replacing a save already there (the view asks first). The
     * directory is created if needed.
     *
     * @throws IllegalArgumentException if the slot name is not valid
     * @throws IOException              if the file cannot be written; an existing save is left as it was
     */
    public void save(String slot, GameSnapshot snapshot) throws IOException {
        Path target = pathOf(slot);
        String json = Objects.requireNonNull(snapshot, "snapshot").toJson();
        Path temporary = null;
        try {
            Files.createDirectories(saveDirectory);
            temporary = Files.createTempFile(saveDirectory, target.getFileName().toString(), ".tmp");
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException writeFailed) {
            throw new IOException("the file could not be written (" + describe(writeFailed) + ")", writeFailed);
        } finally {
            if (temporary != null) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    /**
     * Reads the snapshot in the slot. Whether this game can restore it is for the caller to check
     * ({@link GameSnapshot#isCompatible()}).
     *
     * @throws IllegalArgumentException if the slot name is not valid
     * @throws IOException              if there is no such save, it cannot be read, or it is damaged
     */
    public GameSnapshot load(String slot) throws IOException {
        Path path = pathOf(slot);
        String json;
        try {
            json = Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException missing) {
            throw new IOException("There is no save named \"" + normalize(slot) + "\".", missing);
        } catch (IOException unreadable) {
            throw new IOException("The save file could not be read (" + describe(unreadable) + ").", unreadable);
        }
        return GameSnapshot.fromJson(json);
    }

    /**
     * One entry per readable save file, newest first (then by name). Files that are damaged or not
     * named like a slot are left out; no directory means no saves.
     */
    public List<SaveSlotInfo> listSlots() {
        List<SaveSlotInfo> slots = new ArrayList<>();
        if (!Files.isDirectory(saveDirectory)) {
            return slots;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(saveDirectory, "*" + EXTENSION)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String slot = name.substring(0, name.length() - EXTENSION.length());
                if (!isValidSlot(slot) || !slot.equals(normalize(slot))) {
                    continue;
                }
                try {
                    slots.add(GameSnapshot.fromJson(Files.readString(file, StandardCharsets.UTF_8)).toSlotInfo(slot));
                } catch (IOException unreadable) {
                    // a damaged file is not offered for loading
                }
            }
        } catch (IOException unreadableDirectory) {
            return List.of();
        }
        slots.sort(Comparator.comparing(SaveSlotInfo::getSavedAt).reversed().thenComparing(SaveSlotInfo::getSlot));
        return slots;
    }

    public Path getSaveDirectory() {
        return saveDirectory;
    }

    /** A short reason for a file error, e.g. "access denied: saves/a.json". */
    private static String describe(IOException failure) {
        String kind = failure instanceof AccessDeniedException ? "access denied"
                : failure instanceof FileSystemException fileSystem && fileSystem.getReason() != null
                ? fileSystem.getReason() : failure.getClass().getSimpleName();
        return failure instanceof FileSystemException fileSystem && fileSystem.getFile() != null
                ? kind + ": " + fileSystem.getFile() : kind;
    }

    /** The slot name as stored: in lower case. */
    static String normalize(String slot) {
        return slot.toLowerCase(Locale.ROOT);
    }

    private Path pathOf(String slot) {
        if (!isValidSlot(slot)) {
            throw new IllegalArgumentException(INVALID_SLOT);
        }
        return saveDirectory.resolve(normalize(slot) + EXTENSION);
    }
}
