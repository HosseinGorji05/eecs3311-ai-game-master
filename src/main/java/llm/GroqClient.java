package llm;

/**
 * The alternative provider: Groq's OpenAI-compatible API, by default with OpenAI's open-weight
 * {@code openai/gpt-oss-20b} model.
 *
 * <p>gpt-oss is a reasoning model; Groq returns its reasoning in a separate {@code message.reasoning}
 * field, so only the final {@code message.content} is read (Groq's reasoning documentation).
 *
 * <p>Pattern role: <b>ConcreteStrategy</b>; it only passes its URL, model and key to
 * {@link OpenAiCompatibleClient}.
 */
public class GroqClient extends OpenAiCompatibleClient {

    public static final String BASE_URL = "https://api.groq.com/openai/v1";
    /** Supports JSON object mode (Groq's model and structured-outputs documentation). */
    public static final String DEFAULT_MODEL = "openai/gpt-oss-20b";
    /** The configuration setting that overrides {@link #DEFAULT_MODEL}. */
    public static final String MODEL_SETTING = "GROQ_MODEL";

    public GroqClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    /** @throws IllegalArgumentException if the model is blank */
    public GroqClient(String apiKey, String model) {
        super(BASE_URL, model, apiKey);
    }

    @Override
    public String providerName() {
        return "groq";
    }

    @Override
    protected String modelSetting() {
        return MODEL_SETTING;
    }
}
