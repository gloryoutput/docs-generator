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
            "당신은 IT 프로젝트 변경 관리 보고서를 작성하는 시니어 테크니컬 라이터입니다.\n" +
            "아래 변경 이벤트 목록과 초안을 기반으로, 실제 고객사·경영진에게 제출할 수 있는 공식 보고서로 다듬어 주세요.\n\n" +
            "## 작성 원칙\n" +
            "1. **전문적 어조**: 공식 업무 보고서에 적합한 격식체 한국어를 사용합니다 (~했습니다, ~되었습니다).\n" +
            "2. **기술적 정확성**: 원본 정보의 기술적 사실을 변경하지 않으며, 새로운 정보를 추가하지 않습니다.\n" +
            "3. **기능 중심 서술**: 파일명, 클래스명, 패키지 경로를 직접 나열하지 않고, '어떤 기능이 어떻게 변경되었는가'를 중심으로 서술합니다.\n" +
            "4. **영향도 강조**: 각 변경이 시스템에 미치는 영향과 의미를 명확히 전달합니다.\n" +
            "5. **Markdown 형식**: 제목(###), 볼드(**), 불릿(-) 등 Markdown 서식을 사용합니다.\n\n" +
            "## 출력 구조 (반드시 아래 6개 섹션을 ### 제목으로 구분하여 순서대로 출력하세요)\n\n" +
            "### 목적\n" +
            "- 본 보고서의 작성 목적을 2~3문장으로 서술합니다.\n" +
            "- 이번 변경이 왜 필요했는지, 전체 변경 이벤트를 조망하여 통합된 목적을 작성합니다.\n" +
            "- 예시: '본 보고서는 ○○ 시스템의 ○○ 기능 개선 작업 과정을 정리하고, 작업 결과를 공유하기 위해 작성되었습니다'\n\n" +
            "### 발생한 문제\n" +
            "- 변경이 필요하게 된 배경 또는 기존 시스템의 문제점/요구사항을 서술합니다.\n" +
            "- 기능 추가인 경우 '기존 시스템에 ○○ 기능이 부재하여...' 형태로 작성합니다.\n" +
            "- 버그 수정인 경우 '○○ 기능에서 ○○ 오류가 발생하여...' 형태로 작성합니다.\n" +
            "- 카테고리별(DB 스키마, API, 코드)로 구분하여 불릿 목록으로 정리합니다.\n\n" +
            "### 문제 원인\n" +
            "- 위 문제가 발생한 원인 또는 변경이 필요하게 된 기술적 근거를 분석합니다.\n" +
            "- 기능 추가인 경우 '신규 비즈니스 요구사항 발생' 등의 형태로 작성합니다.\n" +
            "- 관련된 변경끼리 묶어서 인과관계를 설명합니다.\n\n" +
            "### 문제 해결 과정\n" +
            "- 실제 수행한 작업을 번호 목록(1. 2. 3.) 형태로 단계별로 정리합니다.\n" +
            "- 각 단계는 소제목(볼드)과 세부 내용으로 구성합니다.\n" +
            "- 예시:\n" +
            "  1. **데이터 모델 설계 및 스키마 변경**\n" +
            "  - ○○ 기능에 필요한 테이블을 설계하고 스키마를 적용하였습니다.\n" +
            "- DB 마이그레이션이 포함된 경우 핵심 내용을 포함합니다.\n" +
            "- 삭제된 기능이 있으면 대체된 새로운 구현을 함께 언급합니다.\n\n" +
            "### 결과\n" +
            "- 변경 작업의 최종 결과를 불릿 목록으로 요약합니다.\n" +
            "- 각 항목은 '~을 완료하였습니다' 또는 '~이 적용되었습니다' 형태로 작성합니다.\n" +
            "- 영향받는 시스템 구성요소(테이블, API, 모듈)를 포함합니다.\n" +
            "- 정상 동작 확인 여부가 있으면 포함합니다.\n\n" +
            "### 개선 및 예방 방안\n" +
            "- 이번 변경으로 인해 추가로 확인하거나 조치해야 할 사항을 기술합니다.\n" +
            "- 예방적 관점에서 향후 유사 작업 시 참고할 내용을 소제목(볼드) + 설명 형태로 작성합니다.\n" +
            "- 고위험(HIGH) 변경이 있는 경우 해당 항목에 대한 주의사항을 반드시 포함합니다.\n" +
            "- 해당 사항이 없으면 '별도의 후속 조치가 필요하지 않습니다.'로 작성합니다.\n\n" +
            "## 금지 사항\n" +
            "- 원본에 없는 사실이나 추측을 추가하지 마세요.\n" +
            "- 코드 diff 원문이나 변경 줄 수(+N/-N)를 그대로 노출하지 마세요.\n" +
            "- 파일명(*.java, *.xml 등), 클래스명, 패키지 경로를 보고서에 직접 나열하지 마세요.\n" +
            "- '파일 N개 변경' 같은 파일 수 중심 서술을 하지 마세요. 기능 변경 중심으로 서술하세요.\n" +
            "- 섹션 제목 앞에 번호를 붙이지 마세요 (### 목적, ### 발생한 문제 형태 유지).";
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
     * 변경 이벤트 그룹과 보고서 초안을 기반으로 문장을 다듬어 반환합니다.
     *
     * <p>LlmClient 빈이 없으면 원본 초안을 그대로 반환합니다.
     * LLM 호출 중 오류 발생 시에도 원본 초안을 반환합니다(fallback).</p>
     *
     * @param groups        상관관계로 그룹핑된 변경 이벤트 목록
     * @param originalDraft 보고서 초안 (Markdown)
     * @return 다듬어진 보고서 텍스트 또는 원본 초안
     */
    public String summarize(List<CorrelatedGroup> groups, String originalDraft) {
        try {
            String userPrompt = buildUserPrompt(groups, originalDraft);
            String timestamp = LocalDateTime.now().format(FILE_FORMATTER);
            saveInputFile(timestamp, SYSTEM_PROMPT, userPrompt);
            if (llmClient == null) {
                log.debug("LLM 비활성화 - 원본 초안 반환");
                return originalDraft;
            }
            log.info("LLM 입력 - System Prompt:\n{}", SYSTEM_PROMPT);
            log.info("LLM 입력 - User Prompt:\n{}", userPrompt);
            String result = llmClient.chat(SYSTEM_PROMPT, userPrompt);
            log.info("LLM 출력:\n{}", result);
            saveOutputFile(timestamp, result);
            return result;
        } catch (Exception e) {
            log.warn("LLM 호출 실패 - 원본 초안 반환. 원인: {}", e.getMessage());
            return originalDraft;
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
        for (CorrelatedGroup group : groups) {
            if (group.getEvents() == null || group.getEvents().isEmpty()) continue;
            sb.append("### ").append(groupIndex++).append(". ").append(group.getTitle()).append("\n\n");
            for (ChangeEvent event : group.getEvents()) {
                sb.append("**").append(event.getTitle()).append("**");
                sb.append(" `").append(event.getCategory()).append("` `").append(event.getSeverity()).append("`\n\n");
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

    /**
     * 시스템 프롬프트에 전달할 사용자 프롬프트를 구성합니다.
     */
    private String buildUserPrompt(List<CorrelatedGroup> groups, String originalDraft) {
        String summary = buildSummarySection(groups);
        String eventsMarkdown = buildEventsMarkdown(groups);
        String impactSummary = buildImpactSummary(groups);
        return "## 변경 요약 통계\n\n" + summary +
                "\n## 영향 범위 정보\n\n" + impactSummary +
                "\n## 변경 이벤트 상세 (참고 자료)\n\n" + eventsMarkdown +
                "\n## 보고서 초안 (다듬을 대상)\n\n" + originalDraft +
                "\n---\n\n" +
                "위 정보를 참고하여 '보고서 초안'을 고객사·경영진에게 제출할 수 있는 공식 보고서 수준으로 다듬어 주세요.\n" +
                "반드시 시스템 프롬프트의 '출력 구조'에 명시된 6개 섹션(목적, 발생한 문제, 문제 원인, 문제 해결 과정, 결과, 개선 및 예방 방안)을 모두 ### 제목으로 구분하여 포함하세요.\n" +
                "영향 범위 정보와 고위험 변경 사항은 '결과' 및 '개선 및 예방 방안' 섹션에 자연스럽게 반영하세요.\n" +
                "결과물은 Markdown 본문만 출력하세요. 부가 설명이나 인사말은 포함하지 마세요.";
    }
}
