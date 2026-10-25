package engine.character;

import engine.items.Consumable;
import engine.items.Container;
import engine.items.Item;
import engine.items.Weapon;

import java.util.List;

/** <b>ConcreteCreator</b> (Factory Method) for Rogues: a dagger and a pouch holding a potion. */
public class RogueFactory extends CharacterFactory {

    public RogueFactory() {
        super(CharacterClass.ROGUE);
    }

    @Override
    protected int baseHp() {
        return 24;
    }

    @Override
    protected List<Item> createStartingKit() {
        Container pouch = new Container("pouch", 0.2, 4);
        pouch.add(new Consumable("healing potion", 0.5, 10));
        return List.of(new Weapon("dagger", 1.0, 4), pouch);
    }
}
