package presentation;

import engine.commands.ActionType;
import engine.core.CombatMode;
import engine.core.DialogueMode;
import engine.core.GameState;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Which GUI controls work right now, worked out without a display from a copy of the game's situation:
 * whether a game runs, whether it is over, which action types the current mode allows
 * ({@code GameState.allows}, the engine's own rule) and whether the player is in a conversation.
 *
 * <p>The rules add no game rule; they keep the GUI from doing what the CLI cannot:
 * <ul>
 *   <li>While a turn is pending, or before a game starts, after it is over or once the end screen shows,
 *       nothing works except Load and New Game (the end screen's buttons, UC13).</li>
 *   <li>A map click, Take, Use, Equip and Drop work only when the mode allows their action type, so they
 *       are off in combat (except Use and Equip) and in a conversation.</li>
 *   <li>Take is also off in a conversation although {@code DialogueMode} allows TAKE: there a TAKE is the
 *       NPC handing an item over (the reply's side effect), and the CLI cannot send one either, since every
 *       line typed in a conversation is said to the NPC.</li>
 *   <li>Typing and Save stay on in every mode: typed text goes through the same controller as in the CLI,
 *       and the controller refuses a save during combat with a message (SD08).</li>
 *   <li>New Game works only when no game is in progress (UC01's precondition; the CLI's {@code new}).</li>
 *   <li>The mode panels' controls ({@link PanelControl}) follow the mode too: Talk (a click on a person
 *       here, F03) when the mode allows TALK; the combat panel's Attack and Flee in combat (ATTACK and
 *       FLEE); the dialogue panel's Send, Leave and the quest card's Accept and Decline in a conversation
 *       (LEAVE ends it, SD12).</li>
 * </ul>
 */
final class ControlRules {

    /** The controls whose state depends on the game. */
    enum Control {
        SEND, MOVE, TAKE, USE, EQUIP, DROP, SAVE, LOAD, NEW_GAME
    }

    /**
     * The controls of the dialogue panel, the combat panel and the people here; a separate enum from
     * {@link Control}, whose set of controls stays as it was.
     */
    enum PanelControl {
        /** Talk to a person here. */
        TALK,
        /** The combat panel's Attack. */
        ATTACK,
        /** The combat panel's Flee. */
        FLEE,
        /** The dialogue panel's message field and Send. */
        SAY,
        /** The dialogue panel's Leave. */
        LEAVE,
        /** The quest card's Accept and Decline. */
        ANSWER
    }

    static final ControlRules NOT_STARTED = new ControlRules(false, false, false, false,
            EnumSet.noneOf(ActionType.class));

    private final boolean started;
    private final boolean gameOver;
    private final boolean talking;
    private final boolean fighting;
    private final Set<ActionType> allowed;

    ControlRules(boolean started, boolean gameOver, boolean talking, boolean fighting, Set<ActionType> allowed) {
        this.started = started;
        this.gameOver = gameOver;
        this.talking = talking;
        this.fighting = fighting;
        this.allowed = allowed.isEmpty() ? EnumSet.noneOf(ActionType.class) : EnumSet.copyOf(allowed);
    }

    /** Copies what the rules need from the state (on the thread that may read it). */
    static ControlRules of(GameState state) {
        Objects.requireNonNull(state, "state");
        if (state.getPlayer() == null || state.getMode() == null) {
            return NOT_STARTED;
        }
        Set<ActionType> allowed = EnumSet.noneOf(ActionType.class);
        for (ActionType type : ActionType.values()) {
            if (state.allows(type)) {
                allowed.add(type);
            }
        }
        return new ControlRules(true, state.isGameOver(), state.getMode() instanceof DialogueMode,
                state.getMode() instanceof CombatMode, allowed);
    }

    /**
     * Whether the control works.
     *
     * @param busy         a controller call is still running
     * @param endingShown  the end screen is showing
     */
    boolean enabled(Control control, boolean busy, boolean endingShown) {
        Objects.requireNonNull(control, "control");
        if (busy) {
            return false;
        }
        boolean over = gameOver || endingShown;
        if (control == Control.LOAD) {
            return true;
        }
        if (control == Control.NEW_GAME) {
            return !started || over;
        }
        if (!started || over) {
            return false;
        }
        return switch (control) {
            case SEND, SAVE -> true;
            case MOVE -> allowed.contains(ActionType.MOVE);
            case TAKE -> allowed.contains(ActionType.TAKE) && !talking;
            case USE -> allowed.contains(ActionType.USE);
            case EQUIP -> allowed.contains(ActionType.EQUIP);
            case DROP -> allowed.contains(ActionType.DROP);
            case LOAD, NEW_GAME -> throw new AssertionError(control);
        };
    }

    /** Whether a control of the mode panels works; nothing works while busy, before a game or after it. */
    boolean enabled(PanelControl control, boolean busy, boolean endingShown) {
        Objects.requireNonNull(control, "control");
        if (busy || !started || gameOver || endingShown) {
            return false;
        }
        return switch (control) {
            case TALK -> allowed.contains(ActionType.TALK);
            case ATTACK -> fighting && allowed.contains(ActionType.ATTACK);
            case FLEE -> fighting && allowed.contains(ActionType.FLEE);
            case SAY, ANSWER -> talking;
            case LEAVE -> talking && allowed.contains(ActionType.LEAVE);
        };
    }

    boolean isStarted() {
        return started;
    }

    boolean isGameOver() {
        return gameOver;
    }

    boolean isTalking() {
        return talking;
    }

    boolean isFighting() {
        return fighting;
    }
}
