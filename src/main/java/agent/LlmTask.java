package agent;

import engine.core.GameState;
import llm.LLMClient;
import llm.LlmRequest;
import llm.LlmResponse;

import java.io.PrintStream;
import java.util.Objects;

/**
 * One feature that asks the language model for something, done safely: build a prompt, ask the
 * model, parse and check the answer, retry once, and fall back to deterministic code if the model
 * is unavailable, fails, or keeps giving unusable answers (SD00).
 *
 * <p>Pattern roles: <b>AbstractClass</b> of the Template Method pattern ({@link #run} is the final
 * template method; {@link #buildPrompt}, {@link #parse}, {@link #isValid} and {@link #fallback}
 * are the primitive operations) and <b>Context</b> of the Strategy pattern ({@link LLMClient}).
 *
 * <p>Retries: a failed attempt is retried once (invalid output, a timeout, a connection or server
 * error), except a failure that cannot get better by asking again: a client that is not available, or
 * HTTP 401 or 403 (the key was rejected). Then the fallback runs at once.
 *
 * <p>Diagnostics: after a fallback, {@link #getFallbackReason} names the last attempt's failure as a
 * category ("LLM unavailable: HTTP 401 (API key rejected)", "invalid output", ...). With a debug
 * output set, every failed attempt also prints its reason (for invalid output the exact rule the reply
 * broke, see {@link #describeUnparsable} and {@link #describeInvalid}) and the model's raw reply there.
 * Requests, headers and keys are never printed.
 *
 * @param <I> what the task works on, e.g. the player's text
 * @param <O> what it produces, e.g. a list of action proposals
 */
public abstract class LlmTask<I, O> {

    protected static final int MAX_ATTEMPTS = 2;

    static final String UNAVAILABLE = "LLM unavailable";
    static final String INVALID_OUTPUT = "invalid output";

    protected final LLMClient llm;
    protected final PromptBuilder prompts;
    protected final ResponseParser parser;
    protected final GameState state;
    private boolean fallbackUsed;
    private String fallbackReason;
    private PrintStream debug;

    protected LlmTask(LLMClient llm, PromptBuilder prompts, ResponseParser parser, GameState state) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.state = Objects.requireNonNull(state, "state");
    }

    /**
     * The template method: asks the model up to {@code MAX_ATTEMPTS} times and returns the first
     * well-formed, valid answer, or the deterministic fallback. A client that throws counts as a
     * failed attempt, so the game never crashes because of the model.
     */
    public final O run(I input) {
        fallbackUsed = false;
        fallbackReason = null;
        LlmRequest request = buildPrompt(input);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Outcome<O> outcome = attempt(request, input);
            if (outcome.output() != null) {
                return outcome.output();
            }
            fallbackReason = outcome.reason();
            printDebug(attempt, outcome);
            if (outcome.permanent()) {
                break;
            }
        }
        fallbackUsed = true;
        return fallback(input);
    }

    /** Whether the last {@link #run} used the fallback instead of the model's answer. */
    public boolean isFallbackUsed() {
        return fallbackUsed;
    }

    /**
     * Whether the last {@link #run} fell back because the client is not available at all (offline play), as
     * opposed to a failed call or an unusable answer.
     */
    public boolean isClientUnavailable() {
        return fallbackUsed && UNAVAILABLE.equals(fallbackReason);
    }

    /** Why the last {@link #run} fell back, as a short category; {@code null} if it did not. */
    public String getFallbackReason() {
        return fallbackUsed ? fallbackReason : null;
    }

    /** Where failed attempts are described (e.g. {@code System.err}); {@code null} turns this off. */
    public void setDebugOutput(PrintStream debug) {
        this.debug = debug;
    }

    protected abstract LlmRequest buildPrompt(I input);

    /** Turns the model's text into the output, or returns {@code null} if it is malformed. */
    protected abstract O parse(LlmResponse response);

    /** Checks a well-formed output against the input and the game. */
    protected abstract boolean isValid(O output, I input);

    /** The deterministic answer used when the model gives none. */
    protected abstract O fallback(I input);

    /**
     * Why {@link #parse} returned {@code null} for this reply, for the debug output; subclasses that know
     * which rule the reply broke say so.
     */
    protected String describeUnparsable(LlmResponse response) {
        return "the reply could not be parsed";
    }

    /** Prints one line on the debug output, if there is one (never a request, a header or a key). */
    protected void debugLine(String line) {
        if (debug != null) {
            debug.println("[LLM_DEBUG] " + getClass().getSimpleName() + " " + line);
        }
    }

    /** Why {@link #isValid} rejected the output, for the debug output; subclasses can be more precise. */
    protected String describeInvalid(O output, I input) {
        return "the answer failed validation";
    }

    /**
     * The result of one attempt: the output, or the failure category, the model's reply if any, a detail,
     * and whether asking again is pointless.
     */
    private record Outcome<O>(O output, String reason, String reply, String detail, boolean permanent) {

        static <O> Outcome<O> failed(String reason, String reply, String detail) {
            return new Outcome<>(null, reason, reply, detail, false);
        }

        static <O> Outcome<O> permanentlyFailed(String reason, String detail) {
            return new Outcome<>(null, reason, null, detail, true);
        }
    }

    /** HTTP 401 and 403 (the client's categories "HTTP 401 ..." and "HTTP 403 ..."): a rejected key stays rejected. */
    static boolean isPermanent(String error) {
        return error != null && (error.startsWith("HTTP 401") || error.startsWith("HTTP 403"));
    }

    private Outcome<O> attempt(LlmRequest request, I input) {
        String reply = null;
        try {
            if (!llm.isAvailable()) {
                return Outcome.permanentlyFailed(UNAVAILABLE, "the " + llm.providerName() + " client is not available");
            }
            LlmResponse response = llm.complete(request);
            if (response == null) {
                return Outcome.failed(UNAVAILABLE + ": no response", null, "the client returned nothing");
            }
            if (!response.isOk()) {
                return isPermanent(response.getError())
                        ? Outcome.permanentlyFailed(UNAVAILABLE + ": " + response.getError(), response.getError())
                        : Outcome.failed(UNAVAILABLE + ": " + response.getError(), null, response.getError());
            }
            reply = response.getText();
            O output = parse(response);
            if (output == null) {
                return Outcome.failed(INVALID_OUTPUT, reply, describeUnparsable(response));
            }
            if (!isValid(output, input)) {
                return Outcome.failed(INVALID_OUTPUT, reply, describeInvalid(output, input));
            }
            return new Outcome<>(output, null, reply, null, false);
        } catch (RuntimeException clientOrParserFailure) {
            String failure = clientOrParserFailure.getClass().getSimpleName();
            return reply == null
                    ? Outcome.failed(UNAVAILABLE + ": client error", null, failure)
                    : Outcome.failed(INVALID_OUTPUT, reply, "parsing failed with " + failure);
        }
    }

    private void printDebug(int attempt, Outcome<O> outcome) {
        if (debug == null) {
            return;
        }
        debug.println("[LLM_DEBUG] " + getClass().getSimpleName() + " attempt " + attempt + "/" + MAX_ATTEMPTS
                + " failed: " + outcome.reason() + " - " + outcome.detail() + (outcome.permanent() ? " (not retried)" : ""));
        debug.println("[LLM_DEBUG] reply: " + (outcome.reply() == null ? "(none)" : outcome.reply()));
    }
}
