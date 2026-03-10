package io.github.gloryoutput.docsgenerator.summarizer.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gloryoutput.docsgenerator.summarizer.LlmClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Anthropic Claude Messages API를 사용하는 LlmClient 구현체
 *
 * <p>app.llm.provider=anthropic일 때 빈으로 등록됩니다.
 * Anthropic Messages API(/v1/messages) 형식으로 요청을 보내고 응답을 파싱합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Component
@ConditionalOnProperty(name = "app.llm.provider", havingValue = "anthropic")
@Slf4j
public class AnthropicLlmClient implements LlmClient {
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_TOKENS = 4096;
    private static final double TEMPERATURE = 0.3;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Value("${app.llm.api-url:https://api.anthropic.com}")
    private String apiUrl;
    @Value("${app.llm.api-key:}")
    private String apiKey;
    @Value("${app.llm.model:claude-sonnet-4-20250514}")
    private String model;
    private RestClient restClient;

    @PostConstruct
    void init() {
        this.restClient = RestClient.builder()
                .baseUrl(apiUrl)
                .build();
        log.info("Anthropic LLM Client 초기화 완료 - model: {}, api-url: {}", model, apiUrl);
    }

    /**
     * Anthropic Messages API를 호출하여 응답을 반환합니다.
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
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
            return extractContent(responseBody);
        } catch (Exception e) {
            throw new RuntimeException("Anthropic API 호출 중 오류 발생", e);
        }
    }

    /**
     * Anthropic Messages API 요청 본문을 구성합니다.
     */
    private String buildRequestBody(String systemPrompt, String userPrompt) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", MAX_TOKENS);
        root.put("temperature", TEMPERATURE);
        root.put("system", systemPrompt);
        ArrayNode messages = root.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        return objectMapper.writeValueAsString(root);
    }

    /**
     * Anthropic 응답에서 텍스트 content를 추출합니다.
     *
     * <p>토큰 사용량 정보가 있으면 로그로 출력합니다.</p>
     */
    private String extractContent(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode usage = root.get("usage");
        if (usage != null) {
            log.info("LLM 토큰 사용량 - input: {}, output: {}",
                    usage.path("input_tokens").asInt(0),
                    usage.path("output_tokens").asInt(0));
        }
        JsonNode content = root.path("content");
        if (content.isArray() && !content.isEmpty()) {
            return content.get(0).path("text").asText();
        }
        throw new RuntimeException("Anthropic 응답에서 content를 찾을 수 없습니다: " + responseBody);
    }
}
