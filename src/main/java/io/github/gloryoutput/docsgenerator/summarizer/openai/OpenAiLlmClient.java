package io.github.gloryoutput.docsgenerator.summarizer.openai;

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
 * OpenAI Chat Completions API를 사용하는 LlmClient 구현체
 *
 * <p>app.llm.enabled=true일 때만 빈으로 등록됩니다.
 * RestClient를 사용하여 OpenAI 호환 API에 요청을 보내고 응답을 파싱합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Component
@ConditionalOnProperty(name = "app.llm.provider", havingValue = "openai")
@Slf4j
public class OpenAiLlmClient implements LlmClient {
    private static final double TEMPERATURE = 0.3;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient.Builder restClientBuilder;
    @Value("${app.llm.api-url:}")
    private String apiUrl;
    @Value("${app.llm.api-key:}")
    private String apiKey;
    @Value("${app.llm.model:gpt-4o-mini}")
    private String model;
    private RestClient restClient;

    public OpenAiLlmClient(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    @PostConstruct
    void init() {
        this.restClient = restClientBuilder
                .baseUrl(apiUrl)
                .build();
        log.info("OpenAI LLM Client 초기화 완료 - model: {}, api-url: {}", model, apiUrl);
    }

    /**
     * OpenAI Chat Completions API를 호출하여 응답을 반환합니다.
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
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
            return extractContent(responseBody);
        } catch (Exception e) {
            throw new RuntimeException("OpenAI API 호출 중 오류 발생", e);
        }
    }

    /**
     * OpenAI Chat Completions 요청 본문을 구성합니다.
     */
    private String buildRequestBody(String systemPrompt, String userPrompt) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("temperature", TEMPERATURE);
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
     * OpenAI 응답에서 assistant 메시지 content를 추출합니다.
     *
     * <p>토큰 사용량 정보가 있으면 로그로 출력합니다.</p>
     */
    private String extractContent(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode usage = root.get("usage");
        if (usage != null) {
            log.info("LLM 토큰 사용량 - prompt: {}, completion: {}, total: {}",
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0),
                    usage.path("total_tokens").asInt(0));
        }
        return root.path("choices").get(0).path("message").path("content").asText();
    }
}
