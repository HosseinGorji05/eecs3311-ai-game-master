package presentation;

import engine.character.Enemy;
import engine.character.Player;
import engine.core.CombatMode;
import engine.core.GameState;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the combat panel shows (F04, SD04, SD12), copied from the game state without a display: whether the
 * player is fighting, both fighters' HP, and the last round's dice in readable form.
 *
 * <p>The dice come from the engine, never recomputed: the round is the COMBAT events of the latest turn
 * that has any, and an attack event's description is the rules engine's own text ("Ari attacks wolf: rolls
 * 17 + 2 = 19 against defense 11. Hit for 4 damage (wolf: 4/8 HP)."), which {@link #parseAttack} reads
 * back. A defeat, a death and a flee come from their event verbs. The engine records no roll for a flee
 * (only whether it worked), so a flee shows its outcome only. Text that does not match is shown as it is.
 */
final class CombatSummary {

    private static final Pattern ATTACK = Pattern.compile(
            "(.+?) attacks (.+?): rolls (\\d+) \\+ (-?\\d+) = (-?\\d+) against defense (-?\\d+)\\. "
                    + "(?:Miss\\.|Hit for (\\d+) damage \\((.+): (-?\\d+)/(\\d+) HP\\)\\.)");
    private static final String FAILED_TO_FLEE = "failed to flee from the ";

    /** A fighter's name and HP. */
    record Fighter(String name, int hp, int maxHp) {
    }

    /**
     * One attack as the rules engine resolved it. On a miss {@code damage} is 0 and the target's HP are
     * unknown (-1): the engine's miss text does not give them.
     */
    record Attack(String attacker, String target, int roll, int bonus, int total, int defense, boolean hit,
                  int damage, int targetHp, int targetMaxHp) {
    }

    static final CombatSummary NONE = new CombatSummary(false, null, null, List.of());

    private final boolean fighting;
    private final Fighter player;
    private final Fighter enemy;
    private final List<String> lastRound;

    private CombatSummary(boolean fighting, Fighter player, Fighter enemy, List<String> lastRound) {
        this.fighting = fighting;
        this.player = player;
        this.enemy = enemy;
        this.lastRound = List.copyOf(lastRound);
    }

    /** The combat panel's content: the fighters only in combat; the last round whenever there was one. */
    static CombatSummary of(GameState state) {
        Objects.requireNonNull(state, "state");
        if (state.getPlayer() == null || state.getMode() == null) {
            return NONE;
        }
        List<String> round = describeRound(lastRound(state.getEventLog().filter(EventType.COMBAT)));
        if (!(state.getMode() instanceof CombatMode combat)) {
            return new CombatSummary(false, null, null, round);
        }
        Player hero = state.getPlayer();
        Enemy foe = combat.getEnemy();
        return new CombatSummary(true, new Fighter(hero.getName(), hero.getHp(), hero.getMaxHp()),
                new Fighter(foe.getName(), foe.getHp(), foe.getMaxHp()), round);
    }

    /** The COMBAT events of the latest turn that has any, oldest first. */
    static List<GameEvent> lastRound(List<GameEvent> combatEvents) {
        if (combatEvents.isEmpty()) {
            return List.of();
        }
        int turn = combatEvents.get(combatEvents.size() - 1).getTurn();
        return combatEvents.stream().filter(event -> event.getTurn() == turn).toList();
    }

    /** One readable line per event of the round. */
    static List<String> describeRound(List<GameEvent> round) {
        List<String> lines = new ArrayList<>();
        for (GameEvent event : round) {
            lines.add(describe(event));
        }
        return lines;
    }

    /**
     * "Ari rolled 17 + 2 = 19 against defense 11: hit, 4 damage (wolf 4/8 HP).", "... : miss.",
     * "The wolf is defeated.", "Ari died.", "Flee: escaped from the wolf.", "Flee: failed to escape from the
     * wolf."; any other description as it is.
     */
    static String describe(GameEvent event) {
        Objects.requireNonNull(event, "event");
        EventVerb verb = event.getVerb();
        if (verb == EventVerb.DEFEATED) {
            return "The " + event.getSubjectId() + " is defeated.";
        }
        if (verb == EventVerb.DIED) {
            return event.getSubjectId() + " died.";
        }
        if (verb == EventVerb.FLED) {
            return "Flee: escaped from the " + event.getSubjectId() + ".";
        }
        if (event.getDescription().startsWith(FAILED_TO_FLEE)) {
            return "Flee: failed to escape from the " + event.getDescription().substring(FAILED_TO_FLEE.length())
                    + ".";
        }
        Attack attack = parseAttack(event.getDescription());
        if (attack == null) {
            return event.getDescription();
        }
        String outcome = attack.hit()
                ? String.format(Locale.ROOT, "hit, %d damage (%s %d/%d HP)", attack.damage(), attack.target(),
                        attack.targetHp(), attack.targetMaxHp())
                : "miss";
        return String.format(Locale.ROOT, "%s rolled %d + %d = %d against defense %d: %s.", attack.attacker(),
                attack.roll(), attack.bonus(), attack.total(), attack.defense(), outcome);
    }

    /** The attack in the rules engine's text, or {@code null} if the text is not an attack. */
    static Attack parseAttack(String description) {
        Matcher matcher = ATTACK.matcher(description);
        if (!matcher.matches()) {
            return null;
        }
        boolean hit = matcher.group(7) != null;
        return new Attack(matcher.group(1), matcher.group(2), Integer.parseInt(matcher.group(3)),
                Integer.parseInt(matcher.group(4)), Integer.parseInt(matcher.group(5)),
                Integer.parseInt(matcher.group(6)), hit, hit ? Integer.parseInt(matcher.group(7)) : 0,
                hit ? Integer.parseInt(matcher.group(9)) : -1, hit ? Integer.parseInt(matcher.group(10)) : -1);
    }

    boolean isFighting() {
        return fighting;
    }

    /** The player in combat, or {@code null} outside it. */
    Fighter getPlayer() {
        return player;
    }

    /** The enemy in combat, or {@code null} outside it. */
    Fighter getEnemy() {
        return enemy;
    }

    /** The last round's lines, empty before any fight. */
    List<String> getLastRound() {
        return lastRound;
    }
}
