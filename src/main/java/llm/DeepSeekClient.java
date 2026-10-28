package llm;

/**
 * The primary provider: DeepSeek's OpenAI-compatible API, by default with the DeepSeek-V4.1-Flash
 * model.
 *
 * <p>Pattern role: <b>ConcreteStrategy</b>; it only passes its URL, model and key to
 * {@link OpenAiCompatibleClient}.
 */
public class DeepSeekClient extends OpenAiCompatibleClient {

    /** From DeepSeek's API documentation (OpenAI format). */
    public static final String BASE_URL = "https://api.deepseek.com";
    /** The API model ID of DeepSeek-V4.1-Flash (Stage 1 report, DeepSeek's API documentation). */
    public static final String DEFAULT_MODEL = "deepseek-flash";
    /** The configuration setting that overrides {@link #DEFAULT_MODEL}. */
    public static final String MODEL_SETTING = "DEEPSEEK_MODEL";

    public DeepSeekClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    /** @throws IllegalArgumentException if the model is blank */
    public DeepSeekClient(String apiKey, String model) {
        super(BASE_URL, model, apiKey);
    }

    @Override
    public String providerName() {
        return "deepseek";
    }

    @Override
    protected String modelSetting() {
        return MODEL_SETTING;
    }
}
