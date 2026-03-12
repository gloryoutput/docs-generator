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
            "## 핵심 원칙: 구체적 대상이 드러나는 서술\n" +
            "- 모든 문장에 '무엇이 어떻게 변경되었는지' 구체적 대상을 반드시 포함하세요.\n" +
            "- 대상 없이 액션만 서술하는 제네릭 문장을 절대 사용하지 마세요.\n" +
            "- '~하기 위한', '~할 수 있도록', '효율화를 위한', '체계적으로' 같은 의미 없는 목적 수식어를 붙이지 마세요.\n" +
            "- 나쁜 예: '관리 기능을 신규 추가하였습니다', '기능 개선이 필요하였습니다' (대상 없음)\n" +
            "- 좋은 예: '스카우트 관찰 기록의 태그 기반 분류 기능을 추가하였습니다' (구체적 대상 + 행위)\n\n" +
            "## 작성 원칙\n" +
            "1. **전문적 어조**: 공식 업무 보고서에 적합한 격식체 한국어를 사용합니다 (~했습니다, ~되었습니다).\n" +
            "2. **기술적 정확성**: 원본 정보의 기술적 사실을 변경하지 않으며, 새로운 정보를 추가하지 않습니다.\n" +
            "3. **구체적 대상 서술**: 파일명, 클래스명, 패키지 경로를 직접 나열하지 않되, 변경된 기능/테이블/API의 이름을 반드시 명시합니다.\n" +
            "4. **영향도 강조**: 각 변경이 시스템에 미치는 영향과 의미를 명확히 전달합니다.\n" +
            "5. **Markdown 형식**: 제목(###), 볼드(**), 불릿(-) 등 Markdown 서식을 사용합니다.\n" +
            "6. **구체적 데이터 필수**: 모든 섹션에서 반드시 실제 변경 이벤트의 데이터(변경된 기능명, 테이블명, API 경로, 변경 내용)를 인용하여 서술하세요. " +
            "'변경이 필요하였습니다', '수정이 이루어졌습니다' 같은 막연한 문장만으로는 부족합니다. " +
            "반드시 '무엇이', '어떻게' 변경되었는지 구체적으로 명시하세요.\n\n" +
            "## 출력 구조 (반드시 아래 6개 섹션을 ### 제목으로 구분하여 순서대로 출력하세요)\n\n" +
            "### 목적\n" +
            "- 본 보고서의 작성 목적을 3~4문장으로 상세히 서술합니다.\n" +
            "- 이번 변경의 핵심 주제(어떤 기능/모듈이 왜 변경되었는지)를 구체적으로 명시하세요.\n" +
            "- 변경의 규모(총 건수, 주요 유형별 건수)를 포함하세요.\n" +
            "- 나쁜 예: '본 보고서는 소프트웨어 변경 작업의 과정을 정리하고 결과를 공유하기 위해 작성되었습니다.' (이런 뻔한 문장은 금지)\n" +
            "- 좋은 예: '본 보고서는 ○○ 시스템에 스카우트 평가 기능을 신규 추가하고, 선수 관리 API를 개편한 작업의 배경과 결과를 정리한 문서입니다. 해당 기간에 DB 스키마 변경 3건, API 변경 5건, 코드 변경 10건이 수행되었으며, 각 변경의 배경과 진행 과정, 결과 및 후속 조치 사항을 정리하였습니다.'\n" +
            "- 변경 이벤트 데이터에서 주요 기능명, 모듈명을 추출하여 목적에 반영하세요.\n\n" +
            "### 발생한 문제\n" +
            "- 변경이 필요하게 된 배경 또는 기존 시스템의 문제점/요구사항을 상세히 서술합니다.\n" +
            "- 각 이벤트에 대해 '**이벤트 제목**: 구체적 설명' 형식으로, 어떤 기능이 왜 필요했는지 2~3줄로 상세하게 설명하세요.\n" +
            "- DB 스키마 변경이 있으면 해당 테이블명과 변경 이유(신규 테이블, 컬럼 추가 등)를 명시하세요.\n" +
            "- API 변경이 있으면 해당 엔드포인트와 변경 유형(추가/수정/삭제)을 명시하세요.\n" +
            "- 코드 변경이 있으면 해당 기능명과 구체적 부족 사항을 명시하세요.\n" +
            "- 나쁜 예: '기능이 부재하여 추가가 필요하였습니다' (무슨 기능인지 불명)\n" +
            "- 좋은 예: '- **스카우트 관찰 태그 분류 기능 추가**: 스카우트 관찰 기록을 태그 기반으로 분류하는 기능이 없어, 대량의 관찰 데이터에서 특정 항목을 검색하기 어려웠습니다. 태그 기반 분류 체계를 도입하여 관찰 기록의 검색 및 필터링 효율을 높일 필요가 있었습니다.'\n\n" +
            "### 문제 원인\n" +
            "- 위 문제가 발생한 원인 또는 변경이 필요하게 된 기술적 근거를 이벤트별로 상세히 분석합니다.\n" +
            "- 각 이벤트에 대해 이벤트 제목을 볼드로 표시하고, 하위 불릿으로 description에서 추출한 구체적 원인을 2~3줄 서술하세요.\n" +
            "- 기능 추가인 경우, 어떤 업무 요구사항이 있었는지 이벤트 데이터에서 유추하여 서술합니다.\n" +
            "- 관련된 변경끼리 묶어서 인과관계를 설명합니다.\n" +
            "- 예:\n" +
            "  - **스카우트 관찰 태그 분류 기능 추가**\n" +
            "    - 기존 시스템에서 관찰 기록을 분류할 수 있는 구조가 없어 검색이 비효율적이었습니다.\n" +
            "    - 태그를 관찰 기록에 연결하는 데이터 모델이 존재하지 않아 신규 설계가 필요하였습니다.\n\n" +
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
            "- '상기 작업을 통해 다음과 같은 변경이 완료되었습니다.'로 시작하세요.\n" +
            "- 변경 이벤트 하나당 한 줄로 서술하되, 이벤트 제목을 볼드로 표시하고 구체적인 완료 내용을 함께 기재하세요.\n" +
            "- 형식: '- **{이벤트 title}** — {description에서 추출한 핵심 변경 내용} (완료)'\n" +
            "- 나쁜 예: '코드 관련 변경 3건을 완료하였습니다' (카테고리로 묶지 마세요)\n" +
            "- 나쁜 예: '- 스카우트 관찰 태그 조회 API 추가 — 완료' (구체적 내용 없이 '완료'만 붙이지 마세요)\n" +
            "- 좋은 예:\n" +
            "  - **스카우트 관찰 태그 조회 API 추가** — 관찰 기록을 태그 기반으로 분류·조회하는 API를 신규 추가 (완료)\n" +
            "  - **player 테이블 컬럼 추가** — 선수 부상 이력 관리를 위한 injury_history 컬럼 추가 (완료)\n\n" +
            "### 개선 및 예방 방안\n" +
            "- '이번 변경과 관련하여 다음 사항에 대한 후속 점검이 필요합니다.'로 시작하세요.\n" +
            "- 이벤트별로 볼드 제목과 함께 구체적인 후속 조치 사유를 상세히 기술합니다.\n" +
            "- 형식: '- **{변경 대상}** — {구체적 사유와 필요한 조치}'\n" +
            "- 고위험(HIGH): '- **{title}** — 심각도가 높은 변경으로, 배포 후 해당 기능의 정상 동작 여부를 반드시 확인하여야 합니다.'\n" +
            "- 스키마 변경: '- **{테이블명}** — 데이터베이스 스키마가 변경되었으므로, 기존 데이터의 정합성 및 관련 쿼리·인덱스의 정상 동작을 확인하여야 합니다.'\n" +
            "- API 수정/삭제: '- **{엔드포인트}** — API가 변경되었으므로, 해당 API를 호출하는 클라이언트의 호환성을 확인하여야 합니다.'\n" +
            "- 해당 사항이 없으면 '- 이번 변경은 기존 기능에 대한 영향이 제한적이므로, 별도의 후속 조치가 필요하지 않습니다.'\n\n" +
            "## 금지 사항\n" +
            "- 원본에 없는 사실이나 추측을 추가하지 마세요.\n" +
            "- 코드 diff 원문이나 변경 줄 수(+N/-N)를 그대로 노출하지 마세요.\n" +
            "- 파일명(*.java, *.xml 등), 클래스명, 패키지 경로를 보고서에 직접 나열하지 마세요.\n" +
            "- '파일 N개 변경' 같은 파일 수 중심 서술을 하지 마세요. 기능 변경 중심으로 서술하세요.\n" +
            "- 섹션 제목 앞에 번호를 붙이지 마세요 (### 목적, ### 발생한 문제 형태 유지).\n" +
            "- '변경이 이루어졌습니다', '수정이 필요하였습니다', '기능이 부재하여', '개선이 필요하였습니다' 같이 구체적 대상 없는 빈 문장을 절대 사용하지 마세요.\n" +
            "- '~하기 위한', '~할 수 있도록', '체계적으로 관리하기 위해', '효율화를 위한' 같은 의미 없는 목적 수식어를 붙이지 마세요.";
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
