package engine.character;

/**
 * A hostile creature in a location, such as a wolf or the bandit leader.
 *
 * <p>Its {@code attack} is both its attack bonus and the damage of a hit; {@code defenseRating}
 * is its defense. The enemy's name also identifies it in events and quest objectives.
 */
public class Enemy extends Combatant {

    private final int attack;
    private final int defenseRating;

    /**
     * @throws IllegalArgumentException if the name is blank, the HP is not positive, or attack or
     *                                  defense is negative
     */
    public Enemy(String name, int maxHp, int attack, int defenseRating) {
        super(name, maxHp);
        if (attack < 0 || defenseRating < 0) {
            throw new IllegalArgumentException("Attack and defense must not be negative: " + attack + ", " + defenseRating);
        }
        this.attack = attack;
        this.defenseRating = defenseRating;
    }

    @Override
    public int attackBonus() {
        return attack;
    }

    @Override
    public int defense() {
        return defenseRating;
    }

    @Override
    public int damage() {
        return attack;
    }
}
