package io.github.gloryoutput.docsgenerator.summarizer.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gloryoutput.docsgenerator.summarizer.LlmClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Google Gemini API를 사용하는 LlmClient 구현체
 *
 * <p>LlmConfig에서 app.llm.enabled=true, app.llm.provider=gemini일 때 빈으로 등록됩니다.
 * Gemini generateContent API를 사용하여 요청을 보내고 응답을 파싱합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Slf4j
public class GeminiLlmClient implements LlmClient {
    private static final double TEMPERATURE = 0.3;
    private static final int MAX_OUTPUT_TOKENS = 4096;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiKey;
    private final String model;
    private final RestClient restClient;

    public GeminiLlmClient(String apiUrl, String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.restClient = RestClient.builder()
                .baseUrl(apiUrl)
                .build();
        log.info("Gemini LLM Client 초기화 완료 - model: {}, api-url: {}", model, apiUrl);
    }

    /**
     * Gemini generateContent API를 호출하여 응답을 반환합니다.
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
                    .uri("/v1beta/models/{model}:generateContent?key={apiKey}", model, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
            return extractContent(responseBody);
        } catch (Exception e) {
            throw new RuntimeException("Gemini API 호출 중 오류 발생", e);
        }
    }

    /**
     * Gemini generateContent 요청 본문을 구성합니다.
     */
    private String buildRequestBody(String systemPrompt, String userPrompt) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        // systemInstruction 설정
        ObjectNode systemInstruction = root.putObject("systemInstruction");
        ArrayNode systemParts = systemInstruction.putArray("parts");
        ObjectNode systemTextPart = systemParts.addObject();
        systemTextPart.put("text", systemPrompt);
        // contents 설정
        ArrayNode contents = root.putArray("contents");
        ObjectNode userContent = contents.addObject();
        userContent.put("role", "user");
        ArrayNode userParts = userContent.putArray("parts");
        ObjectNode userTextPart = userParts.addObject();
        userTextPart.put("text", userPrompt);
        // generationConfig 설정
        ObjectNode generationConfig = root.putObject("generationConfig");
        generationConfig.put("temperature", TEMPERATURE);
        generationConfig.put("maxOutputTokens", MAX_OUTPUT_TOKENS);
        return objectMapper.writeValueAsString(root);
    }

    /**
     * Gemini 응답에서 텍스트 content를 추출합니다.
     *
     * <p>토큰 사용량 정보가 있으면 로그로 출력합니다.</p>
     */
    private String extractContent(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode usageMetadata = root.get("usageMetadata");
        if (usageMetadata != null) {
            log.info("LLM 토큰 사용량 - prompt: {}, completion: {}, total: {}",
                    usageMetadata.path("promptTokenCount").asInt(0),
                    usageMetadata.path("candidatesTokenCount").asInt(0),
                    usageMetadata.path("totalTokenCount").asInt(0));
        }
        JsonNode candidates = root.path("candidates");
        if (candidates.isArray() && !candidates.isEmpty()) {
            return candidates.get(0).path("content").path("parts").get(0).path("text").asText();
        }
        throw new RuntimeException("Gemini 응답에서 content를 찾을 수 없습니다: " + responseBody);
    }
}
