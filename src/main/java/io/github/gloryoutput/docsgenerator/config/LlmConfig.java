package io.github.gloryoutput.docsgenerator.config;

import io.github.gloryoutput.docsgenerator.summarizer.LlmClient;
import io.github.gloryoutput.docsgenerator.summarizer.anthropic.AnthropicLlmClient;
import io.github.gloryoutput.docsgenerator.summarizer.gemini.GeminiLlmClient;
import io.github.gloryoutput.docsgenerator.summarizer.ollama.OllamaLlmClient;
import io.github.gloryoutput.docsgenerator.summarizer.openai.OpenAiLlmClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 클라이언트 빈 등록 설정
 *
 * <p>app.llm.enabled=true일 때만 활성화되며,
 * app.llm.provider 값(openai, anthropic, gemini, ollama)에 따라
 * 적절한 LlmClient 구현체를 등록합니다.
 * enabled=false(기본값)이면 LlmClient 빈이 생성되지 않아
 * LLM 없이도 전체 파이프라인이 정상 동작합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Configuration
@ConditionalOnProperty(name = "app.llm.enabled", havingValue = "true")
@Slf4j
public class LlmConfig {
    @Value("${app.llm.api-url:}")
    private String apiUrl;
    @Value("${app.llm.api-key:}")
    private String apiKey;
    @Value("${app.llm.model:}")
    private String model;

    @Bean
    @ConditionalOnProperty(name = "app.llm.provider", havingValue = "openai")
    public LlmClient openAiLlmClient() {
        String resolvedModel = (model != null && !model.isBlank()) ? model : "gpt-4o-mini";
        log.info("OpenAI LlmClient 빈 등록 - model: {}", resolvedModel);
        return new OpenAiLlmClient(apiUrl, apiKey, resolvedModel);
    }

    @Bean
    @ConditionalOnProperty(name = "app.llm.provider", havingValue = "anthropic")
    public LlmClient anthropicLlmClient() {
        String resolvedUrl = (apiUrl != null && !apiUrl.isBlank()) ? apiUrl : "https://api.anthropic.com";
        String resolvedModel = (model != null && !model.isBlank()) ? model : "claude-sonnet-4-20250514";
        log.info("Anthropic LlmClient 빈 등록 - model: {}", resolvedModel);
        return new AnthropicLlmClient(resolvedUrl, apiKey, resolvedModel);
    }

    @Bean
    @ConditionalOnProperty(name = "app.llm.provider", havingValue = "gemini")
    public LlmClient geminiLlmClient() {
        String resolvedUrl = (apiUrl != null && !apiUrl.isBlank()) ? apiUrl : "https://generativelanguage.googleapis.com";
        String resolvedModel = (model != null && !model.isBlank()) ? model : "gemini-2.0-flash";
        log.info("Gemini LlmClient 빈 등록 - model: {}", resolvedModel);
        return new GeminiLlmClient(resolvedUrl, apiKey, resolvedModel);
    }

    @Bean
    @ConditionalOnProperty(name = "app.llm.provider", havingValue = "ollama")
    public LlmClient ollamaLlmClient() {
        String resolvedUrl = (apiUrl != null && !apiUrl.isBlank()) ? apiUrl : "http://localhost:11434";
        String resolvedModel = (model != null && !model.isBlank()) ? model : "deepseek-r1";
        log.info("Ollama LlmClient 빈 등록 - model: {}, url: {}", resolvedModel, resolvedUrl);
        return new OllamaLlmClient(resolvedUrl, resolvedModel);
    }
}
