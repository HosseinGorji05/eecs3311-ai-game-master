package engine.world;

import engine.items.KeyItem;

import java.util.Objects;

/**
 * A one-way connection from a location to its neighbour in one {@link Direction}.
 *
 * <p>An exit created with a required key name starts locked; {@link #unlock(KeyItem)} opens it for
 * good when given a key whose name matches (ignoring case).
 */
public class Exit {

    private final String id;
    private final Direction direction;
    private final Location destination;
    private boolean locked;
    private final String requiredKeyName;

    /** Creates an exit that is always open. */
    public Exit(String id, Direction direction, Location destination) {
        this(id, direction, destination, null);
    }

    /**
     * Creates an exit that is locked until a key named {@code requiredKeyName} is used on it, or an
     * open exit if {@code requiredKeyName} is {@code null}.
     */
    public Exit(String id, Direction direction, Location destination, String requiredKeyName) {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Exit id must not be blank");
        }
        if (requiredKeyName != null && requiredKeyName.isBlank()) {
            throw new IllegalArgumentException("Required key name must not be blank");
        }
        this.id = id;
        this.direction = Objects.requireNonNull(direction, "direction");
        this.destination = Objects.requireNonNull(destination, "destination");
        this.requiredKeyName = requiredKeyName;
        this.locked = requiredKeyName != null;
    }

    public String getId() {
        return id;
    }

    public Direction getDirection() {
        return direction;
    }

    public Location getDestination() {
        return destination;
    }

    /** The name of the key that opens this exit, or {@code null} if it never needed one. */
    public String getRequiredKeyName() {
        return requiredKeyName;
    }

    public boolean isLocked() {
        return locked;
    }

    /**
     * Unlocks this exit if {@code key} is the required key.
     *
     * @return {@code true} if the exit is now open (also when it was not locked), {@code false} if
     *         the key is {@code null} or does not fit
     */
    public boolean unlock(KeyItem key) {
        if (!locked) {
            return true;
        }
        if (key == null || !key.getName().equalsIgnoreCase(requiredKeyName)) {
            return false;
        }
        locked = false;
        return true;
    }
}
