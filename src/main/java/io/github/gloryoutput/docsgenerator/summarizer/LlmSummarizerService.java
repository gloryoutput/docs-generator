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
            "## 핵심 원칙: '어떤 목적으로 코드를 수정했는지'를 메인으로 작성하세요\n" +
            "이 프로젝트를 전혀 모르는 사람도 보고서만 읽고 '왜 이 변경이 필요했는지' 이해할 수 있어야 합니다.\n" +
            "단순히 '무엇을 추가/수정했다'가 아니라 '어떤 문제를 해결하기 위해' 또는 '어떤 목표를 달성하기 위해' 변경했는지를 중심으로 서술하세요.\n\n" +
            "## 작성 원칙\n" +
            "1. **전문적 어조**: 공식 업무 보고서에 적합한 격식체 한국어를 사용합니다 (~했습니다, ~되었습니다).\n" +
            "2. **기술적 정확성**: 원본 정보의 기술적 사실을 변경하지 않으며, 새로운 정보를 추가하지 않습니다.\n" +
            "3. **변경 목적 중심 서술**: 파일명, 클래스명, 패키지 경로를 직접 나열하지 않고, '어떤 목적으로 코드를 수정했는지'를 중심으로 서술합니다.\n" +
            "   나쁜 예: '스카우트 관리 기능을 신규 추가하였습니다.' (무엇을 했는지만 서술)\n" +
            "   좋은 예: '스카우트 후보 선수 정보를 체계적으로 관리하기 위해 관리 기능을 신규 추가하였습니다.' (목적 + 행위)\n" +
            "4. **영향도 강조**: 각 변경이 시스템에 미치는 영향과 의미를 명확히 전달합니다.\n" +
            "5. **Markdown 형식**: 제목(###), 볼드(**), 불릿(-) 등 Markdown 서식을 사용합니다.\n" +
            "6. **구체적 데이터 필수**: 모든 섹션에서 반드시 실제 변경 이벤트의 데이터(변경된 기능명, 테이블명, API 경로, 변경 내용)를 인용하여 서술하세요. " +
            "'변경이 필요하였습니다', '수정이 이루어졌습니다' 같은 막연한 문장만으로는 부족합니다. " +
            "반드시 '무엇이', '왜', '어떻게' 변경되었는지 구체적으로 명시하세요.\n\n" +
            "## 출력 구조 (반드시 아래 6개 섹션을 ### 제목으로 구분하여 순서대로 출력하세요)\n\n" +
            "### 목적\n" +
            "- 본 보고서의 작성 목적을 2~3문장으로 서술합니다.\n" +
            "- 이번 변경의 핵심 주제(어떤 기능/모듈이 왜 변경되었는지)를 구체적으로 명시하세요.\n" +
            "- 나쁜 예: '본 보고서는 소프트웨어 변경 작업의 과정을 정리하고 결과를 공유하기 위해 작성되었습니다.' (이런 뻔한 문장은 금지)\n" +
            "- 좋은 예: '본 보고서는 ○○ 시스템에 스카우트 평가 기능을 신규 추가하고, 선수 관리 API를 개편한 작업의 배경과 결과를 정리한 문서입니다.'\n" +
            "- 변경 이벤트 데이터에서 주요 기능명, 모듈명을 추출하여 목적에 반영하세요.\n\n" +
            "### 발생한 문제\n" +
            "- 변경이 필요하게 된 배경 또는 기존 시스템의 문제점/요구사항을 서술합니다.\n" +
            "- 각 항목마다 '구체적으로 무엇이 없었는지/부족했는지/잘못되었는지'를 명시하세요.\n" +
            "- 나쁜 예: '기존 시스템에 기능이 부재하여 추가가 필요하였습니다.' (무슨 기능인지 알 수 없음)\n" +
            "- 좋은 예: '기존 시스템에는 스카우트가 선수를 관찰한 내용을 태그 기반으로 분류하는 기능이 없어, 관찰 기록 검색이 비효율적이었습니다.'\n" +
            "- 카테고리별(DB 스키마, API, 코드)로 구분하되, 각 항목은 변경 이벤트의 실제 데이터를 기반으로 작성합니다.\n\n" +
            "### 문제 원인\n" +
            "- 위 문제가 발생한 원인 또는 변경이 필요하게 된 기술적 근거를 분석합니다.\n" +
            "- 기능 추가인 경우, 어떤 업무 요구사항이 있었는지 이벤트 데이터에서 유추하여 서술합니다.\n" +
            "- 관련된 변경끼리 묶어서 인과관계를 설명합니다.\n" +
            "- 예: '선수 평가 데이터를 관찰 메모와 연결하여 종합 분석할 수 있는 구조가 필요하였으나, 기존 데이터 모델에는 해당 연관관계가 정의되어 있지 않았습니다.'\n\n" +
            "### 문제 해결 과정\n" +
            "- 실제 수행한 작업을 번호 목록(1. 2. 3.) 형태로 단계별로 정리합니다.\n" +
            "- 각 단계는 소제목(볼드)과 1~2문장의 핵심 설명으로 구성합니다.\n" +
            "- 각 단계에서 '어떤 목적으로 무엇을 추가/수정/삭제했는지' 구체적으로 서술하세요.\n" +
            "- 나쁜 예: '1. **데이터 모델 설계** - 스키마를 적용하였습니다.' (무슨 스키마인지, 왜 필요했는지 알 수 없음)\n" +
            "- 좋은 예: '1. **관찰 태그 데이터 모델 설계** - 스카우트 관찰 기록을 태그 기반으로 분류하기 위해 observation_tag 테이블을 신규 설계하고, observation 테이블과의 연관관계를 정의하였습니다.'\n" +
            "- DB 마이그레이션이 포함된 경우 어떤 테이블/컬럼이 변경되었는지 핵심 내용을 포함합니다.\n" +
            "- 삭제된 기능이 있으면 대체된 새로운 구현을 함께 언급합니다.\n" +
            "- 주의: 개별 기능의 세부 변경 목록은 시스템이 자동 삽입하므로, 기능명만 나열하지 말고 과정 중심으로 서술하세요.\n\n" +
            "### 결과\n" +
            "- 변경 작업의 최종 결과를 불릿 목록으로 요약합니다.\n" +
            "- 각 항목에 실제 변경된 기능/테이블/API를 구체적으로 명시하세요.\n" +
            "- 나쁜 예: '- 코드 관련 변경 3건을 완료하였습니다.' (어떤 변경인지 알 수 없음)\n" +
            "- 좋은 예: '- 스카우트 관찰 태그 조회·등록·삭제 API를 구현하여 관찰 기록의 태그 기반 분류가 가능해졌습니다.'\n" +
            "- 영향받는 시스템 구성요소(테이블, API, 모듈)를 포함합니다.\n" +
            "- 정상 동작 확인 여부가 있으면 포함합니다.\n\n" +
            "### 개선 및 예방 방안\n" +
            "- 이번 변경으로 인해 추가로 확인하거나 조치해야 할 사항을 기술합니다.\n" +
            "- 구체적인 변경 내용에 기반하여 후속 조치를 도출하세요.\n" +
            "- 나쁜 예: '- 고위험 변경 사항에 대해 모니터링이 필요합니다.' (무엇을 모니터링하는지 불명)\n" +
            "- 좋은 예: '- **observation_tag 테이블 인덱스 모니터링**: 태그 조회 쿼리의 성능을 확인하고, 데이터 증가에 따른 인덱스 효율성을 점검할 필요가 있습니다.'\n" +
            "- 고위험(HIGH) 변경이 있는 경우 해당 항목에 대한 주의사항을 반드시 포함합니다.\n" +
            "- 해당 사항이 없으면 '별도의 후속 조치가 필요하지 않습니다.'로 작성합니다.\n\n" +
            "## 금지 사항\n" +
            "- 원본에 없는 사실이나 추측을 추가하지 마세요.\n" +
            "- 코드 diff 원문이나 변경 줄 수(+N/-N)를 그대로 노출하지 마세요.\n" +
            "- 파일명(*.java, *.xml 등), 클래스명, 패키지 경로를 보고서에 직접 나열하지 마세요.\n" +
            "- '파일 N개 변경' 같은 파일 수 중심 서술을 하지 마세요. 기능 변경 중심으로 서술하세요.\n" +
            "- 섹션 제목 앞에 번호를 붙이지 마세요 (### 목적, ### 발생한 문제 형태 유지).\n" +
            "- '변경이 이루어졌습니다', '수정이 필요하였습니다' 같이 구체적인 내용 없는 빈 문장을 절대 사용하지 마세요.";
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
            sb.append("개별 레포지토리명(예: football-api, football-web 등)을 직접 언급하지 말고, ");
            sb.append("'본 프로젝트', '시스템' 등의 통합된 관점으로 서술하세요.\n");
            sb.append("서버 측 변경과 화면 측 변경이 있다면 하나의 기능 개선 흐름으로 자연스럽게 통합하여 서술하세요.\n\n");
        } else {
            sb.append("## 중요: 레포지토리별 구분 서술\n");
            sb.append("이 보고서는 각 레포지토리(서버, 프론트엔드 등)의 변경 사항을 **구분하여** 작성해야 합니다.\n");
            sb.append("각 레포지토리에서 발생한 변경을 별도로 서술하되, 관련 있는 변경끼리 인과관계를 명시하세요.\n\n");
        }
        sb.append("위 정보를 참고하여 '보고서 초안'을 고객사·경영진에게 제출할 수 있는 공식 보고서 수준으로 다듬어 주세요.\n");
        sb.append("반드시 시스템 프롬프트의 '출력 구조'에 명시된 6개 섹션(목적, 발생한 문제, 문제 원인, 문제 해결 과정, 결과, 개선 및 예방 방안)을 모두 ### 제목으로 구분하여 포함하세요.\n");
        sb.append("영향 범위 정보와 고위험 변경 사항은 '결과' 및 '개선 및 예방 방안' 섹션에 자연스럽게 반영하세요.\n");
        sb.append("결과물은 Markdown 본문만 출력하세요. 부가 설명이나 인사말은 포함하지 마세요.");
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
