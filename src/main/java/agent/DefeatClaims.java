package agent;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a text says that an enemy was defeated or died, in any tense, for the checks of
 * {@link EndingWriter} and {@link Narrator} that reject a defeat that never happened.
 *
 * <p>A sentence claims the defeat when, around the enemy's name:
 * <ul>
 *   <li>a defeat verb comes before the name and no "by" says who did it to whom: "Ari slew the wolf",
 *       "Ari kills the wolf" (not "nearly kills"); or the name follows "dead", "slain", "fallen" or
 *       "lifeless": "the dead wolf";</li>
 *   <li>the name is followed by a passive or a state of death: "the wolf was killed", "the wolf had been
 *       slain", "the wolf, which had long been dead", "the wolf lay dead", "the wolf is no more";</li>
 *   <li>the name is followed by a verb of dying: "the wolf died", "the wolf falls", "the wolf had fallen",
 *       "the wolf perished" (not "fell silent", "fell back", "falls upon Ari", ...);</li>
 *   <li>or by "'s death", "'s corpse" or "'s carcass".</li>
 * </ul>
 * Between the name and the verb only auxiliaries and a few time adverbs may stand, so a denial ("the wolf
 * was never killed", "the wolf wasn't slain") and the enemy as the one who kills ("the wolf killed a sheep",
 * "the wolf had killed a sheep") are no claim.
 */
final class DefeatClaims {

    /** Verbs that, before an enemy's name (and without "by"), say it was defeated. */
    private static final Pattern VERBS_BEFORE = Pattern.compile(
            "\\b(?<!nearly )(?<!almost )(defeated|defeats|slew|slays|vanquished|vanquishes|killed|kills|slain|struck down|strikes down)\\b");
    /** Words right before an enemy's name that say it is dead: "the dead wolf". */
    private static final Pattern DEAD_BEFORE = Pattern.compile("\\b(dead|slain|fallen|lifeless)\\s+$");

    private static final String ADVERBS = "(?:(?:already|long|finally|now|soon|then|quickly|also)\\s+)*";
    private static final String HAVE = "(?:(?:had|has|have)\\s+)?";
    private static final String BE = "(?:is|was|were|are|been|being|got|gets|lay|lies|lie|lying|remains|remained)";
    private static final String DEFEATED_STATE = "(?:defeated|killed|slain|vanquished|struck down|cut down|felled"
            + "|destroyed|finished off|put down|dead|no more)";
    private static final String DYING = "(?:died|dies|perished|perishes|fell|falls|fallen)"
            + "(?!\\s+(?:silent|asleep|back|behind|quiet|still|ill|short|apart|into|in love|upon|on|onto|over"
            + "|forward|towards?|across|at)\\b)";
    /** What, right after an enemy's name, says it was defeated or died (see the class comment). */
    private static final Pattern CLAIM_AFTER = Pattern.compile("^(?:\\s*,)?\\s+(?:(?:which|who|that)\\s+)?" + ADVERBS
            + "(?:" + HAVE + ADVERBS + BE + "\\s+" + ADVERBS + DEFEATED_STATE + "|" + HAVE + ADVERBS + DYING + ")\\b"
            + "|^['’]s\\s+(?:death|corpse|carcass)\\b");

    private DefeatClaims() {
    }

    /** The text in lower case, split into sentences (and clauses after ";" and line breaks). */
    static List<String> sentences(String text) {
        return List.of(text.toLowerCase(Locale.ROOT).split("[.!?;\\n]"));
    }

    /** Whether one of the lower-case {@code sentences} says the enemy was defeated or died. */
    static boolean claimsDefeated(List<String> sentences, String enemy) {
        Pattern name = Pattern.compile("\\b" + Pattern.quote(enemy.toLowerCase(Locale.ROOT)) + "\\b");
        for (String sentence : sentences) {
            Matcher mention = name.matcher(sentence);
            boolean first = true;
            while (mention.find()) {
                String before = sentence.substring(0, mention.start());
                if (first && mention.start() > 0 && !sentence.contains(" by ") && VERBS_BEFORE.matcher(before).find()
                        || DEAD_BEFORE.matcher(before).find()
                        || CLAIM_AFTER.matcher(sentence.substring(mention.end())).find()) {
                    return true;
                }
                first = false;
            }
        }
        return false;
    }
}
