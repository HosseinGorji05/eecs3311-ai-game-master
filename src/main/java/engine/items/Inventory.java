package engine.items;

import java.util.List;

/**
 * The items a player carries, with a total weight limit counted through nested containers.
 *
 * <p>Pattern role: <b>Client</b> of the Composite pattern. Its root is a {@link Container}, so
 * {@link #totalWeight()} is a single {@code getWeight()} call on the item tree.
 *
 * <p>Invariant: {@code totalWeight() <= maxWeight}.
 */
public class Inventory {

    /** Default carrying limit in kg (Stage 1 design decision "Carrying limit"). */
    public static final double MAX_CARRY_WEIGHT = 10.0;

    /** Tolerance for floating-point sums of item weights, so exactly the limit is allowed. */
    private static final double WEIGHT_EPSILON = 1e-9;

    private final double maxWeight;
    private final Container root;

    public Inventory() {
        this(MAX_CARRY_WEIGHT);
    }

    public Inventory(double maxWeight) {
        if (!Double.isFinite(maxWeight) || maxWeight < 0) {
            throw new IllegalArgumentException("Maximum weight must be a non-negative number: " + maxWeight);
        }
        this.maxWeight = maxWeight;
        this.root = new Container("inventory", 0.0, Integer.MAX_VALUE);
    }

    /**
     * Adds an item to the top level of the inventory.
     *
     * @return {@code false} if the item cannot be carried or cannot be added to the root
     */
    public boolean add(Item item) {
        return canCarry(item) && root.add(item);
    }

    /**
     * Removes the first item with this name (ignoring case), searching nested containers too.
     *
     * @return the removed item, or {@code null} if there is no such item
     */
    public Item remove(String name) {
        return name == null ? null : removeFrom(root, name);
    }

    /**
     * Finds the first item with this name (ignoring case), searching nested containers too.
     *
     * @return the item, or {@code null} if there is no such item
     */
    public Item find(String name) {
        return name == null ? null : findIn(root, name);
    }

    /** Whether adding this item would keep the total weight within {@code maxWeight}. */
    public boolean canCarry(Item item) {
        return item != null && totalWeight() + item.getWeight() <= maxWeight + WEIGHT_EPSILON;
    }

    public double totalWeight() {
        return root.getWeight();
    }

    private static Item findIn(Container container, String name) {
        for (Item item : container.getContents()) {
            if (item.getName().equalsIgnoreCase(name)) {
                return item;
            }
            if (item instanceof Container nested) {
                Item found = findIn(nested, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Item removeFrom(Container container, String name) {
        List<Item> contents = container.getContents();
        for (Item item : contents) {
            if (item.getName().equalsIgnoreCase(name)) {
                container.remove(item);
                return item;
            }
            if (item instanceof Container nested) {
                Item removed = removeFrom(nested, name);
                if (removed != null) {
                    return removed;
                }
            }
        }
        return null;
    }
}
