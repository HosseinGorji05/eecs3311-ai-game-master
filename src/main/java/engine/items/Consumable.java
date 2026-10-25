package engine.items;

/**
 * An item that is used up, such as a potion that restores HP.
 *
 * <p>Pattern roles: <b>Leaf</b> (Composite) and <b>ConcreteProduct</b> (Factory Method).
 */
public class Consumable extends Item {

    private final int healAmount;

    public Consumable(String name, double weight, int healAmount) {
        this(name, weight, healAmount, null);
    }

    public Consumable(String name, double weight, int healAmount, String ownerId) {
        super(name, weight, ownerId);
        if (healAmount < 0) {
            throw new IllegalArgumentException("Heal amount must not be negative: " + healAmount);
        }
        this.healAmount = healAmount;
    }

    public int getHealAmount() {
        return healAmount;
    }

    @Override
    public double getWeight() {
        return weight;
    }
}
