package engine.commands;

import engine.core.GameState;
import engine.core.RulesEngine;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import engine.items.Item;
import engine.items.KeyItem;
import engine.world.Direction;
import engine.world.Exit;
import engine.world.Location;

import java.util.Locale;
import java.util.Objects;

/**
 * Moves the player through one exit of the current location, given either a direction ("go
 * east") or the name of an adjacent location ("Tool Shed").
 *
 * <p>Pattern role: <b>ConcreteCommand</b>. {@link #validate} resolves the exit and, for a locked
 * exit, the key the player carries, and keeps both for {@link #execute} (SD11).
 *
 * <p>A successful move adds a MOVEMENT event (verb MOVED, subject = the destination's id) to its
 * result, which {@code RulesEngine} publishes.
 */
public class MoveCommand implements GameCommand {

    private final Direction direction;
    private final String destinationName;
    private Exit exit;
    private KeyItem key;

    /** Moves through the exit in this direction. */
    public MoveCommand(Direction direction) {
        this.direction = Objects.requireNonNull(direction, "direction");
        this.destinationName = null;
    }

    /** Moves to the adjacent location with this name (ignoring case). */
    public MoveCommand(String destinationName) {
        Objects.requireNonNull(destinationName, "destinationName");
        if (destinationName.isBlank()) {
            throw new IllegalArgumentException("Destination name must not be blank");
        }
        this.direction = null;
        this.destinationName = destinationName.trim();
    }

    @Override
    public ActionType getType() {
        return ActionType.MOVE;
    }

    /**
     * Invalid if there is no exit that way or the destination is not adjacent (the exits are
     * listed), or if the exit is locked and the player does not carry its key.
     */
    @Override
    public ValidationResult validate(GameState state) {
        exit = null;
        key = null;
        Location location = state.currentLocation();
        Exit door = destinationName != null ? location.findExitTo(destinationName) : location.getExit(direction);
        if (door == null) {
            String problem = destinationName != null
                    ? "You can't go straight to " + destinationName + " from here; travel step by step."
                    : "There is no exit " + direction.name().toLowerCase(Locale.ROOT) + ".";
            return new ValidationResult(false, problem + " Exits: " + location.describeExits() + ".");
        }
        KeyItem carriedKey = null;
        if (door.isLocked()) {
            String keyName = door.getRequiredKeyName();
            Item item = state.getPlayer().getInventory().find(keyName);
            if (!(item instanceof KeyItem k)) {
                return new ValidationResult(false, "The way to " + door.getDestination().getName()
                        + " is locked. You need the " + keyName + ".");
            }
            carriedKey = k;
        }
        exit = door;
        key = carriedKey;
        return new ValidationResult(true, null);
    }

    @Override
    public ActionResult execute(GameState state, RulesEngine rules) {
        if (exit == null) {
            throw new IllegalStateException("validate() must succeed before execute()");
        }
        String unlocked = "";
        if (exit.isLocked()) {
            if (!exit.unlock(key)) {
                throw new IllegalStateException("The validated key does not open " + exit.getId());
            }
            unlocked = "You unlock the way with the " + key.getName() + ".\n";
        }
        Location destination = exit.getDestination();
        state.setCurrentLocation(destination);
        destination.markVisited();
        ActionResult result = new ActionResult(true, unlocked + destination.describe());
        result.addEvent(new GameEvent(EventType.MOVEMENT, state.getTurn(), "moved to " + destination.getName(),
                destination.getId(), 0, EventVerb.MOVED));
        return result;
    }
}
