package io.github.gloryoutput.docsgenerator.summarizer;

import io.github.gloryoutput.docsgenerator.correlation.CorrelatedGroup;
import io.github.gloryoutput.docsgenerator.domain.changeevent.ChangeEvent;
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
 * LLM을 활용한 보고서 초안 문장 다듬기 서비스 (오케스트레이터)
 *
 * <p>변경 이벤트 목록과 보고서 초안을 입력받아, LlmClient를 통해 문장을 자연스럽고
 * 명확하게 다듬어 반환합니다. LlmClient 빈이 없으면 원본 초안을 그대로 반환합니다.</p>
 *
 * <p>설계 원칙(Section 16): LLM에는 raw diff, 전체 소스코드, config 원문을 절대
 * 전달하지 않으며, change event의 title/description과 구조화된 초안만 입력합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@Slf4j
public class LlmSummarizerService {
    private static final String SYSTEM_PROMPT =
            "고객사·경영진에게 제출하는 A4 2페이지 이내 변경 관리 보고서를 작성하세요.\n\n" +
            "## 절대 분량 제한\n" +
            "- **전체 보고서: 1,500자 이내. 절대 초과 금지.**\n" +
            "- 유사 변경은 반드시 하나로 묶고, 전체 보고서에서 항목 총 수는 3~5개로 제한하세요.\n" +
            "- **입력 데이터에 중복·유사 항목이 많을 수 있습니다. 같은 기능에 대한 반복 항목은 하나로 합쳐서 1번만 서술하세요.**\n" +
            "- 중요도가 낮은 항목(사소한 필드 확장, 수치 제한 변경 등)은 생략하거나 상위 기능에 포함시키세요.\n" +
            "- 섹션 간 같은 내용을 반복하지 마세요.\n\n" +
            "## 작성 원칙\n" +
            "- 격식체 한국어 (~했습니다, ~되었습니다)\n" +
            "- 비개발자 대상: 파일명, 클래스명, 패키지 경로, 개발 용어(Repository, Service, Entity 등) 사용 금지\n" +
            "- 기능명은 구체적으로 명시하되, 구현 상세(필드명, 메서드명, 수치)는 생략\n" +
            "- 대분류: **기획수정**(기존 기능 변경/개선), **신기능**(신규 추가), **오류수정**(버그/장애 수정)\n\n" +
            "## 출력 구조 (6개 섹션, ### 제목, 번호 붙이지 말 것)\n\n" +
            "### 목적\n" +
            "2~3문장. 변경 배경, 핵심 주제, 규모(건수).\n\n" +
            "### 발생한 문제\n" +
            "대분류별 1~2개 항목. 형식: '- **{영역명}**: {배경 1~2문장}'\n" +
            "오류수정은 실제 오류 현상을 구체적으로 서술.\n\n" +
            "### 문제 원인\n" +
            "'발생한 문제'와 1:1 대응. 원인만 서술하고 해결 내용은 쓰지 마세요.\n" +
            "형식: '- **{영역명}**: {원인 1문장}'\n\n" +
            "### 문제 해결 과정\n" +
            "**이 섹션은 전체 보고서에서 가장 짧아야 합니다. 최대 3~5줄.**\n" +
            "- 대분류별 1줄로 요약. 형식: '- **{대분류}**: {조치 요약 1문장}'\n" +
            "- 기능별로 나열 금지. 모든 신기능은 하나의 항목으로 묶어 기능명만 나열.\n" +
            "  예: '- **신기능**: 메인페이지 개편, 포메이션 확장, 스케줄 개선 등 총 N건의 기능을 신규 개발하였습니다'\n" +
            "- 기획수정, 오류수정도 각각 1줄로 통합.\n" +
            "- '초기 조사', '요구사항 분석', '현황 파악' 등 조사/분석 단계는 절대 포함하지 마세요. 실제 수행한 조치만 서술.\n" +
            "- '초기 조사→수정→테스트' 단계별 서술, 기능별 상세 설명 절대 금지.\n\n" +
            "### 결과\n" +
            "'상기 작업을 통해 다음과 같은 변경이 완료되었습니다.'로 시작.\n" +
            "해결 과정과 동일 단위로 서술. 형식: '- **{영역명}** — {완료 내용} (완료)'\n\n" +
            "### 개선 및 예방 방안\n" +
            "**300자 이내.** 완료된 작업의 개선점을 적지 마세요. 앞으로 더 좋은 방향으로 나아가기 위한 제안만 1~2개. 항목별 1문장.\n\n" +
            "## 금지 사항\n" +
            "- 원본에 없는 사실 추가 금지\n" +
            "- 파일명, 클래스명, 코드 diff 나열 금지\n" +
            "- 대상 없는 빈 문장 금지\n" +
            "- 1,500자 초과 금지";
    private static final DateTimeFormatter FILE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private final LlmClient llmClient;
    @Value("${app.llm.prompt-output-dir:./llm-prompts}")
    private String promptOutputDir;

    public LlmSummarizerService(@Autowired(required = false) LlmClient llmClient) {
        this.llmClient = llmClient;
        if (llmClient != null) {
            log.info("LLM Summarizer 활성화 - client: {}", llmClient.getClass().getSimpleName());
        } else {
            log.info("LLM Summarizer 비활성화 (LlmClient 빈 없음)");
        }
    }

    /**
     * 변경 이벤트 그룹과 보고서 초안을 기반으로 6개 섹션 보고서를 생성합니다.
     *
     * <p>LlmClient 빈이 없거나 LLM 호출 실패 시 빈 문자열을 반환합니다.</p>
     *
     * @param groups             상관관계로 그룹핑된 변경 이벤트 목록
     * @param originalDraft      보고서 초안 (Markdown)
     * @param mergeRepositories  레포지토리 통합 여부
     * @return 6개 섹션이 포함된 보고서 텍스트 또는 빈 문자열
     */
    /**
     * 변경 이벤트 그룹과 보고서 초안을 기반으로 6개 섹션 보고서를 생성합니다.
     *
     * <p>LlmClient 빈이 없거나 LLM 호출 실패 시 빈 문자열을 반환합니다.
     * 6개 섹션(목적, 발생한 문제, 문제 원인, 문제 해결 과정, 결과, 개선 및 예방 방안)은
     * LLM 없이는 생성할 수 없으므로, 원본 초안을 반환하지 않습니다.</p>
     */
    public String summarize(List<CorrelatedGroup> groups, String originalDraft, boolean mergeRepositories) {
        if (llmClient == null) {
            log.debug("LLM 비활성화 - 6개 섹션 생성 불가, 빈 문자열 반환");
            return "";
        }
        try {
            String userPrompt = buildUserPrompt(groups, originalDraft, mergeRepositories);
            String timestamp = LocalDateTime.now().format(FILE_FORMATTER);
            saveInputFile(timestamp, SYSTEM_PROMPT, userPrompt);
            log.info("LLM 입력 - System Prompt:\n{}", SYSTEM_PROMPT);
            log.info("LLM 입력 - User Prompt:\n{}", userPrompt);
            String result = llmClient.chat(SYSTEM_PROMPT, userPrompt);
            log.info("LLM 출력:\n{}", result);
            saveOutputFile(timestamp, result);
            return result;
        } catch (Exception e) {
            log.warn("LLM 호출 실패 - 6개 섹션 생성 불가. 원인: {}", e.getMessage());
            return "";
        }
    }

    /**
     * LLM 입력(System Prompt + User Prompt)을 파일로 저장합니다.
     */
    private void saveInputFile(String timestamp, String systemPrompt, String userPrompt) {
        try {
            Path dir = Paths.get(promptOutputDir);
            Files.createDirectories(dir);
            String content = "# LLM Input\n\n" +
                    "## System Prompt\n\n" + systemPrompt +
                    "\n\n## User Prompt\n\n" + userPrompt;
            Path filePath = dir.resolve("llm_input_" + timestamp + ".md");
            Files.writeString(filePath, content, StandardCharsets.UTF_8);
            log.info("LLM 입력 파일 저장 완료: {}", filePath.toAbsolutePath());
        } catch (IOException e) {
            log.warn("LLM 입력 파일 저장 실패: {}", e.getMessage());
        }
    }
    /**
     * LLM 출력 결과를 파일로 저장합니다.
     */
    private void saveOutputFile(String timestamp, String output) {
        try {
            Path dir = Paths.get(promptOutputDir);
            Files.createDirectories(dir);
            String content = "# LLM Output\n\n" + output;
            Path filePath = dir.resolve("llm_output_" + timestamp + ".md");
            Files.writeString(filePath, content, StandardCharsets.UTF_8);
            log.info("LLM 출력 파일 저장 완료: {}", filePath.toAbsolutePath());
        } catch (IOException e) {
            log.warn("LLM 출력 파일 저장 실패: {}", e.getMessage());
        }
    }

    /**
     * 상관관계 그룹 구조를 유지한 채 핵심 정보만 포함한 Markdown 텍스트로 변환합니다.
     */
    private String buildEventsMarkdown(List<CorrelatedGroup> groups) {
        StringBuilder sb = new StringBuilder();
        int groupIndex = 1;
        // 중복 제목 제거를 위한 Set
        Set<String> seenTitles = new LinkedHashSet<>();
        for (CorrelatedGroup group : groups) {
            if (group.getEvents() == null || group.getEvents().isEmpty()) continue;
            sb.append("### ").append(groupIndex++).append(". ").append(group.getTitle()).append("\n\n");
            for (ChangeEvent event : group.getEvents()) {
                // 동일/유사 제목의 이벤트는 1번만 출력
                String normalizedTitle = event.getTitle().trim().toLowerCase();
                if (!seenTitles.add(normalizedTitle)) continue;
                sb.append("**").append(event.getTitle()).append("**");
                sb.append(" `").append(event.getCategory()).append("` `").append(event.getSeverity()).append("`");
                if (event.getMajorCategory() != null && !event.getMajorCategory().isBlank()) {
                    sb.append(" `대분류:").append(event.getMajorCategory()).append("`");
                }
                sb.append("\n\n");
                if (event.getDescription() != null) {
                    sb.append(event.getDescription()).append("\n\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * 전체 변경 통계 요약을 생성합니다.
     */
    private String buildSummarySection(List<CorrelatedGroup> groups) {
        int totalEvents = 0;
        Map<String, Integer> categoryCount = new LinkedHashMap<>();
        Map<String, Integer> severityCount = new LinkedHashMap<>();
        Set<String> allSourceTypes = new LinkedHashSet<>();
        for (CorrelatedGroup group : groups) {
            if (group.getEvents() == null) continue;
            for (ChangeEvent event : group.getEvents()) {
                totalEvents++;
                categoryCount.merge(event.getCategory(), 1, Integer::sum);
                severityCount.merge(event.getSeverity(), 1, Integer::sum);
                if (event.getSourceType() != null) allSourceTypes.add(event.getSourceType());
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("- **총 변경 그룹**: ").append(groups.size()).append("개\n");
        sb.append("- **총 변경 이벤트**: ").append(totalEvents).append("건\n");
        sb.append("- **카테고리별**: ");
        categoryCount.forEach((k, v) -> sb.append(k).append("(").append(v).append(") "));
        sb.append("\n");
        sb.append("- **심각도별**: ");
        severityCount.forEach((k, v) -> sb.append(k).append("(").append(v).append(") "));
        sb.append("\n");
        sb.append("- **소스 타입**: ").append(String.join(", ", allSourceTypes)).append("\n");
        return sb.toString();
    }

    /**
     * 변경 이벤트로부터 영향 범위 정보를 추출합니다.
     */
    private String buildImpactSummary(List<CorrelatedGroup> groups) {
        Set<String> tables = new LinkedHashSet<>();
        Set<String> endpoints = new LinkedHashSet<>();
        Set<String> repositories = new LinkedHashSet<>();
        List<String> highEvents = new ArrayList<>();
        for (CorrelatedGroup group : groups) {
            if (group.getEvents() == null) continue;
            for (ChangeEvent event : group.getEvents()) {
                switch (event.getCategory()) {
                    case "SCHEMA_CHANGE" -> {
                        if (event.getCorrelationKey() != null) tables.add(event.getCorrelationKey());
                    }
                    case "API_CHANGE" -> {
                        if (event.getCorrelationKey() != null) endpoints.add(event.getCorrelationKey());
                    }
                    case "CODE_CHANGE" -> {
                        if (event.getCorrelationKey() != null) repositories.add(event.getCorrelationKey());
                    }
                }
                if ("HIGH".equals(event.getSeverity())) {
                    highEvents.add("[HIGH] " + event.getTitle());
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        if (!tables.isEmpty()) {
            sb.append("- **영향받는 테이블**: ").append(String.join(", ", tables)).append("\n");
        }
        if (!endpoints.isEmpty()) {
            sb.append("- **영향받는 API**: ").append(String.join(", ", endpoints)).append("\n");
        }
        if (!repositories.isEmpty()) {
            sb.append("- **영향받는 레포지토리**: ").append(String.join(", ", repositories)).append("\n");
        }
        if (!highEvents.isEmpty()) {
            sb.append("- **고위험 변경 사항**:\n");
            highEvents.forEach(e -> sb.append("  - ").append(e).append("\n"));
        }
        return sb.toString();
    }

    /** 사용자 프롬프트의 각 대용량 섹션에 적용할 최대 문자 수 (LLM 토큰 초과 방지) */
    private static final int MAX_SECTION_CHARS = 8000;

    /**
     * 시스템 프롬프트에 전달할 사용자 프롬프트를 구성합니다.
     *
     * <p>변경 이벤트 상세와 보고서 초안은 대량의 커밋이 포함될 경우
     * LLM 컨텍스트 윈도우를 초과할 수 있으므로, 섹션별 최대 문자 수를 제한합니다.</p>
     */
    private String buildUserPrompt(List<CorrelatedGroup> groups, String originalDraft,
                                    boolean mergeRepositories) {
        String summary = buildSummarySection(groups);
        String eventsMarkdown = truncateIfNeeded(buildEventsMarkdown(groups), MAX_SECTION_CHARS);
        String impactSummary = buildImpactSummary(groups);
        String truncatedDraft = truncateIfNeeded(originalDraft, MAX_SECTION_CHARS);
        StringBuilder sb = new StringBuilder();
        sb.append("## 변경 요약 통계\n\n").append(summary);
        sb.append("\n## 영향 범위 정보\n\n").append(impactSummary);
        sb.append("\n## 변경 이벤트 상세 (참고 자료)\n\n").append(eventsMarkdown);
        sb.append("\n## 보고서 초안 (다듬을 대상)\n\n").append(truncatedDraft);
        sb.append("\n---\n\n");
        if (mergeRepositories) {
            sb.append("## 중요: 통합 프로젝트 관점 서술\n");
            sb.append("이 보고서는 여러 레포지토리(서버, 프론트엔드 등)를 **하나의 프로젝트**로 통합하여 작성해야 합니다.\n");
            sb.append("개별 레포지토리명을 직접 언급하지 말고, ");
            sb.append("'본 프로젝트', '시스템' 등의 통합된 관점으로 서술하세요.\n");
            sb.append("서버 측 변경과 화면 측 변경이 있다면 하나의 기능 개선 흐름으로 자연스럽게 통합하여 서술하세요.\n\n");
        } else {
            sb.append("## 중요: 레포지토리별 구분 서술\n");
            sb.append("이 보고서는 각 레포지토리(서버, 프론트엔드 등)의 변경 사항을 **구분하여** 작성해야 합니다.\n");
            sb.append("각 레포지토리에서 발생한 변경을 별도로 서술하되, 관련 있는 변경끼리 인과관계를 명시하세요.\n\n");
        }
        sb.append("위 정보를 기반으로 6개 섹션(###) 보고서를 작성하세요.\n");
        sb.append("**반드시 1,500자 이내로 작성하세요. 유사 항목 통합 필수. Markdown 본문만 출력.**");
        return sb.toString();
    }

    /**
     * 텍스트가 최대 문자 수를 초과하면 줄 단위로 잘라내고 생략 표시를 추가합니다.
     *
     * @param text 원본 텍스트
     * @param maxChars 최대 문자 수
     * @return 원본 텍스트 또는 잘라낸 텍스트
     */
    private String truncateIfNeeded(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        // 줄 단위로 자르기 (문장 중간 절단 방지)
        int cutIndex = text.lastIndexOf('\n', maxChars);
        if (cutIndex <= 0) {
            cutIndex = maxChars;
        }
        log.info("프롬프트 섹션 잘라냄: {}자 → {}자", text.length(), cutIndex);
        return text.substring(0, cutIndex) + "\n\n(... 이하 생략 - 위 내용을 기반으로 보고서를 작성해 주세요)";
    }
}
