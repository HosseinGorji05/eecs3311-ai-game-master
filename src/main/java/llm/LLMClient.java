package llm;

/**
 * Access to a language model. Every LLM task depends only on this interface, so the provider can
 * be swapped (or replaced by {@link ScriptedLLMClient} in tests and offline play) without changing
 * game code.
 *
 * <p>Pattern roles: <b>Strategy</b> (and the Adapter's <b>Target</b> for the HTTP clients).
 */
public interface LLMClient {

    LlmResponse complete(LlmRequest request);

    /** Whether the model can be asked right now; tasks fall back to deterministic code if not. */
    boolean isAvailable();

    String providerName();
}
