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
            "이것을 비개발자(경영진, 고객사)가 읽을 보고서에 들어갈 **핵심 변경 요약**으로 압축해 주세요.\n\n" +
            "## 핵심 원칙: 같은 기능에 대한 여러 레이어 변경은 반드시 하나로 통합하세요\n" +
            "예시: 'weather 기능 추가', 'weather 조회 기능', 'weather response 추가', 'weather 정보 관리' 등\n" +
            "12개 항목 → '날씨(weather) 관리 기능 신규 추가' 1개로 압축\n\n" +
            "## 압축 규칙\n" +
            "1. **카테고리당 최대 1~2개 문장**: 같은 키워드에 대한 항목이 여러 개면 반드시 하나로 통합\n" +
            "2. 기술 용어(Repository, Service, Controller, Entity, 필드, 메서드 등)를 사용하지 마세요\n" +
            "3. 파일명, 클래스명, 패키지 경로를 언급하지 마세요\n" +
            "4. '~기능 추가', '~처리 방식 개선', '~관리 기능 확장' 형태의 간결한 문장으로 작성\n" +
            "5. 카테고리명은 비개발자가 이해할 수 있는 업무 관점의 한국어 이름으로 변환하세요\n" +
            "   예: 'scout' → '스카우트 관리', 'evaluation' → '선수 평가', 'weather' → '날씨 정보'\n" +
            "6. 항목이 모두 다른 카테고리에 병합되어 비게 된 카테고리는 제외하세요\n" +
            "7. '조회 기능 추가', '관리 기능 추가', '데이터 관리 추가'가 모두 있으면 → '관리 기능 신규 추가'로 통합\n" +
            "8. 카테고리명에 '/'가 포함된 경우(예: 'scout/weather'), 이는 상위 기능의 하위 기능을 나타냅니다.\n" +
            "   반드시 상위 기능의 맥락에서 하위 기능을 서술하세요.\n" +
            "   예: 'scout/weather' 카테고리 → 카테고리명을 '스카우트 관리'로 하고, '스카우팅 스케줄에서 날씨 입력 기능 추가' 형태로 서술\n" +
            "   나쁜 예: '날씨 관리 기능 신규 추가' (하위 기능을 독립 기능처럼 서술)\n" +
            "   좋은 예: '스카우팅 스케줄에서 날씨 입력 기능 추가' (상위 기능 맥락에서 서술)\n" +
            "   '/' 앞의 상위 기능 카테고리에 해당 항목을 포함시키세요. 하위 기능만의 별도 카테고리를 만들지 마세요.\n\n" +
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
     * 기능별 변경 내용을 카테고리 병합 후 압축합니다.
     *
     * <p>처리 순서:
     * 1) 같은 키워드를 공유하는 카테고리 병합 (예: "API (scout)" + "비즈니스 로직 (scout)" → "scout")
     * 2) 병합된 카테고리 내 중복 항목 제거
     * 3) 키워드 기반 요약 압축 (같은 키워드의 유사 항목을 1~2문장으로 통합)
     * 4) LLM이 있으면 추가로 자연어 정제, 없으면 3단계 결과 반환</p>
     *
     * @param changesByFeature 기능 영역 → 변경 설명 목록
     * @return 카테고리 → 압축된 변경 요약 목록
     */
    public Map<String, List<String>> compress(Map<String, Set<String>> changesByFeature) {
        if (changesByFeature == null || changesByFeature.isEmpty()) {
            return Map.of();
        }
        // 1단계: 같은 키워드의 카테고리 병합 + 중복 제거
        Map<String, Set<String>> merged = mergeCategories(changesByFeature);
        // 2단계: 키워드 기반 요약 압축 (LLM 없어도 동작)
        Map<String, List<String>> summarized = summarizeByKeyword(merged);
        if (llmClient == null) {
            int totalBefore = merged.values().stream().mapToInt(Set::size).sum();
            int totalAfter = summarized.values().stream().mapToInt(List::size).sum();
            log.debug("LLM 비활성화 - 키워드 기반 요약 적용 ({}건 → {}건)", totalBefore, totalAfter);
            return summarized;
        }
        // 3단계: LLM 추가 압축
        int totalItems = summarized.values().stream().mapToInt(List::size).sum();
        try {
            String userPrompt = buildUserPrompt(merged);
            String timestamp = LocalDateTime.now().format(FILE_FORMATTER);
            savePromptFile(timestamp, "compress_input", SYSTEM_PROMPT + "\n\n---\n\n" + userPrompt);
            log.info("LLM 변경 설명 압축 시작 - {}개 카테고리, {}건 항목", merged.size(), totalItems);
            String result = llmClient.chat(SYSTEM_PROMPT, userPrompt);
            savePromptFile(timestamp, "compress_output", result);
            Map<String, List<String>> compressed = parseResponse(result);
            if (compressed == null || compressed.isEmpty()) {
                log.warn("LLM 압축 결과가 비어있음 - 키워드 기반 요약 반환");
                return summarized;
            }
            int compressedItems = compressed.values().stream().mapToInt(List::size).sum();
            log.info("LLM 변경 설명 압축 완료 - {}개 카테고리 {}건 → {}개 카테고리 {}건",
                    summarized.size(), totalItems, compressed.size(), compressedItems);
            return compressed;
        } catch (Exception e) {
            log.warn("LLM 변경 설명 압축 실패 - 키워드 기반 요약 반환. 원인: {}", e.getMessage());
            return summarized;
        }
    }
    /**
     * 카테고리별 항목을 키워드 기반으로 요약 압축합니다.
     *
     * <p>같은 키워드(예: "weather")에 대해 여러 레이어에서 생성된 유사 항목
     * ("weather 기능 추가", "weather 조회 기능 추가", "weather 정보 관리 추가" 등)을
     * 하나의 요약 문장으로 통합합니다.</p>
     *
     * <p>예: 12건의 weather 관련 항목 → "weather 관리 기능 신규 추가 (조회, 데이터 초기화 포함)"</p>
     */
    private Map<String, List<String>> summarizeByKeyword(Map<String, Set<String>> merged) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : merged.entrySet()) {
            String keyword = entry.getKey();
            Set<String> items = entry.getValue();
            if (items.isEmpty()) continue;
            // 항목이 3개 이하면 그대로 유지
            if (items.size() <= 3) {
                result.put(keyword, new ArrayList<>(items));
                continue;
            }
            result.put(keyword, buildKeywordSummary(keyword, items));
        }
        return result;
    }
    /**
     * 단일 키워드에 속하는 항목들을 1~2개의 요약 문장으로 압축합니다.
     *
     * <p>항목들에서 액션 패턴(추가, 조회, 수정, 삭제 등)을 감지하고,
     * 키워드와 무관한 고유 항목은 별도로 유지합니다.</p>
     *
     * <p>계층적 키워드(parent/child 형식)인 경우, 상위 기능의 맥락에서
     * 하위 기능을 서술합니다.
     * 예: "scout/weather" → "scout 기능에서 weather 입력 기능 추가"</p>
     */
    private List<String> buildKeywordSummary(String keyword, Set<String> items) {
        // 계층적 키워드 분리 (parent/child)
        String parentKeyword = null;
        String childKeyword = keyword;
        if (keyword.contains("/")) {
            String[] parts = keyword.split("/", 2);
            parentKeyword = parts[0];
            childKeyword = parts[1];
        }
        String keywordLower = childKeyword.toLowerCase();
        // 키워드 관련 항목과 무관한 항목을 분리
        List<String> keywordItems = new ArrayList<>();
        List<String> otherItems = new ArrayList<>();
        for (String item : items) {
            if (item.toLowerCase().contains(keywordLower)
                    || item.toLowerCase().contains(keywordLower.replaceAll("s$", ""))) {
                keywordItems.add(item);
            } else {
                otherItems.add(item);
            }
        }
        List<String> summary = new ArrayList<>();
        if (!keywordItems.isEmpty()) {
            // 액션 패턴 감지
            boolean hasNew = false;
            boolean hasQuery = false;
            boolean hasManage = false;
            boolean hasModify = false;
            boolean hasDelete = false;
            Set<String> extraActions = new LinkedHashSet<>();
            for (String item : keywordItems) {
                if (item.contains("신규 추가") || item.contains("기능 추가") || item.contains("기능 연동")) hasNew = true;
                if (item.contains("조회")) hasQuery = true;
                if (item.contains("관리") || item.contains("정보")) hasManage = true;
                if (item.contains("수정") || item.contains("변경") || item.contains("개선")) hasModify = true;
                if (item.contains("삭제") || item.contains("제거")) hasDelete = true;
                if (item.contains("initializer") || item.contains("초기화")) extraActions.add("초기화");
                if (item.contains("동기화")) extraActions.add("동기화");
                if (item.contains("변환")) extraActions.add("변환");
                if (item.contains("검증")) extraActions.add("검증");
            }
            // 요약 문장 생성
            StringBuilder sb = new StringBuilder();
            if (parentKeyword != null) {
                // 계층적 키워드: 상위 기능 맥락에서 하위 기능 서술
                sb.append(parentKeyword).append(" 기능에서 ").append(childKeyword);
            } else {
                sb.append(childKeyword);
            }
            if (hasNew) {
                sb.append(" 관리 기능 신규 추가");
            } else if (hasModify) {
                sb.append(" 관리 기능 개선");
            } else if (hasManage) {
                sb.append(" 관리 기능 추가");
            } else if (hasQuery) {
                sb.append(" 조회 기능 추가");
            } else {
                sb.append(" 관련 변경");
            }
            // 부가 기능 괄호 표기
            List<String> subFeatures = new ArrayList<>();
            if (hasQuery && hasNew) subFeatures.add("조회");
            if (hasManage && hasNew) subFeatures.add("데이터 관리");
            if (hasDelete) subFeatures.add("삭제");
            subFeatures.addAll(extraActions);
            if (!subFeatures.isEmpty()) {
                sb.append(" (").append(String.join(", ", subFeatures)).append(" 포함)");
            }
            summary.add(sb.toString());
        }
        // 키워드와 무관한 고유 항목은 별도 추가 (최대 2개)
        int otherLimit = Math.min(otherItems.size(), 2);
        for (int i = 0; i < otherLimit; i++) {
            summary.add(otherItems.get(i));
        }
        if (otherItems.size() > 2) {
            summary.add("외 " + (otherItems.size() - 2) + "건의 관련 변경");
        }
        return summary;
    }

    /**
     * 같은 키워드를 공유하는 카테고리를 병합합니다.
     *
     * <p>카테고리명에서 괄호 안 키워드를 추출하여 그룹핑합니다.
     * 예: "API (scout)" + "비즈니스 로직 (scout)" + "데이터 모델 (scout)" → "scout"
     * 키워드가 없는 카테고리(예: "설정", "공통 모듈")는 원래 이름을 유지합니다.</p>
     *
     * <p>계층적 키워드(parent/child)는 별도 키로 유지합니다.
     * 예: "비즈니스 로직 (scout/weather)" → "scout/weather"
     * 이렇게 하면 하위 기능의 항목이 상위 기능과 구분되어 LLM이 맥락을 파악할 수 있습니다.</p>
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
