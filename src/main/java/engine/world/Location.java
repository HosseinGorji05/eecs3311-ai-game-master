package engine.world;

import engine.character.Enemy;
import engine.items.Item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A place in the world, with its exits and the items lying in it.
 *
 * <p>Constraint: at most one exit per {@link Direction}, so a location has 0..4 exits.
 *
 * <p>The NPCs of the design ({@code npcs}, {@code findNpc()}) are added together with dialogue.
 */
public class Location {

    private final String id;
    private final String name;
    private final String description;
    private boolean visited;
    private final List<Exit> exits = new ArrayList<>();
    private final List<Item> items = new ArrayList<>();
    private final List<Enemy> enemies = new ArrayList<>();

    public Location(String id, String name, String description) {
        this.id = requireText(id, "id");
        this.name = requireText(name, "name");
        this.description = requireText(description, "description");
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isVisited() {
        return visited;
    }

    public void markVisited() {
        visited = true;
    }

    /**
     * Adds an exit leading out of this location.
     *
     * @throws IllegalArgumentException if this location already has an exit in that direction
     */
    public void addExit(Exit exit) {
        Objects.requireNonNull(exit, "exit");
        if (getExit(exit.getDirection()) != null) {
            throw new IllegalArgumentException(name + " already has an exit " + directionName(exit.getDirection()));
        }
        exits.add(exit);
    }

    /** The exit in this direction, or {@code null} if there is none. */
    public Exit getExit(Direction dir) {
        for (Exit exit : exits) {
            if (exit.getDirection() == dir) {
                return exit;
            }
        }
        return null;
    }

    /**
     * The exit leading directly to the location with this name (ignoring case).
     *
     * @return the exit, or {@code null} if no adjacent location has that name
     */
    public Exit findExitTo(String locationName) {
        if (locationName == null) {
            return null;
        }
        for (Exit exit : exits) {
            if (exit.getDestination().getName().equalsIgnoreCase(locationName.trim())) {
                return exit;
            }
        }
        return null;
    }

    /** The exits of this location in the order they were added, as a read-only view. */
    public List<Exit> getExits() {
        return Collections.unmodifiableList(exits);
    }

    /**
     * Finds an item lying here by name (ignoring case).
     *
     * @return the item, or {@code null} if there is no such item
     */
    public Item findItem(String name) {
        if (name == null) {
            return null;
        }
        for (Item item : items) {
            if (item.getName().equalsIgnoreCase(name.trim())) {
                return item;
            }
        }
        return null;
    }

    public void addItem(Item item) {
        items.add(Objects.requireNonNull(item, "item"));
    }

    /**
     * Removes an item lying here by name (ignoring case).
     *
     * @return the removed item, or {@code null} if there is no such item
     */
    public Item removeItem(String name) {
        Item item = findItem(name);
        if (item != null) {
            items.remove(item);
        }
        return item;
    }

    /** The items lying here, as a read-only view. */
    public List<Item> getItems() {
        return Collections.unmodifiableList(items);
    }

    public void addEnemy(Enemy enemy) {
        enemies.add(Objects.requireNonNull(enemy, "enemy"));
    }

    /**
     * Finds an enemy here by name (ignoring case).
     *
     * @return the enemy, or {@code null} if there is no such enemy
     */
    public Enemy findEnemy(String name) {
        if (name == null) {
            return null;
        }
        for (Enemy enemy : enemies) {
            if (enemy.getName().equalsIgnoreCase(name.trim())) {
                return enemy;
            }
        }
        return null;
    }

    /** Removes a defeated enemy from this location. */
    public void removeEnemy(Enemy enemy) {
        enemies.remove(enemy);
    }

    /** The enemies here, as a read-only view. */
    public List<Enemy> getEnemies() {
        return Collections.unmodifiableList(enemies);
    }

    /** The enemies as text, e.g. {@code "wolf (8/8 HP)"}, or {@code "none"}. */
    public String describeEnemies() {
        if (enemies.isEmpty()) {
            return "none";
        }
        return enemies.stream()
                .map(enemy -> enemy.getName() + " (" + enemy.getHp() + "/" + enemy.getMaxHp() + " HP)")
                .collect(Collectors.joining(", "));
    }

    /** The exits as text, e.g. {@code "north (Tavern), east (Tool Shed, locked)"}. */
    public String describeExits() {
        if (exits.isEmpty()) {
            return "none";
        }
        return exits.stream()
                .map(exit -> directionName(exit.getDirection()) + " (" + exit.getDestination().getName()
                        + (exit.isLocked() ? ", locked" : "") + ")")
                .collect(Collectors.joining(", "));
    }

    /**
     * The name, description, items, enemies (only when there are any) and exits, as shown when the
     * player arrives.
     */
    public String describe() {
        String itemText = items.isEmpty()
                ? "none"
                : items.stream().map(Item::describe).collect(Collectors.joining(", "));
        String enemyText = enemies.isEmpty() ? "" : "\nEnemies: " + describeEnemies();
        return name + "\n" + description + "\nItems: " + itemText + enemyText + "\nExits: " + describeExits();
    }

    static String directionName(Direction direction) {
        return direction.name().toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Location " + field + " must not be blank");
        }
        return value;
    }
}
