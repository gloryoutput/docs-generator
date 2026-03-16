package io.github.gloryoutput.docsgenerator.summarizer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gloryoutput.docsgenerator.domain.changeevent.ChangeEvent;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
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

/**
 * LLM을 활용한 변경 이벤트 강화 서비스
 *
 * <p>규칙 엔진이 생성한 ChangeEvent 목록을 LLM에 전달하여
 * 제목, 설명, 심각도, 변경 의도를 분석하고 개선합니다.</p>
 *
 * <p>설계 원칙(Section 16): raw diff, 전체 소스코드, config 원문을 전달하지 않으며,
 * ChangeEvent의 title/description/category 등 구조화된 정보만 입력합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@Slf4j
public class LlmChangeEventEnhancerService {
    private static final String SYSTEM_PROMPT =
            "당신은 소프트웨어 변경 관리(Change Management) 전문가입니다.\n" +
            "아래 변경 이벤트 목록을 분석하여 각 이벤트의 제목, 설명, 심각도를 공식 보고서에 적합한 수준으로 개선해 주세요.\n\n" +
            LlmPromptConstants.AUDIENCE_RULE + "\n" +
            LlmPromptConstants.CONCRETE_SUBJECT_RULE + "\n" +
            LlmPromptConstants.CLIENT_PERSPECTIVE_RULE + "\n" +
            "## 제목 작성 규칙\n" +
            "- 간결하고 명확한 한국어로 작성 (80자 이내)\n" +
            "- '~기능 추가', '~처리 방식 개선', '~관리 기능 확장' 등 업무 관점의 변경 유형이 드러나는 형태\n" +
            "- 고유명사(시스템명, 서비스명)는 그대로 유지\n\n" +
            "## 설명 작성 규칙\n" +
            LlmPromptConstants.FORMAL_TONE_RULE +
            "- '어떤 업무 기능이 → 어떻게 변경되었고 → 사용자/업무에 어떤 영향이 있는지' 순서로 서술\n" +
            "- 개발 내부 구현 상세(필드 추가, 메서드 추가, 어노테이션 등)는 언급하지 않음\n\n" +
            "## 심각도 판단 기준\n" +
            "- **HIGH**: DB 스키마 변경, 인증/보안 변경, 기존 API 삭제/호환성 파괴, 대규모 리팩토링\n" +
            "- **MEDIUM**: 기존 기능 수정, 새 API 추가, 비즈니스 로직 변경, Entity 구조 변경\n" +
            "- **LOW**: 설정 변경, 코드 정리, 문서 수정, 유틸리티 추가, 네이밍 변경\n\n" +
            "## 대분류 판별 기준 (majorCategory)\n" +
            LlmPromptConstants.MAJOR_CATEGORY_RULE + "\n" +
            "판별 시 주의사항:\n" +
            "- '데이터 모델 확장', '컬럼 추가', '테이블 추가' 등은 기능 목적에 따라 판별하세요 (신규 기능을 위한 것이면 '신기능', 기존 기능 변경이면 '기획수정')\n" +
            "- '수정'이라는 단어가 있다고 무조건 '오류수정'이 아닙니다. 기능 자체를 수정/개선한 것이면 '기획수정'입니다\n" +
            "- '오류수정'은 실제 오류/버그/장애가 발생하여 이를 고친 경우에만 해당합니다\n\n" +
            "## 응답 형식\n" +
            "반드시 아래 JSON 배열 형식으로만 응답하세요. 다른 텍스트를 포함하지 마세요.\n" +
            "[\n" +
            "  {\n" +
            "    \"index\": 0,\n" +
            "    \"title\": \"개선된 제목\",\n" +
            "    \"description\": \"개선된 설명\",\n" +
            "    \"severity\": \"HIGH|MEDIUM|LOW\",\n" +
            "    \"majorCategory\": \"기획수정|신기능|오류수정\"\n" +
            "  }\n" +
            "]";
    private static final DateTimeFormatter FILE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final Set<String> VALID_SEVERITIES = Set.of("HIGH", "MEDIUM", "LOW");
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Value("${app.llm.prompt-output-dir:./llm-prompts}")
    private String promptOutputDir;

    public LlmChangeEventEnhancerService(@Autowired(required = false) LlmClient llmClient) {
        this.llmClient = llmClient;
        if (llmClient != null) {
            log.info("LLM ChangeEvent Enhancer 활성화 - client: {}", llmClient.getClass().getSimpleName());
        } else {
            log.info("LLM ChangeEvent Enhancer 비활성화 (LlmClient 빈 없음)");
        }
    }

    /**
     * 변경 이벤트 목록을 LLM으로 강화합니다.
     *
     * <p>LlmClient 빈이 없거나 이벤트가 비어있으면 원본을 그대로 반환합니다.
     * LLM 호출 중 오류 발생 시에도 원본을 반환합니다(fallback).</p>
     *
     * @param events 규칙 엔진이 생성한 변경 이벤트 목록
     * @param idAnalysisRequest 분석 요청 ID
     * @param idProject 프로젝트 ID
     * @return LLM으로 강화된 변경 이벤트 목록 또는 원본
     */
    public List<ChangeEvent> enhance(List<ChangeEvent> events, UUID idAnalysisRequest, UUID idProject,
                                      boolean mergeRepositories) {
        if (llmClient == null || events == null || events.isEmpty()) {
            log.debug("LLM ChangeEvent 강화 건너뜀 - client: {}, events: {}",
                    llmClient != null ? "있음" : "없음", events != null ? events.size() : 0);
            return events;
        }
        try {
            String userPrompt = buildUserPrompt(events, mergeRepositories);
            String timestamp = LocalDateTime.now().format(FILE_FORMATTER);
            savePromptFile(timestamp, "enhance_input", SYSTEM_PROMPT + "\n\n---\n\n" + userPrompt);
            log.info("LLM ChangeEvent 강화 시작 - {}건 이벤트", events.size());
            String result = llmClient.chat(SYSTEM_PROMPT, userPrompt);
            savePromptFile(timestamp, "enhance_output", result);
            List<EnhancedEventDto> enhanced = parseResponse(result);
            if (enhanced == null || enhanced.isEmpty()) {
                log.warn("LLM 응답 파싱 결과가 비어있음 - 원본 반환");
                return events;
            }
            return applyEnhancements(events, enhanced, idAnalysisRequest, idProject);
        } catch (Exception e) {
            log.warn("LLM ChangeEvent 강화 실패 - 원본 반환. 원인: {}", e.getMessage());
            return events;
        }
    }

    /**
     * 이벤트 목록을 LLM에 전달할 구조화된 프롬프트로 변환합니다.
     */
    private String buildUserPrompt(List<ChangeEvent> events, boolean mergeRepositories) {
        StringBuilder sb = new StringBuilder();
        if (mergeRepositories) {
            sb.append("## 작성 모드: 프로젝트 통합\n");
            sb.append("여러 레포지토리의 변경 사항을 하나의 프로젝트로 통합하여 서술하세요.\n");
            sb.append("개별 레포지토리명을 언급하지 말고 통합된 관점으로 작성하세요.\n\n");
        }
        sb.append("## 변경 이벤트 목록 (").append(events.size()).append("건)\n\n");
        for (int i = 0; i < events.size(); i++) {
            ChangeEvent event = events.get(i);
            sb.append("### 이벤트 ").append(i).append("\n");
            sb.append("- **카테고리**: ").append(event.getCategory()).append("\n");
            sb.append("- **제목**: ").append(event.getTitle()).append("\n");
            sb.append("- **심각도**: ").append(event.getSeverity()).append("\n");
            sb.append("- **소스**: ").append(event.getSourceType()).append("\n");
            if (event.getDescription() != null && !event.getDescription().isBlank()) {
                sb.append("- **설명**: ").append(event.getDescription()).append("\n");
            }
            sb.append("\n");
        }
        sb.append("---\n\n위 이벤트들의 제목, 설명, 심각도를 개선하여 JSON 배열로 응답해 주세요.");
        return sb.toString();
    }

    /**
     * LLM 응답을 파싱하여 EnhancedEventDto 목록으로 변환합니다.
     *
     * <p>JSON 배열이 코드블록(```json...```)으로 감싸져 있는 경우도 처리합니다.</p>
     */
    private List<EnhancedEventDto> parseResponse(String response) {
        try {
            String json = extractJson(response);
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("LLM 응답 JSON 파싱 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 응답 문자열에서 JSON 배열 부분을 추출합니다.
     */
    private String extractJson(String response) {
        String trimmed = response.trim();
        // 코드블록 제거
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        // JSON 배열 시작점 찾기
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    /**
     * LLM 강화 결과를 원본 이벤트에 적용하여 새 이벤트 목록을 생성합니다.
     *
     * <p>index로 원본 이벤트와 매핑합니다. 매핑되지 않는 이벤트는 원본을 유지합니다.
     * category, sourceType, correlationKey, confidenceScore는 원본을 유지합니다.</p>
     */
    private List<ChangeEvent> applyEnhancements(List<ChangeEvent> originals, List<EnhancedEventDto> enhanced,
                                                  UUID idAnalysisRequest, UUID idProject) {
        Map<Integer, EnhancedEventDto> enhancedMap = new HashMap<>();
        for (EnhancedEventDto dto : enhanced) {
            if (dto.getIndex() != null && dto.getIndex() >= 0 && dto.getIndex() < originals.size()) {
                enhancedMap.put(dto.getIndex(), dto);
            }
        }
        List<ChangeEvent> result = new ArrayList<>(originals.size());
        int enhancedCount = 0;
        for (int i = 0; i < originals.size(); i++) {
            ChangeEvent original = originals.get(i);
            EnhancedEventDto dto = enhancedMap.get(i);
            if (dto != null && hasValidEnhancement(dto)) {
                result.add(ChangeEvent.builder()
                        .idAnalysisRequest(idAnalysisRequest)
                        .idProject(idProject)
                        .category(original.getCategory())
                        .title(dto.getTitle() != null && !dto.getTitle().isBlank()
                                ? dto.getTitle() : original.getTitle())
                        .description(dto.getDescription() != null && !dto.getDescription().isBlank()
                                ? dto.getDescription() : original.getDescription())
                        .severity(VALID_SEVERITIES.contains(dto.getSeverity())
                                ? dto.getSeverity() : original.getSeverity())
                        .confidenceScore(original.getConfidenceScore())
                        .sourceType(original.getSourceType())
                        .correlationKey(original.getCorrelationKey())
                        .majorCategory(VALID_MAJOR_CATEGORIES.contains(dto.getMajorCategory())
                                ? dto.getMajorCategory() : null)
                        .build());
                enhancedCount++;
            } else {
                result.add(original);
            }
        }
        log.info("LLM ChangeEvent 강화 완료 - {}건 중 {}건 개선", originals.size(), enhancedCount);
        return result;
    }

    /**
     * EnhancedEventDto에 유효한 강화 데이터가 있는지 검증합니다.
     */
    private boolean hasValidEnhancement(EnhancedEventDto dto) {
        return (dto.getTitle() != null && !dto.getTitle().isBlank())
                || (dto.getDescription() != null && !dto.getDescription().isBlank())
                || VALID_SEVERITIES.contains(dto.getSeverity());
    }

    /**
     * 프롬프트/결과를 파일로 저장합니다.
     */
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

    /**
     * LLM 응답을 역직렬화하기 위한 내부 DTO
     */
    private static final Set<String> VALID_MAJOR_CATEGORIES = Set.of("기획수정", "신기능", "오류수정");

    @Getter
    @Setter
    @NoArgsConstructor
    static class EnhancedEventDto {
        private Integer index;
        private String title;
        private String description;
        private String severity;
        private String majorCategory;
    }
}
