package engine.commands;

import engine.world.Direction;

import java.util.Locale;
import java.util.Objects;

/**
 * Turns an {@link ActionProposal}, from the LLM or from a button, into a {@link GameCommand}.
 *
 * <p>Pattern roles: <b>Client</b> of the Command pattern, and a factory whose
 * {@link #create(ActionProposal)} picks the concrete command for the proposal's type. The command
 * is only created here; whether it may run is decided later by its {@code validate()}.
 */
public class CommandFactory {

    /**
     * Creates the command for a proposal. For MOVE, a target that names a direction ("east")
     * gives a move in that direction; any other target is the name of an adjacent location.
     * TAKE and ATTACK need a target; FLEE ignores it.
     *
     * @throws IllegalArgumentException if the target is missing, or there is no command for the
     *                                  proposal's type yet
     */
    public GameCommand create(ActionProposal proposal) {
        Objects.requireNonNull(proposal, "proposal");
        return switch (proposal.getType()) {
            case MOVE -> createMove(requireTarget(proposal));
            case TAKE -> new TakeCommand(requireTarget(proposal));
            case ATTACK -> new AttackCommand(requireTarget(proposal));
            case FLEE -> new FleeCommand();
            default -> throw new IllegalArgumentException("Unsupported action type: " + proposal.getType());
        };
    }

    private static GameCommand createMove(String target) {
        Direction direction = parseDirection(target);
        return direction != null ? new MoveCommand(direction) : new MoveCommand(target);
    }

    private static Direction parseDirection(String text) {
        try {
            return Direction.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notADirection) {
            return null;
        }
    }

    private static String requireTarget(ActionProposal proposal) {
        String target = proposal.getTarget();
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException(proposal.getType() + " needs a target");
        }
        return target;
    }
}
