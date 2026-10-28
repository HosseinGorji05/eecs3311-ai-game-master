package llm;

/**
 * The primary provider: DeepSeek's OpenAI-compatible API with the DeepSeek-V4.1-Flash model.
 *
 * <p>Pattern role: <b>ConcreteStrategy</b>; it only passes its URL, model and key to
 * {@link OpenAiCompatibleClient}.
 */
public class DeepSeekClient extends OpenAiCompatibleClient {

    /** From DeepSeek's API documentation (OpenAI format). */
    public static final String BASE_URL = "https://api.deepseek.com";
    /** The API model ID of DeepSeek-V4.1-Flash (Stage 1 report, DeepSeek's API documentation). */
    public static final String MODEL = "deepseek-flash";

    public DeepSeekClient(String apiKey) {
        super(BASE_URL, MODEL, apiKey);
    }

    @Override
    public String providerName() {
        return "deepseek";
    }
}
