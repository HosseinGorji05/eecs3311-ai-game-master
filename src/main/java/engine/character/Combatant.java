package engine.character;

import java.util.Objects;

/**
 * Common base of every character that has hit points and can fight.
 *
 * <p>An attack hits when {@code d20 + attacker.attackBonus() >= target.defense()} and then deals
 * {@code attacker.damage()} (see {@code RulesEngine.resolveAttack()}).
 */
public abstract class Combatant {

    protected String name;
    protected int hp;
    protected int maxHp;

    protected Combatant(String name, int maxHp) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Name must not be blank");
        }
        if (maxHp <= 0) {
            throw new IllegalArgumentException("Maximum HP must be positive: " + maxHp);
        }
        this.name = name;
        this.maxHp = maxHp;
        this.hp = maxHp;
    }

    /** Lowers HP by {@code amount}, never below 0. */
    public void takeDamage(int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Damage must not be negative: " + amount);
        }
        hp = Math.max(0, hp - amount);
    }

    public boolean isAlive() {
        return hp > 0;
    }

    /** Added to the attacker's d20 roll. */
    public abstract int attackBonus();

    /** The total an attack roll must reach to hit this combatant. */
    public abstract int defense();

    /** The HP a hit by this combatant takes away. */
    public abstract int damage();

    public String getName() {
        return name;
    }

    public int getHp() {
        return hp;
    }

    public int getMaxHp() {
        return maxHp;
    }
}
