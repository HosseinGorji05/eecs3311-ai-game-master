package llm;

/**
 * The alternative provider: Groq's OpenAI-compatible API running Llama 3.3 70B Versatile.
 *
 * <p>Pattern role: <b>ConcreteStrategy</b>; it only passes its URL, model and key to
 * {@link OpenAiCompatibleClient}.
 */
public class GroqClient extends OpenAiCompatibleClient {

    public static final String BASE_URL = "https://api.groq.com/openai/v1";
    public static final String MODEL = "llama-3.3-70b-versatile";

    public GroqClient(String apiKey) {
        super(BASE_URL, MODEL, apiKey);
    }

    @Override
    public String providerName() {
        return "groq";
    }
}
