package engine.character;

import engine.items.Consumable;
import engine.items.Item;
import engine.items.Weapon;

import java.util.List;

/** <b>ConcreteCreator</b> (Factory Method) for Warriors: the most HP and a sword. */
public class WarriorFactory extends CharacterFactory {

    public WarriorFactory() {
        super(CharacterClass.WARRIOR);
    }

    @Override
    protected int baseHp() {
        return 30;
    }

    @Override
    protected List<Item> createStartingKit() {
        return List.of(
                new Weapon("sword", 3.0, 6),
                new Consumable("healing potion", 0.5, 10));
    }
}
