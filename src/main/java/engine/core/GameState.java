package engine.core;

import engine.character.Player;
import engine.commands.ActionType;
import engine.events.EventLog;
import engine.quests.QuestLog;
import engine.world.Location;
import engine.world.World;

import java.util.Objects;

/**
 * The single source of truth of a running game: the player, the world, the current location,
 * the current mode, the turn number, the quest log, the event log and the story memory.
 *
 * <p>Pattern role: <b>Context</b> of the State pattern; {@link #allows} and {@link #canSave}
 * delegate to the current {@link GameMode}.
 *
 * <p>The state owns its {@link EventLog}, {@link QuestLog} and {@link StoryMemory}; whoever owns
 * the {@code GameEventBus} subscribes the event log (and a {@code QuestTracker} and a
 * {@code MemoryManager}, which fill the quest log and the story memory) to it.
 *
 * <p>{@code isGameOver()} and the Memento operations {@code createSnapshot()} / {@code restore()}
 * of the design are added together with the ending and saving.
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

    private GameMode requireMode() {
        if (mode == null) {
            throw new IllegalStateException("The game has not started");
        }
        return mode;
    }
}
