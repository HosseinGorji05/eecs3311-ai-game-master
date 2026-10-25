package engine.character;

import engine.items.Item;

import java.util.List;
import java.util.Objects;

/**
 * Builds a {@link Player} for one character class.
 *
 * <p>Pattern role: <b>Creator</b> of the Factory Method pattern. {@link #createCharacter} runs
 * the same steps for every class and calls the factory method {@link #createStartingKit()}, which
 * each ConcreteCreator overrides; {@link #baseHp()} supplies the class's HP.
 */
public abstract class CharacterFactory {

    private final CharacterClass characterClass;

    protected CharacterFactory(CharacterClass characterClass) {
        this.characterClass = Objects.requireNonNull(characterClass, "characterClass");
    }

    /** Returns the concrete factory for the chosen class. */
    public static CharacterFactory forClass(CharacterClass cls) {
        Objects.requireNonNull(cls, "cls");
        return switch (cls) {
            case WARRIOR -> new WarriorFactory();
            case ROGUE -> new RogueFactory();
            case MAGE -> new MageFactory();
        };
    }

    /**
     * Creates a player of this factory's class with its base HP and starting kit.
     *
     * @throws IllegalArgumentException if the name is blank or the stats are not valid
     */
    public Player createCharacter(String name, Stats stats) {
        int hp = baseHp();
        List<Item> kit = createStartingKit();
        return new Player(name, characterClass, stats, hp, kit);
    }

    protected abstract int baseHp();

    /** The factory method: creates the class-specific starting items. */
    protected abstract List<Item> createStartingKit();
}
