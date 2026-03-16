package io.github.gloryoutput.docsgenerator.summarizer.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gloryoutput.docsgenerator.summarizer.LlmClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.time.Duration;

/**
 * Ollama OpenAI 호환 API를 사용하는 LlmClient 구현체
 *
 * <p>LlmConfig에서 app.llm.enabled=true, app.llm.provider=ollama일 때 빈으로 등록됩니다.
 * Ollama는 OpenAI 호환 API(/v1/chat/completions)를 제공하므로 동일한 요청 구조를 사용합니다.
 * API 키 없이 로컬에서 동작하며, 기본 모델은 deepseek-r1입니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Slf4j
public class OllamaLlmClient implements LlmClient {
    private static final double TEMPERATURE = 0.3;
    /** deepseek-r1 등 대형 프롬프트 처리를 위한 컨텍스트 윈도우 크기 */
    private static final int NUM_CTX = 32768;
    /** LLM 응답 대기 타임아웃 (10분) */
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(10);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String model;
    private final RestClient restClient;

    public OllamaLlmClient(String apiUrl, String model) {
        this.model = model;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder()
                .baseUrl(apiUrl)
                .requestFactory(factory)
                .build();
        log.info("Ollama LLM Client 초기화 완료 - model: {}, api-url: {}, num_ctx: {}, timeout: {}",
                model, apiUrl, NUM_CTX, READ_TIMEOUT);
    }

    /**
     * Ollama OpenAI 호환 API를 호출하여 응답을 반환합니다.
     *
     * @param systemPrompt 시스템 프롬프트
     * @param userPrompt 사용자 프롬프트
     * @return LLM 응답 텍스트
     */
    @Override
    public String chat(String systemPrompt, String userPrompt) {
        try {
            String requestBody = buildRequestBody(systemPrompt, userPrompt);
            String responseBody = restClient.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
            return extractContent(responseBody);
        } catch (Exception e) {
            throw new RuntimeException("Ollama API 호출 중 오류 발생", e);
        }
    }

    /**
     * OpenAI 호환 Chat Completions 요청 본문을 구성합니다.
     */
    private String buildRequestBody(String systemPrompt, String userPrompt) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("temperature", TEMPERATURE);
        ObjectNode options = root.putObject("options");
        options.put("num_ctx", NUM_CTX);
        ArrayNode messages = root.putArray("messages");
        ObjectNode systemMsg = messages.addObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        return objectMapper.writeValueAsString(root);
    }

    /**
     * 응답에서 assistant 메시지 content를 추출합니다.
     *
     * <p>토큰 사용량 정보가 있으면 로그로 출력합니다.</p>
     */
    private String extractContent(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode usage = root.get("usage");
        if (usage != null) {
            log.info("Ollama 토큰 사용량 - prompt: {}, completion: {}, total: {}",
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0),
                    usage.path("total_tokens").asInt(0));
        }
        return root.path("choices").get(0).path("message").path("content").asText();
    }
}
