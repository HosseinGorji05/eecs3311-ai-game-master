package engine.core;

import engine.character.Player;
import engine.commands.ActionType;
import engine.events.EventLog;
import engine.persistence.GameSnapshot;
import engine.quests.QuestLog;
import engine.world.Location;
import engine.world.World;

import java.util.Objects;

/**
 * The single source of truth of a running game: the player, the world, the current location,
 * the current mode, the turn number, the quest log, the event log and the story memory.
 *
 * <p>Pattern roles: <b>Context</b> of the State pattern ({@link #allows} and {@link #canSave}
 * delegate to the current {@link GameMode}) and <b>Originator</b> of the Memento pattern
 * ({@link #createSnapshot} and {@link #restore}, SD08). Only this class and the objects it owns read
 * and write the snapshot's state.
 *
 * <p>The state owns its {@link EventLog}, {@link QuestLog} and {@link StoryMemory}; whoever owns
 * the {@code GameEventBus} subscribes the event log (and a {@code QuestTracker} and a
 * {@code MemoryManager}, which fill the quest log and the story memory) to it.
 *
 * <p>{@code isGameOver()} of the design is added together with the ending.
 */
public class GameState {

    private int turn;
    private Player player;
    private World world;
    private Location currentLocation;
    private GameMode mode;
    private final QuestLog questLog = new QuestLog();
    private final EventLog eventLog = new EventLog();
    private final StoryMemory storyMemory = new StoryMemory();

    /**
     * Starts a new game: the player is placed in the world's start location (marked visited),
     * in exploration mode, on turn 1.
     */
    public void start(Player player, World world) {
        this.player = Objects.requireNonNull(player, "player");
        this.world = Objects.requireNonNull(world, "world");
        this.currentLocation = world.getStartLocation();
        this.currentLocation.markVisited();
        this.mode = new ExplorationMode();
        this.turn = 1;
    }

    /** The current turn number: 1 during the first turn, 0 before {@link #start}. */
    public int getTurn() {
        return turn;
    }

    public Player getPlayer() {
        return player;
    }

    public World getWorld() {
        return world;
    }

    public Location currentLocation() {
        return currentLocation;
    }

    public void setCurrentLocation(Location location) {
        this.currentLocation = Objects.requireNonNull(location, "location");
    }

    public GameMode getMode() {
        return mode;
    }

    public void setMode(GameMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    /** Whether the current mode allows this action type. */
    public boolean allows(ActionType type) {
        return requireMode().allows(type);
    }

    /** Whether the current mode allows saving. */
    public boolean canSave() {
        return requireMode().canSave();
    }

    public QuestLog getQuestLog() {
        return questLog;
    }

    public EventLog getEventLog() {
        return eventLog;
    }

    public StoryMemory getStoryMemory() {
        return storyMemory;
    }

    public void nextTurn() {
        turn++;
    }

    /**
     * A snapshot of the full state (see {@code StateJson} for what it holds), named after the player
     * and the current location.
     *
     * @throws IllegalStateException if the game has not started or the current mode does not allow saving
     */
    public GameSnapshot createSnapshot() {
        if (!canSave()) {
            throw new IllegalStateException("Saving is not allowed right now");
        }
        return new GameSnapshot(StateJson.write(this), player.getName(), currentLocation.getName());
    }

    /**
     * Replaces the game with the snapshot's. The whole snapshot is read and checked first; only then
     * are the player, the world, the location, the mode and the turn replaced and the existing quest
     * log, event log and story memory refilled, so everything that holds this state, the event log or
     * the memory keeps working. No event is published.
     *
     * @throws IllegalArgumentException if the snapshot is from another version or its state is damaged;
     *                                  the game is then left as it was
     */
    public void restore(GameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.isCompatible()) {
            throw new IllegalArgumentException("The save was made by another version of the game (format "
                    + snapshot.getVersion() + "; this game reads format " + GameSnapshot.CURRENT_VERSION + ").");
        }
        StateJson.Restored saved = StateJson.read(snapshot.getStateJson());
        turn = saved.turn();
        player = saved.player();
        world = saved.world();
        currentLocation = saved.currentLocation();
        mode = saved.mode();
        questLog.restore(saved.quests());
        eventLog.restore(saved.events());
        storyMemory.restore(saved.facts(), saved.summaries(), saved.recent());
    }

    private GameMode requireMode() {
        if (mode == null) {
            throw new IllegalStateException("The game has not started");
        }
        return mode;
    }
}
