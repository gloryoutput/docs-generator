package io.github.gloryoutput.docsgenerator.summarizer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM을 활용한 변경 설명 압축 서비스
 *
 * <p>여러 파일에 걸친 기능별 변경 내용을 카테고리 병합 → LLM 압축 순서로 처리합니다.
 * 같은 키워드를 공유하는 카테고리(예: "API (scout)", "비즈니스 로직 (scout)")를
 * 하나의 카테고리("scout")로 병합한 뒤, LLM으로 각 카테고리 내 항목을 압축합니다.</p>
 *
 * <p>LlmClient 빈이 없으면 카테고리 병합 + 중복 제거만 적용합니다(fallback).</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@Slf4j
public class LlmDescriptionCompressorService {
    private static final String SYSTEM_PROMPT =
            "당신은 IT 변경 보고서 작성 전문가입니다.\n" +
            "아래 '기능별 변경 내용'은 여러 파일을 수정하여 발생한 변경 사항을 기능별로 정리한 것입니다.\n" +
            "이것을 비개발자(경영진, 고객사)가 읽을 보고서에 들어갈 **핵심 변경 요약**으로 대폭 압축해 주세요.\n\n" +
            "## 압축 규칙\n" +
            "1. **반드시 원본 항목 수의 1/5 이하로 압축하세요** (예: 항목 20개 → 최대 4개)\n" +
            "2. 연관된 여러 카테고리를 하나의 상위 카테고리로 통합하세요\n" +
            "   예: 'scout', 'evaluation', 'observation' → '스카우트 평가 관리'\n" +
            "3. 각 카테고리 내에서 모든 유사 항목을 **1개의 핵심 문장**으로 병합하세요\n" +
            "4. 기술 용어(Repository, Service, Controller, Entity, 필드, 메서드 등)를 사용하지 마세요\n" +
            "5. 파일명, 클래스명, 패키지 경로를 언급하지 마세요\n" +
            "6. '~기능 추가', '~처리 방식 개선', '~관리 기능 확장' 형태의 간결한 문장으로 작성\n" +
            "7. 카테고리명은 비개발자가 이해할 수 있는 업무 관점의 한국어 이름으로 변환하세요\n" +
            "8. 최종 카테고리 수는 **최대 5개**를 넘지 마세요\n" +
            "9. 항목이 1~2개뿐인 소규모 카테고리는 다른 카테고리에 병합하거나 '기타 개선'에 통합하세요\n\n" +
            "## 응답 형식\n" +
            "반드시 아래 JSON 객체 형식으로만 응답하세요. 다른 텍스트를 포함하지 마세요.\n" +
            "{\n" +
            "  \"카테고리명\": [\"변경 요약 1\"],\n" +
            "  \"카테고리명\": [\"변경 요약 1\"]\n" +
            "}";
    /** 카테고리에서 괄호 안 키워드를 추출하는 패턴: "비즈니스 로직 (scout)" → "scout" */
    private static final Pattern KEYWORD_PATTERN = Pattern.compile("\\(([^)]+)\\)");
    private static final DateTimeFormatter FILE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Value("${app.llm.prompt-output-dir:./llm-prompts}")
    private String promptOutputDir;

    public LlmDescriptionCompressorService(@Autowired(required = false) LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 기능별 변경 내용을 카테고리 병합 후 LLM으로 압축합니다.
     *
     * <p>처리 순서:
     * 1) 같은 키워드를 공유하는 카테고리 병합 (예: "API (scout)" + "비즈니스 로직 (scout)" → "scout")
     * 2) 병합된 카테고리 내 중복 항목 제거
     * 3) LLM으로 각 카테고리 내 항목을 핵심 요약으로 압축
     * 4) LLM 없으면 1~2단계 결과를 그대로 반환 (fallback)</p>
     *
     * @param changesByFeature 기능 영역 → 변경 설명 목록
     * @return 카테고리 → 압축된 변경 요약 목록
     */
    /** fallback 시 최대 카테고리 수 */
    private static final int MAX_FALLBACK_CATEGORIES = 5;
    /** fallback 시 카테고리당 최대 항목 수 */
    private static final int MAX_FALLBACK_ITEMS_PER_CATEGORY = 2;

    public Map<String, List<String>> compress(Map<String, Set<String>> changesByFeature) {
        if (changesByFeature == null || changesByFeature.isEmpty()) {
            return Map.of();
        }
        // 1단계: 같은 키워드의 카테고리 병합 + 중복 제거
        Map<String, Set<String>> merged = mergeCategories(changesByFeature);
        Map<String, List<String>> fallback = truncateFallback(toListMap(merged));
        if (llmClient == null) {
            log.debug("LLM 비활성화 - 카테고리 병합 결과 반환 ({}개 → {}개 카테고리)",
                    changesByFeature.size(), fallback.size());
            return fallback;
        }
        // 2단계: LLM 압축
        int totalItems = fallback.values().stream().mapToInt(List::size).sum();
        try {
            String userPrompt = buildUserPrompt(merged);
            String timestamp = LocalDateTime.now().format(FILE_FORMATTER);
            savePromptFile(timestamp, "compress_input", SYSTEM_PROMPT + "\n\n---\n\n" + userPrompt);
            log.info("LLM 변경 설명 압축 시작 - {}개 카테고리, {}건 항목", merged.size(), totalItems);
            String result = llmClient.chat(SYSTEM_PROMPT, userPrompt);
            savePromptFile(timestamp, "compress_output", result);
            Map<String, List<String>> compressed = parseResponse(result);
            if (compressed == null || compressed.isEmpty()) {
                log.warn("LLM 압축 결과가 비어있음 - 카테고리 병합 결과 반환");
                return fallback;
            }
            int compressedItems = compressed.values().stream().mapToInt(List::size).sum();
            log.info("LLM 변경 설명 압축 완료 - {}개 카테고리 {}건 → {}개 카테고리 {}건",
                    fallback.size(), totalItems, compressed.size(), compressedItems);
            return compressed;
        } catch (Exception e) {
            log.warn("LLM 변경 설명 압축 실패 - 카테고리 병합 결과 반환. 원인: {}", e.getMessage());
            return fallback;
        }
    }

    /**
     * 같은 키워드를 공유하는 카테고리를 병합합니다.
     *
     * <p>카테고리명에서 괄호 안 키워드를 추출하여 그룹핑합니다.
     * 예: "API (scout)" + "비즈니스 로직 (scout)" + "데이터 모델 (scout)" → "scout"
     * 키워드가 없는 카테고리(예: "설정", "공통 모듈")는 원래 이름을 유지합니다.</p>
     */
    private Map<String, Set<String>> mergeCategories(Map<String, Set<String>> changesByFeature) {
        Map<String, Set<String>> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : changesByFeature.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            String categoryKey = extractCategoryKey(entry.getKey());
            merged.computeIfAbsent(categoryKey, k -> new LinkedHashSet<>()).addAll(entry.getValue());
        }
        log.debug("카테고리 병합: {}개 → {}개", changesByFeature.size(), merged.size());
        return merged;
    }

    /**
     * 카테고리명에서 병합 키를 추출합니다.
     *
     * <p>"비즈니스 로직 (scout)" → "scout", "설정" → "설정"</p>
     */
    private String extractCategoryKey(String categoryName) {
        Matcher matcher = KEYWORD_PATTERN.matcher(categoryName);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return categoryName;
    }

    /**
     * fallback 결과를 최대 카테고리 수와 카테고리당 항목 수로 제한합니다.
     *
     * <p>항목이 많은 카테고리를 우선 유지하고, 초과 카테고리의 항목은
     * "기타 개선" 카테고리로 통합합니다.</p>
     */
    private Map<String, List<String>> truncateFallback(Map<String, List<String>> original) {
        if (original.size() <= MAX_FALLBACK_CATEGORIES) {
            // 카테고리 수 제한 내: 항목 수만 제한
            Map<String, List<String>> result = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : original.entrySet()) {
                List<String> items = entry.getValue();
                result.put(entry.getKey(), items.size() > MAX_FALLBACK_ITEMS_PER_CATEGORY
                        ? items.subList(0, MAX_FALLBACK_ITEMS_PER_CATEGORY)
                        : items);
            }
            return result;
        }
        // 카테고리를 항목 수 기준 내림차순 정렬하여 상위 카테고리 유지
        List<Map.Entry<String, List<String>>> sorted = new ArrayList<>(original.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));
        Map<String, List<String>> result = new LinkedHashMap<>();
        List<String> etcItems = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<String, List<String>> entry = sorted.get(i);
            if (i < MAX_FALLBACK_CATEGORIES - 1) {
                List<String> items = entry.getValue();
                result.put(entry.getKey(), items.size() > MAX_FALLBACK_ITEMS_PER_CATEGORY
                        ? items.subList(0, MAX_FALLBACK_ITEMS_PER_CATEGORY)
                        : items);
            } else {
                // 초과 카테고리의 첫 번째 항목만 수집
                if (!entry.getValue().isEmpty()) {
                    etcItems.add(entry.getValue().get(0));
                }
            }
        }
        if (!etcItems.isEmpty()) {
            result.put("기타 개선", etcItems.size() > MAX_FALLBACK_ITEMS_PER_CATEGORY
                    ? etcItems.subList(0, MAX_FALLBACK_ITEMS_PER_CATEGORY)
                    : etcItems);
        }
        return result;
    }

    private Map<String, List<String>> toListMap(Map<String, Set<String>> setMap) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : setMap.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }
        return result;
    }

    private String buildUserPrompt(Map<String, Set<String>> mergedCategories) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 기능별 변경 내용\n\n");
        for (Map.Entry<String, Set<String>> entry : mergedCategories.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            sb.append("### ").append(entry.getKey()).append("\n");
            for (String desc : entry.getValue()) {
                sb.append("- ").append(desc).append("\n");
            }
            sb.append("\n");
        }
        sb.append("---\n위 내용을 카테고리별 핵심 변경 요약 JSON 객체로 압축해 주세요.");
        return sb.toString();
    }

    private Map<String, List<String>> parseResponse(String response) {
        try {
            String json = extractJson(response);
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("LLM 압축 응답 JSON 파싱 실패: {}", e.getMessage());
            return null;
        }
    }

    private String extractJson(String response) {
        String trimmed = response.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int objStart = trimmed.indexOf('{');
        int objEnd = trimmed.lastIndexOf('}');
        if (objStart >= 0 && objEnd > objStart) {
            return trimmed.substring(objStart, objEnd + 1);
        }
        return trimmed;
    }

    private void savePromptFile(String timestamp, String suffix, String content) {
        try {
            Path dir = Paths.get(promptOutputDir);
            Files.createDirectories(dir);
            Path filePath = dir.resolve("llm_" + suffix + "_" + timestamp + ".md");
            Files.writeString(filePath, content, StandardCharsets.UTF_8);
            log.debug("LLM 파일 저장: {}", filePath.toAbsolutePath());
        } catch (IOException e) {
            log.warn("LLM 파일 저장 실패: {}", e.getMessage());
        }
    }
}
