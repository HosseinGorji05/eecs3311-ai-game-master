package engine.items;

/**
 * An item the player can equip to deal damage.
 *
 * <p>Pattern roles: <b>Leaf</b> (Composite) and <b>ConcreteProduct</b> (Factory Method).
 */
public class Weapon extends Item {

    private final int damage;

    public Weapon(String name, double weight, int damage) {
        this(name, weight, damage, null);
    }

    public Weapon(String name, double weight, int damage, String ownerId) {
        super(name, weight, ownerId);
        if (damage < 0) {
            throw new IllegalArgumentException("Weapon damage must not be negative: " + damage);
        }
        this.damage = damage;
    }

    public int getDamage() {
        return damage;
    }

    @Override
    public double getWeight() {
        return weight;
    }
}
