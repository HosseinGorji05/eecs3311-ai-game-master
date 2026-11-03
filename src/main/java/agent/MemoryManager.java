package agent;

import engine.character.Player;
import engine.core.GameState;
import engine.core.StoryMemory;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import engine.events.GameEventListener;
import engine.world.Location;
import engine.world.NPC;
import engine.world.Region;
import engine.world.World;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Keeps the story memory up to date and reads it for prompts and the journal (F09, SD06).
 *
 * <p>Pattern role: <b>ConcreteObserver</b> of the Observer pattern. Every published event except a
 * REJECTED one (a refused attempt changes nothing; the event history still shows it) is recorded in
 * the {@link StoryMemory}. A key fact (a death, a quest outcome, or any event with a non-zero
 * alignment delta, such as a theft) is also kept as a fixed fact. Once more than
 * {@value #SUMMARIZE_THRESHOLD} recent events are stored, the oldest {@value #SUMMARY_BATCH} are taken
 * out and the {@link MemorySummarizer} condenses them into one summary, which is stored with the turns
 * it covers. Summarizing never fails a turn: the summarizer falls back to the events' own descriptions,
 * and any other failure leads to the same fallback.
 *
 * <p>{@link #buildContext} picks what goes into a prompt within a size limit: the fixed facts first,
 * then the summaries and recent events that mention the prompt's focus (an NPC or location id, also
 * matched by its name), then the other recent events and summaries, newest first; whatever does not
 * fit is dropped. {@link #journalText} shows all of the memory and never calls the model (UC09).
 */
public class MemoryManager implements GameEventListener {

    /** How many recent events are kept before the oldest are summarized. */
    static final int SUMMARIZE_THRESHOLD = 20;
    /** How many of the oldest events one summary covers. */
    static final int SUMMARY_BATCH = 10;
    /** The memory context when nothing is remembered yet. */
    static final String NOTHING_REMEMBERED = "Nothing remembered yet.";
    static final String STORY_JUST_BEGUN = "The story has just begun.";

    private final GameState state;
    private final MemorySummarizer summarizer;

    public MemoryManager(GameState state, MemorySummarizer summarizer) {
        this.state = Objects.requireNonNull(state, "state");
        this.summarizer = Objects.requireNonNull(summarizer, "summarizer");
    }

    @Override
    public void onEvent(GameEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.getType() == EventType.REJECTED) {
            return;
        }
        StoryMemory memory = state.getStoryMemory();
        memory.record(event);
        if (isKeyFact(event)) {
            memory.addFixedFact(event.getDescription());
        }
        if (memory.recentCount() > SUMMARIZE_THRESHOLD) {
            List<GameEvent> oldest = memory.takeOldest(SUMMARY_BATCH);
            memory.addSummary(turns(oldest) + summarize(oldest));
        }
    }

    /** Adds the {@link #startingFacts} of the game as fixed facts; called once, when the game starts. */
    public void addStartingFacts() {
        startingFacts(state).forEach(state.getStoryMemory()::addFixedFact);
    }

    /**
     * The fixed facts every game starts with, all built here from the game state: who the player is
     * and where they arrived, what they set out with, and where each person can be found.
     */
    static List<String> startingFacts(GameState state) {
        Player player = state.getPlayer();
        World world = state.getWorld();
        Location start = world.getStartLocation();
        List<String> facts = new ArrayList<>();
        Region region = regionOf(world, start);
        facts.add(player.getName() + ", a " + player.getCharacterClass().name().toLowerCase(Locale.ROOT)
                + ", arrived at the " + start.getName() + (region == null ? "" : " in the " + region.getName())
                + " when the story began.");
        Map<String, Integer> kit = new LinkedHashMap<>();
        player.getInventory().getItems().forEach(item -> kit.merge(item.getName(), 1, Integer::sum));
        if (!kit.isEmpty()) {
            facts.add(player.getName() + " set out carrying: " + kit.entrySet().stream()
                    .map(entry -> entry.getValue() == 1 ? entry.getKey() : entry.getKey() + " (" + entry.getValue() + ")")
                    .collect(Collectors.joining(", ")) + ".");
        }
        for (Region each : world.getRegions()) {
            for (Location location : each.getLocations()) {
                for (NPC npc : location.getNpcs()) {
                    facts.add(npc.getName() + " the " + npc.getRole() + " can be found at the " + location.getName() + ".");
                }
            }
        }
        return facts;
    }

    /**
     * The memory most relevant to {@code focus}, at most {@code maxChars} characters long, as described
     * in the class comment; {@value #NOTHING_REMEMBERED} if nothing is remembered.
     *
     * @param focus what the prompt is about (an NPC's or a location's id), or {@code null}
     */
    public String buildContext(String focus, int maxChars) {
        StoryMemory memory = state.getStoryMemory();
        Set<String> terms = focusTerms(focus);
        List<String> summaries = memory.getSummaries();
        List<GameEvent> events = memory.getRecentEvents();
        List<Entry> byPriority = new ArrayList<>();
        List<String> facts = memory.getFixedFacts();
        for (int i = 0; i < facts.size(); i++) {
            byPriority.add(new Entry(Section.FACTS, i, facts.get(i)));
        }
        addSummaries(byPriority, summaries, summary -> mentions(summary, terms));
        addEvents(byPriority, events, event -> mentions(event, terms));
        addEvents(byPriority, events, event -> !mentions(event, terms));
        addSummaries(byPriority, summaries, summary -> !mentions(summary, terms));
        List<Entry> chosen = new ArrayList<>();
        for (Entry entry : byPriority) {
            chosen.add(entry);
            if (render(chosen).length() > maxChars) {
                chosen.remove(chosen.size() - 1);
            }
        }
        if (chosen.isEmpty()) {
            return NOTHING_REMEMBERED.length() <= maxChars ? NOTHING_REMEMBERED : "";
        }
        return render(chosen);
    }

    /**
     * The whole story memory as readable text: the fixed facts, the summaries of older events and the
     * recent events, or {@value #STORY_JUST_BEGUN} if no event has been remembered yet.
     */
    public String journalText() {
        StoryMemory memory = state.getStoryMemory();
        List<Entry> all = new ArrayList<>();
        List<String> facts = memory.getFixedFacts();
        for (int i = 0; i < facts.size(); i++) {
            all.add(new Entry(Section.FACTS, i, facts.get(i)));
        }
        List<String> summaries = memory.getSummaries();
        for (int i = 0; i < summaries.size(); i++) {
            all.add(new Entry(Section.EARLIER, i, summaries.get(i)));
        }
        List<GameEvent> events = memory.getRecentEvents();
        for (int i = 0; i < events.size(); i++) {
            all.add(new Entry(Section.RECENT, i, describe(events.get(i))));
        }
        String title = "Journal of " + state.getPlayer().getName();
        String text = render(all);
        String story = summaries.isEmpty() && events.isEmpty() ? STORY_JUST_BEGUN : "";
        return List.of(title, text, story).stream().filter(part -> !part.isEmpty()).collect(Collectors.joining("\n"));
    }

    /** Where the summarizer describes failed model attempts; {@code null} turns this off. */
    public void setDebugOutput(PrintStream debug) {
        summarizer.setDebugOutput(debug);
    }

    /** A death, a quest outcome, or any event with a non-zero alignment delta (e.g. a theft), as in SD06. */
    private static boolean isKeyFact(GameEvent event) {
        EventVerb verb = event.getVerb();
        return verb == EventVerb.DEFEATED || verb == EventVerb.DIED
                || event.getType() == EventType.QUEST && (verb == EventVerb.COMPLETED || verb == EventVerb.FAILED)
                || event.getAlignmentDelta() != 0;
    }

    /** Adds the summaries that pass the filter, newest first. */
    private static void addSummaries(List<Entry> entries, List<String> summaries, Predicate<String> filter) {
        for (int i = summaries.size() - 1; i >= 0; i--) {
            if (filter.test(summaries.get(i))) {
                entries.add(new Entry(Section.EARLIER, i, summaries.get(i)));
            }
        }
    }

    /** Adds the recent events that pass the filter, newest first. */
    private static void addEvents(List<Entry> entries, List<GameEvent> events, Predicate<GameEvent> filter) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (filter.test(events.get(i))) {
                entries.add(new Entry(Section.RECENT, i, describe(events.get(i))));
            }
        }
    }

    /** The summarizer's note; any failure outside its own fallback also ends in the events' descriptions. */
    private String summarize(List<GameEvent> oldest) {
        try {
            return summarizer.summarize(oldest);
        } catch (RuntimeException failure) {
            return MemorySummarizer.fallbackSummary(oldest);
        }
    }

    /** "Turn 3: " or "Turns 3-7: " for the events a summary covers. */
    private static String turns(List<GameEvent> events) {
        int first = events.get(0).getTurn();
        int last = events.get(events.size() - 1).getTurn();
        return first == last ? "Turn " + first + ": " : "Turns " + first + "-" + last + ": ";
    }

    private static Region regionOf(World world, Location location) {
        return world.getRegions().stream().filter(region -> region.getLocations().contains(location)).findFirst().orElse(null);
    }

    /** The focus and, if it is an NPC's or a location's id, its name, in lower case. */
    private Set<String> focusTerms(String focus) {
        Set<String> terms = new LinkedHashSet<>();
        if (focus == null || focus.isBlank()) {
            return terms;
        }
        terms.add(focus.toLowerCase(Locale.ROOT));
        World world = state.getWorld();
        if (world != null) {
            NPC npc = world.findNpc(focus);
            if (npc != null) {
                terms.add(npc.getName().toLowerCase(Locale.ROOT));
            }
            Location location = world.findLocation(focus);
            if (location != null) {
                terms.add(location.getName().toLowerCase(Locale.ROOT));
            }
        }
        return terms;
    }

    private static boolean mentions(String text, Set<String> terms) {
        String lower = text.toLowerCase(Locale.ROOT);
        return terms.stream().anyMatch(lower::contains);
    }

    private static boolean mentions(GameEvent event, Set<String> terms) {
        String subject = event.getSubjectId() == null ? "" : event.getSubjectId().toLowerCase(Locale.ROOT);
        return terms.contains(subject) || mentions(event.getDescription(), terms);
    }

    private static String describe(GameEvent event) {
        return "[turn " + event.getTurn() + "] " + event.getDescription();
    }

    /** The parts of the memory, in the order they are shown. */
    private enum Section {
        FACTS("Facts"),
        EARLIER("Earlier"),
        RECENT("Recent events");

        private final String heading;

        Section(String heading) {
            this.heading = heading;
        }
    }

    /** One line of memory: its section, its position there (oldest first) and its text. */
    private record Entry(Section section, int index, String text) {
    }

    /** The entries under their section headings, each section oldest first; empty sections are left out. */
    private static String render(List<Entry> entries) {
        List<String> lines = new ArrayList<>();
        for (Section section : Section.values()) {
            List<Entry> inSection = entries.stream().filter(entry -> entry.section() == section)
                    .sorted((a, b) -> Integer.compare(a.index(), b.index())).toList();
            if (!inSection.isEmpty()) {
                lines.add(section.heading + ":");
                inSection.forEach(entry -> lines.add("- " + entry.text()));
            }
        }
        return String.join("\n", lines);
    }
}
