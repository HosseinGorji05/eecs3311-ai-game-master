package agent;

import engine.core.GameState;
import llm.LLMClient;
import llm.LlmRequest;
import llm.LlmResponse;

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
 * @param <I> what the task works on, e.g. the player's text
 * @param <O> what it produces, e.g. a list of action proposals
 */
public abstract class LlmTask<I, O> {

    protected static final int MAX_ATTEMPTS = 2;

    protected final LLMClient llm;
    protected final PromptBuilder prompts;
    protected final ResponseParser parser;
    protected final GameState state;
    private boolean fallbackUsed;

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
        LlmRequest request = buildPrompt(input);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            O output = attempt(request, input);
            if (output != null) {
                return output;
            }
        }
        fallbackUsed = true;
        return fallback(input);
    }

    /** Whether the last {@link #run} used the fallback instead of the model's answer. */
    public boolean isFallbackUsed() {
        return fallbackUsed;
    }

    protected abstract LlmRequest buildPrompt(I input);

    /** Turns the model's text into the output, or returns {@code null} if it is malformed. */
    protected abstract O parse(LlmResponse response);

    /** Checks a well-formed output against the input and the game. */
    protected abstract boolean isValid(O output, I input);

    /** The deterministic answer used when the model gives none. */
    protected abstract O fallback(I input);

    private O attempt(LlmRequest request, I input) {
        try {
            if (!llm.isAvailable()) {
                return null;
            }
            LlmResponse response = llm.complete(request);
            if (response == null || !response.isOk()) {
                return null;
            }
            O output = parse(response);
            return output != null && isValid(output, input) ? output : null;
        } catch (RuntimeException clientOrParserFailure) {
            return null;
        }
    }
}
