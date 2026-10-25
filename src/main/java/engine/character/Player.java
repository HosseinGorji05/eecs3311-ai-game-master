package engine.character;

import engine.items.Inventory;
import engine.items.Item;
import engine.items.Weapon;

import java.util.List;
import java.util.Objects;

/**
 * The player's character: class, stats, inventory, equipped weapon and backstory.
 *
 * <p>Pattern role: the object built by the Factory Method pattern ({@link CharacterFactory}
 * creates it with class-specific HP and starting kit). Its {@link Inventory} is the client of the
 * item Composite.
 */
public class Player extends Combatant {

    private final CharacterClass characterClass;
    private final Stats stats;
    private final Inventory inventory;
    private Weapon equippedWeapon;
    private String backstory;

    /**
     * Creates a player at full HP carrying the given starting kit.
     *
     * @throws IllegalArgumentException if the stats break the stat-point rules, or the kit does
     *                                  not fit in the inventory
     */
    public Player(String name, CharacterClass cls, Stats stats, int hp, List<Item> kit) {
        super(name, hp);
        this.characterClass = Objects.requireNonNull(cls, "cls");
        Objects.requireNonNull(stats, "stats");
        if (!stats.isValid()) {
            throw new IllegalArgumentException("Spend exactly 10 points, 1-8 per stat");
        }
        this.stats = stats;
        this.inventory = new Inventory();
        Objects.requireNonNull(kit, "kit");
        for (Item item : kit) {
            if (!inventory.add(item)) {
                String itemName = item == null ? "null" : item.getName();
                throw new IllegalArgumentException("Starting kit does not fit in the inventory: " + itemName);
            }
        }
    }

    public Stats getStats() {
        return stats;
    }

    public Inventory getInventory() {
        return inventory;
    }

    /** Restores HP by {@code amount}, never above the maximum. */
    public void heal(int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Heal amount must not be negative: " + amount);
        }
        hp = Math.min(maxHp, hp + amount);
    }

    public void equip(Weapon weapon) {
        this.equippedWeapon = Objects.requireNonNull(weapon, "weapon");
    }

    /** The equipped weapon, or {@code null} if none is equipped. */
    public Weapon getEquippedWeapon() {
        return equippedWeapon;
    }

    public void setBackstory(String text) {
        this.backstory = text;
    }

    /** The backstory, or {@code null} if none has been set yet. */
    public String getBackstory() {
        return backstory;
    }
}
