package engine.character;

import engine.items.Consumable;
import engine.items.Item;
import engine.items.Weapon;

import java.util.List;

/** <b>ConcreteCreator</b> (Factory Method) for Mages: the least HP, a staff and two potions. */
public class MageFactory extends CharacterFactory {

    public MageFactory() {
        super(CharacterClass.MAGE);
    }

    @Override
    protected int baseHp() {
        return 18;
    }

    @Override
    protected List<Item> createStartingKit() {
        return List.of(
                new Weapon("staff", 2.0, 3),
                new Consumable("healing potion", 0.5, 10),
                new Consumable("healing potion", 0.5, 10));
    }
}
