package engine.items;

/**
 * A key that can unlock a locked exit whose required key name matches this item's name.
 *
 * <p>Pattern roles: <b>Leaf</b> (Composite) and <b>ConcreteProduct</b> (Factory Method).
 */
public class KeyItem extends Item {

    public KeyItem(String name, double weight) {
        super(name, weight);
    }

    public KeyItem(String name, double weight, String ownerId) {
        super(name, weight, ownerId);
    }

    @Override
    public double getWeight() {
        return weight;
    }
}
