package engine.items;

import java.util.Locale;
import java.util.Objects;

/**
 * Anything the player can pick up, carry or store.
 *
 * <p>Pattern roles: <b>Component</b> of the Composite pattern (with {@link Container} as the
 * Composite and {@link Weapon}, {@link Consumable} and {@link KeyItem} as Leaves), and
 * <b>Product</b> of the Factory Method pattern (created by {@code CharacterFactory}).
 */
public abstract class Item {

    protected String name;
    protected double weight;
    /** Id of the NPC that owns this item, or {@code null} if it belongs to nobody. */
    protected String ownerId;

    protected Item(String name, double weight) {
        this(name, weight, null);
    }

    protected Item(String name, double weight, String ownerId) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Item name must not be blank");
        }
        if (!Double.isFinite(weight) || weight < 0) {
            throw new IllegalArgumentException("Item weight must be a non-negative number: " + weight);
        }
        this.name = name;
        this.weight = weight;
        this.ownerId = ownerId;
    }

    public String getName() {
        return name;
    }

    public String getOwnerId() {
        return ownerId;
    }

    /** The weight in kg; for a {@link Container} this includes everything inside it. */
    public abstract double getWeight();

    public String describe() {
        return String.format(Locale.ROOT, "%s (%.1f kg)", name, getWeight());
    }
}
