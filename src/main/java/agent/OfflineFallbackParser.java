package agent;

import engine.commands.ActionProposal;
import engine.commands.ActionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A keyword parser for plain commands, used when the language model cannot help (UC14): "go east",
 * "e", "go to the tavern", "take the shed key", "pick up iron key", "attack wolf", "flee", and
 * several of them joined with "and", "then" or commas.
 *
 * <p>All or nothing: if any part of the input matches no known pattern, or names "it" / "them"
 * instead of a thing, the result is empty and the player is asked to rephrase.
 */
public class OfflineFallbackParser {

    private static final Pattern CLAUSE_SEPARATOR = Pattern.compile("\\s*(?:,|;|\\band then\\b|\\bthen\\b|\\band\\b)\\s*");
    private static final Map<String, String> DIRECTION_SHORTCUTS = Map.of(
            "n", "north", "s", "south", "e", "east", "w", "west");
    private static final Set<String> PRONOUNS = Set.of("it", "them", "that", "this", "him", "her");

    private static final Pattern FLEE = Pattern.compile("flee|run|run away|escape|retreat");
    private static final Pattern LEAVE = Pattern.compile("leave|bye|goodbye");
    private static final Pattern LOOK = Pattern.compile("look|l|look around");
    private static final Pattern INVENTORY = Pattern.compile("inventory|inv|i");
    private static final Pattern DIRECTION = Pattern.compile("n|s|e|w|north|south|east|west");
    private static final Pattern MOVE = Pattern.compile("(?:go|walk|move|head|travel|run)(?: to)? (.+)");
    private static final Pattern PUT = Pattern.compile("put (.+) (?:in|into) (.+)");
    private static final List<Rule> TARGET_RULES = List.of(
            new Rule(Pattern.compile("(?:take|get|grab|pick up|pick) (.+?)(?: up)?"), ActionType.TAKE),
            new Rule(Pattern.compile("(?:attack|fight|kill|hit|strike) (.+)"), ActionType.ATTACK),
            new Rule(Pattern.compile("drop (.+)"), ActionType.DROP),
            new Rule(Pattern.compile("(?:use|drink|eat) (.+)"), ActionType.USE),
            new Rule(Pattern.compile("(?:equip|wield) (.+)"), ActionType.EQUIP),
            new Rule(Pattern.compile("(?:talk|speak)(?: to| with)? (.+)"), ActionType.TALK));

    /** The actions in the text, in order; empty if the text is not understood. */
    public List<ActionProposal> parse(String text) {
        if (text == null) {
            return List.of();
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[.!?]+$", "")
                .replaceAll("\\s+", " ");
        List<ActionProposal> proposals = new ArrayList<>();
        for (String clause : CLAUSE_SEPARATOR.split(normalized)) {
            if (clause.isBlank()) {
                continue;
            }
            ActionProposal proposal = parseClause(clause.trim());
            if (proposal == null) {
                return List.of();
            }
            proposals.add(proposal);
        }
        return List.copyOf(proposals);
    }

    private static ActionProposal parseClause(String clause) {
        if (FLEE.matcher(clause).matches()) {
            return new ActionProposal(ActionType.FLEE, null, null);
        }
        if (LEAVE.matcher(clause).matches()) {
            return new ActionProposal(ActionType.LEAVE, null, null);
        }
        if (LOOK.matcher(clause).matches()) {
            return new ActionProposal(ActionType.LOOK, null, null);
        }
        if (INVENTORY.matcher(clause).matches()) {
            return new ActionProposal(ActionType.INVENTORY, null, null);
        }
        if (DIRECTION.matcher(clause).matches()) {
            return move(clause);
        }
        Matcher put = PUT.matcher(clause);
        if (put.matches()) {
            String item = target(put.group(1));
            String container = target(put.group(2));
            return item == null || container == null ? null : new ActionProposal(ActionType.PUT, item, container);
        }
        Matcher move = MOVE.matcher(clause);
        if (move.matches()) {
            return move(move.group(1));
        }
        for (Rule rule : TARGET_RULES) {
            Matcher matcher = rule.pattern().matcher(clause);
            if (matcher.matches()) {
                String target = target(matcher.group(1));
                return target == null ? null : new ActionProposal(rule.type(), target, null);
            }
        }
        return null;
    }

    private static ActionProposal move(String where) {
        String destination = target(where);
        if (destination == null) {
            return null;
        }
        destination = DIRECTION_SHORTCUTS.getOrDefault(destination, destination);
        return new ActionProposal(ActionType.MOVE, destination, null);
    }

    /** The target without a leading article; {@code null} if it is empty or only a pronoun. */
    private static String target(String raw) {
        String target = raw.trim().replaceFirst("^(?:the|a|an) ", "").trim();
        return target.isEmpty() || PRONOUNS.contains(target) ? null : target;
    }

    /** A verb pattern whose group 1 is the target, and the action type it stands for. */
    private record Rule(Pattern pattern, ActionType type) {
    }
}
