package llm;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Queue;

/**
 * An {@link LLMClient} without a network: it answers each request with the next canned response,
 * in order. It is available while it has answers left, so with no responses at all it makes every
 * task use its deterministic fallback (offline play).
 *
 * <p>Pattern role: <b>ConcreteStrategy</b>.
 */
public class ScriptedLLMClient implements LLMClient {

    private final Queue<String> responses;

    public ScriptedLLMClient(List<String> responses) {
        Objects.requireNonNull(responses, "responses");
        this.responses = new ArrayDeque<>();
        for (String response : responses) {
            this.responses.add(Objects.requireNonNull(response, "response"));
        }
    }

    /** The next canned response, or a failed response if none are left. */
    @Override
    public LlmResponse complete(LlmRequest request) {
        Objects.requireNonNull(request, "request");
        String next = responses.poll();
        return next == null
                ? new LlmResponse(null, false, "No scripted response left")
                : new LlmResponse(next, true, null);
    }

    @Override
    public boolean isAvailable() {
        return !responses.isEmpty();
    }

    @Override
    public String providerName() {
        return "scripted";
    }
}
