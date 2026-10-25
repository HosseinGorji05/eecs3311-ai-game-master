package engine.items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An item that holds other items, such as a backpack or a pouch. Containers can be nested.
 *
 * <p>Pattern role: <b>Composite</b> of the Composite pattern. {@link #getWeight()} returns the
 * container's own weight plus {@code getWeight()} of everything inside it, at any depth.
 *
 * <p>Constraints: {@code capacity} is the number of items the container can hold directly, and a
 * container can never contain itself, directly or through a nested container.
 */
public class Container extends Item {

    private final int capacity;
    private final List<Item> contents = new ArrayList<>();

    public Container(String name, double weight, int capacity) {
        this(name, weight, capacity, null);
    }

    public Container(String name, double weight, int capacity, String ownerId) {
        super(name, weight, ownerId);
        if (capacity < 0) {
            throw new IllegalArgumentException("Container capacity must not be negative: " + capacity);
        }
        this.capacity = capacity;
    }

    /**
     * Puts an item directly into this container.
     *
     * @return {@code false} if the item is {@code null}, is already in this container, would make
     *         the container contain itself, or the container is full
     */
    public boolean add(Item item) {
        if (item == null || contents.contains(item) || contents.size() >= capacity) {
            return false;
        }
        if (item == this || (item instanceof Container other && other.containsDeep(this))) {
            return false;
        }
        return contents.add(item);
    }

    /** Removes an item held directly by this container. */
    public boolean remove(Item item) {
        return contents.remove(item);
    }

    /** The items held directly by this container, as a read-only view. */
    public List<Item> getContents() {
        return Collections.unmodifiableList(contents);
    }

    @Override
    public double getWeight() {
        double total = weight;
        for (Item item : contents) {
            total += item.getWeight();
        }
        return total;
    }

    private boolean containsDeep(Item target) {
        for (Item item : contents) {
            if (item == target || (item instanceof Container c && c.containsDeep(target))) {
                return true;
            }
        }
        return false;
    }
}
