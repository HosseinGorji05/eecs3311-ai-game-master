package engine.character;

import java.util.Objects;

/**
 * Common base of every character that has hit points.
 *
 * <p>The combat operations of the design ({@code attackBonus()}, {@code defense()},
 * {@code damage()}) are added together with the combat rules.
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
}
