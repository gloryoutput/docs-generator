package io.github.gloryoutput.docsgenerator.service;

import io.github.gloryoutput.docsgenerator.analyzer.api.ApiAnalyzerResult;
import io.github.gloryoutput.docsgenerator.analyzer.database.DbSchemaResult;
import io.github.gloryoutput.docsgenerator.analyzer.git.GitDiffResult;
import io.github.gloryoutput.docsgenerator.domain.changeevent.ChangeEvent;
import io.github.gloryoutput.docsgenerator.domain.changeevent.ChangeEventRepository;
import io.github.gloryoutput.docsgenerator.service.RuleEngineService.AnalysisContext;
import io.github.gloryoutput.docsgenerator.service.RuleEngineService.ChangeEventTemplate;
import io.github.gloryoutput.docsgenerator.service.RuleEngineService.MatchedRule;
import io.github.gloryoutput.docsgenerator.summarizer.LlmChangeEventEnhancerService;
import io.github.gloryoutput.docsgenerator.summarizer.LlmDescriptionCompressorService;
import io.github.gloryoutput.docsgenerator.util.LayerDetector;
import io.github.gloryoutput.docsgenerator.util.ReportNoiseFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/**
 * 분석 결과로부터 변경 이벤트를 생성하는 서비스
 *
 * <p>Git diff, DB 스키마, API endpoint 분석 결과를 기반으로
 * 규칙 엔진을 통한 크로스 레이어 분석, 유사 이벤트 병합,
 * 제목 정규화를 적용하여 변경 이벤트를 도출합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChangeEventService {
    private final ChangeEventRepository changeEventRepository;
    private final RuleEngineService ruleEngineService;
    private final LlmChangeEventEnhancerService llmChangeEventEnhancerService;
    private final LlmDescriptionCompressorService llmDescriptionCompressorService;

    /**
     * 분석 결과로부터 변경 이벤트를 생성하고 저장합니다.
     *
     * <p>Section 12 알고리즘에 따라 다음 순서로 처리합니다:
     * 1) AnalysisContext 구성 (레이어 판별, 키워드 추출)
     * 2) 규칙 엔진 매칭
     * 3) 규칙 기반 이벤트 생성 + 미매칭 증거의 폴백 이벤트 생성
     * 4) 유사 이벤트 병합
     * 5) 제목/설명 정규화
     * 6) LLM 기반 이벤트 강화 (제목/설명/심각도 개선)</p>
     *
     * @param idAnalysisRequest 분석 요청 ID
     * @param idProject 프로젝트 ID
     * @param gitResults Git diff 분석 결과 목록
     * @param schemaResults DB 스키마 분석 결과 목록
     * @param apiResult API endpoint 분석 결과
     * @return 생성된 변경 이벤트 목록
     */
    @Transactional
    public List<ChangeEvent> buildChangeEvents(UUID idAnalysisRequest, UUID idProject,
                                                List<GitDiffResult> gitResults,
                                                List<DbSchemaResult> schemaResults,
                                                ApiAnalyzerResult apiResult,
                                                boolean mergeRepositories) {
        // 1. AnalysisContext 구성
        AnalysisContext context = buildAnalysisContext(gitResults, schemaResults, apiResult);
        log.info("AnalysisContext 구성 완료 - layers: {}, sourceTypes: {}, keyword: {}",
                context.getPresentLayers(), context.getPresentSourceTypes(), context.getPrimaryKeyword());
        // 2. 규칙 엔진 매칭
        List<MatchedRule> matchedRules = ruleEngineService.matchRules(context);
        // 3. 규칙 기반 이벤트 생성
        List<ChangeEvent> events = new ArrayList<>();
        Set<String> coveredCategories = new HashSet<>();
        if (!matchedRules.isEmpty()) {
            events.addAll(buildRuleBasedEvents(idAnalysisRequest, idProject, matchedRules, context.getPrimaryKeyword()));
            for (MatchedRule rule : matchedRules) {
                coveredCategories.add(rule.getResolvedCategory());
            }
        }
        // 4. 폴백: 규칙으로 커버되지 않은 증거에 대해 기존 로직 적용
        if (!coveredCategories.contains("SCHEMA_CHANGE")) {
            for (DbSchemaResult schemaResult : schemaResults) {
                if (schemaResult.getChanges() == null) continue;
                for (DbSchemaResult.SchemaChange change : schemaResult.getChanges()) {
                    ChangeEvent event = buildSchemaChangeEvent(idAnalysisRequest, idProject, change);
                    if (event != null) {
                        events.add(event);
                    }
                }
            }
        }
        if (!coveredCategories.contains("API_CHANGE")) {
            if (apiResult != null && apiResult.getChanges() != null) {
                for (ApiAnalyzerResult.EndpointChange change : apiResult.getChanges()) {
                    ChangeEvent event = buildApiChangeEvent(idAnalysisRequest, idProject, change);
                    if (event != null) {
                        events.add(event);
                    }
                }
            }
        }
        if (!coveredCategories.contains("CODE_CHANGE")) {
            events.addAll(buildCodeChangeEvents(idAnalysisRequest, idProject, gitResults, mergeRepositories));
        }
        // 5. 유사 이벤트 병합
        events = mergeEvents(events);
        // 6. 제목 정규화
        for (ChangeEvent event : events) {
            // ChangeEvent는 불변이므로 정규화된 제목으로 새로 생성하지 않고,
            // 빌드 시점에 정규화를 적용하기 위해 이 단계에서는 리스트를 재구성
        }
        events = normalizeEventTitles(events);
        // 7. LLM 기반 이벤트 강화 (제목/설명/심각도 개선)
        events = llmChangeEventEnhancerService.enhance(events, idAnalysisRequest, idProject, mergeRepositories);
        if (!events.isEmpty()) {
            changeEventRepository.saveAll(events);
            log.info("변경 이벤트 {}건 생성 완료 (분석 요청: {})", events.size(), idAnalysisRequest);
        }
        return events;
    }

    /**
     * 모든 분석 입력으로부터 AnalysisContext를 구성합니다.
     *
     * <p>Git 파일 경로에서 레이어를 판별하고, 키워드를 추출하여
     * 가장 빈도 높은 키워드를 primaryKeyword로 설정합니다.</p>
     *
     * @param gitResults Git diff 분석 결과 목록
     * @param schemaResults DB 스키마 분석 결과 목록
     * @param apiResult API endpoint 분석 결과
     * @return 구성된 분석 컨텍스트
     */
    private AnalysisContext buildAnalysisContext(List<GitDiffResult> gitResults,
                                                 List<DbSchemaResult> schemaResults,
                                                 ApiAnalyzerResult apiResult) {
        // 모든 파일 경로 수집
        List<String> allFilePaths = new ArrayList<>();
        for (GitDiffResult gitResult : gitResults) {
            if (gitResult.getCommits() != null) {
                for (GitDiffResult.CommitInfo commit : gitResult.getCommits()) {
                    if (commit.getFileChanges() != null) {
                        for (GitDiffResult.FileChange fc : commit.getFileChanges()) {
                            if (fc.getFilePath() != null) {
                                allFilePaths.add(fc.getFilePath());
                            }
                        }
                    }
                }
            }
        }
        // LayerDetector로 레이어 판별
        Set<String> presentLayers = LayerDetector.detectLayers(allFilePaths);
        boolean hasQueryPattern = LayerDetector.containsQueryPattern(allFilePaths);
        // 키워드 추출 후 가장 빈번한 키워드 선택
        Map<String, Integer> keywordFrequency = new HashMap<>();
        for (String filePath : allFilePaths) {
            List<String> keywords = LayerDetector.extractKeywords(filePath);
            for (String keyword : keywords) {
                keywordFrequency.merge(keyword, 1, Integer::sum);
            }
        }
        String primaryKeyword = keywordFrequency.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
        // 소스 타입 결정
        Set<String> presentSourceTypes = new LinkedHashSet<>();
        Set<String> presentChangeTypes = new LinkedHashSet<>();
        boolean hasSchemaChange = false;
        boolean hasApiChange = false;
        boolean hasCodeChange = false;
        for (DbSchemaResult schema : schemaResults) {
            if (schema.getChanges() != null && !schema.getChanges().isEmpty()) {
                hasSchemaChange = true;
                presentSourceTypes.add("DB_SCHEMA");
                for (DbSchemaResult.SchemaChange change : schema.getChanges()) {
                    presentChangeTypes.add(change.getChangeType());
                }
            }
        }
        if (apiResult != null && apiResult.getChanges() != null && !apiResult.getChanges().isEmpty()) {
            hasApiChange = true;
            presentSourceTypes.add("API_ENDPOINT");
            for (ApiAnalyzerResult.EndpointChange change : apiResult.getChanges()) {
                presentChangeTypes.add(change.getChangeType());
            }
        }
        if (!gitResults.isEmpty()) {
            for (GitDiffResult git : gitResults) {
                if (git.getCommits() != null && !git.getCommits().isEmpty()) {
                    hasCodeChange = true;
                    presentSourceTypes.add("GIT");
                    break;
                }
            }
        }
        return AnalysisContext.builder()
                .presentSourceTypes(presentSourceTypes)
                .presentLayers(presentLayers)
                .presentChangeTypes(presentChangeTypes)
                .hasApiChange(hasApiChange)
                .hasSchemaChange(hasSchemaChange)
                .hasCodeChange(hasCodeChange)
                .hasQueryPattern(hasQueryPattern)
                .primaryKeyword(primaryKeyword)
                .build();
    }

    /**
     * 매칭된 규칙으로부터 크로스 레이어 변경 이벤트를 생성합니다.
     *
     * @param idAnalysisRequest 분석 요청 ID
     * @param idProject 프로젝트 ID
     * @param matchedRules 매칭된 규칙 목록
     * @param primaryKeyword 치환에 사용할 주요 키워드
     * @return 규칙 기반 변경 이벤트 목록
     */
    private List<ChangeEvent> buildRuleBasedEvents(UUID idAnalysisRequest, UUID idProject,
                                                    List<MatchedRule> matchedRules, String primaryKeyword) {
        List<ChangeEvent> events = new ArrayList<>();
        for (MatchedRule matched : matchedRules) {
            ChangeEventTemplate template = ruleEngineService.buildEventFromRule(matched, primaryKeyword);
            events.add(ChangeEvent.builder()
                    .idAnalysisRequest(idAnalysisRequest)
                    .idProject(idProject)
                    .category(template.getCategory())
                    .title(template.getTitle())
                    .description(template.getDescription())
                    .severity(template.getSeverity())
                    .confidenceScore(template.getConfidenceScore())
                    .sourceType("RULE_ENGINE")
                    .correlationKey(matched.getRule().getRuleCode())
                    .build());
            log.debug("규칙 기반 이벤트 생성: {} (규칙: {})", template.getTitle(), matched.getRule().getRuleCode());
        }
        return events;
    }

    /**
     * 유사 이벤트를 병합합니다.
     *
     * <p>같은 correlationKey와 category를 가진 이벤트를 그룹화하여,
     * 설명을 합치고 confidenceScore가 높은 이벤트의 제목과 심각도를 유지합니다.</p>
     *
     * @param events 병합 전 이벤트 목록
     * @return 병합된 이벤트 목록
     */
    private List<ChangeEvent> mergeEvents(List<ChangeEvent> events) {
        if (events.size() <= 1) {
            return events;
        }
        // correlationKey + category 기준으로 그룹화
        Map<String, List<ChangeEvent>> grouped = new LinkedHashMap<>();
        for (ChangeEvent event : events) {
            String key = (event.getCorrelationKey() != null ? event.getCorrelationKey() : "")
                    + "::" + event.getCategory();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(event);
        }
        List<ChangeEvent> merged = new ArrayList<>();
        for (List<ChangeEvent> group : grouped.values()) {
            if (group.size() == 1) {
                merged.add(group.get(0));
                continue;
            }
            ChangeEvent primary = group.get(0);
            for (ChangeEvent event : group) {
                if (event.getConfidenceScore() > primary.getConfidenceScore()) {
                    primary = event;
                }
            }
            String mergedDescription = buildMergedDescription(group);
            // 심각도는 가장 높은 것을 채택
            String maxSeverity = primary.getSeverity();
            for (ChangeEvent event : group) {
                if (severityRank(event.getSeverity()) > severityRank(maxSeverity)) {
                    maxSeverity = event.getSeverity();
                }
            }
            merged.add(ChangeEvent.builder()
                    .idAnalysisRequest(primary.getIdAnalysisRequest())
                    .idProject(primary.getIdProject())
                    .category(primary.getCategory())
                    .title(primary.getTitle())
                    .description(mergedDescription)
                    .severity(maxSeverity)
                    .confidenceScore(primary.getConfidenceScore())
                    .sourceType(primary.getSourceType())
                    .correlationKey(primary.getCorrelationKey())
                    .build());
        }
        if (merged.size() < events.size()) {
            log.info("유사 이벤트 병합: {}건 → {}건", events.size(), merged.size());
        }
        return merged;
    }
    /**
     * 병합 대상 이벤트들의 description을 통합합니다.
     *
     * <p>각 이벤트의 description을 줄바꿈으로 구분하여 나열합니다.
     * 중복 description은 제거합니다.</p>
     */
    private String buildMergedDescription(List<ChangeEvent> group) {
        Set<String> seen = new LinkedHashSet<>();
        for (ChangeEvent event : group) {
            if (event.getDescription() != null && !event.getDescription().isBlank()) {
                seen.add(event.getDescription());
            }
        }
        return String.join("\n", seen);
    }
    /**
     * 심각도 순위를 반환합니다 (HIGH=3, MEDIUM=2, LOW=1).
     */
    private int severityRank(String severity) {
        if (severity == null) return 0;
        return switch (severity) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    /**
     * 이벤트 제목을 정규화하여 새 이벤트 목록을 반환합니다.
     *
     * <p>ChangeEvent가 불변이므로 정규화된 제목으로 새 이벤트를 생성합니다.</p>
     *
     * @param events 정규화 전 이벤트 목록
     * @return 제목이 정규화된 이벤트 목록
     */
    private List<ChangeEvent> normalizeEventTitles(List<ChangeEvent> events) {
        List<ChangeEvent> normalized = new ArrayList<>(events.size());
        for (ChangeEvent event : events) {
            String normalizedTitle = normalizeTitle(event.getTitle());
            if (normalizedTitle.equals(event.getTitle())) {
                normalized.add(event);
            } else {
                normalized.add(ChangeEvent.builder()
                        .idAnalysisRequest(event.getIdAnalysisRequest())
                        .idProject(event.getIdProject())
                        .category(event.getCategory())
                        .title(normalizedTitle)
                        .description(event.getDescription())
                        .severity(event.getSeverity())
                        .confidenceScore(event.getConfidenceScore())
                        .sourceType(event.getSourceType())
                        .correlationKey(event.getCorrelationKey())
                        .build());
            }
        }
        return normalized;
    }

    /**
     * 이벤트 제목을 정규화합니다.
     *
     * <p>앞뒤 공백 제거, 100자 초과 시 말줄임, 일관된 한국어 문장 종결 처리를 수행합니다.</p>
     *
     * @param title 정규화 대상 제목
     * @return 정규화된 제목
     */
    private String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "제목 없음";
        }
        String normalized = title.trim();
        // 100자 초과 시 말줄임 처리
        if (normalized.length() > 100) {
            normalized = normalized.substring(0, 97) + "...";
        }
        return normalized;
    }

    /**
     * DB 스키마 변경으로부터 변경 이벤트를 생성합니다.
     *
     * <p>테이블명, 컬럼명, 인덱스명 등 기술 식별자를 한국어 도메인 용어로 변환하여
     * 비개발자가 읽을 수 있는 자연어 제목과 설명을 생성합니다.</p>
     */
    private ChangeEvent buildSchemaChangeEvent(UUID idAnalysisRequest, UUID idProject,
                                                DbSchemaResult.SchemaChange change) {
        String title;
        String severity;
        String correlationKey = change.getTableName();
        String description;
        String tableKr = translateSnakeCase(change.getTableName());
        switch (change.getChangeType()) {
            case "TABLE_ADDED":
                title = tableKr + " 데이터 관리 테이블 추가";
                severity = "HIGH";
                description = tableKr + " 정보를 관리하기 위한 테이블 추가";
                break;
            case "TABLE_REMOVED":
                title = tableKr + " 데이터 테이블 삭제";
                severity = "HIGH";
                description = tableKr + " 관련 테이블 삭제";
                break;
            case "COLUMN_ADDED":
                title = tableKr + " 관리 항목 추가";
                severity = "MEDIUM";
                description = tableKr + " 테이블에 신규 관리 항목 추가";
                break;
            case "COLUMN_REMOVED":
                title = tableKr + " 관리 항목 삭제";
                severity = "HIGH";
                description = tableKr + " 테이블에서 관리 항목 삭제";
                break;
            case "COLUMN_TYPE_CHANGED":
                title = tableKr + " 관리 항목 형식 변경";
                severity = "HIGH";
                description = tableKr + " 테이블 관리 항목의 데이터 형식 변경";
                break;
            case "INDEX_ADDED":
                title = tableKr + " 조회 성능 개선";
                severity = "LOW";
                description = tableKr + " 테이블 조회 성능 향상을 위한 색인 추가";
                break;
            case "INDEX_REMOVED":
                title = tableKr + " 불필요 색인 제거";
                severity = "LOW";
                description = tableKr + " 테이블에서 불필요한 색인 제거";
                break;
            default:
                log.warn("알 수 없는 스키마 변경 타입: {}", change.getChangeType());
                return null;
        }
        return ChangeEvent.builder()
                .idAnalysisRequest(idAnalysisRequest)
                .idProject(idProject)
                .category("SCHEMA_CHANGE")
                .title(title)
                .description(description)
                .severity(severity)
                .confidenceScore(1.0)
                .sourceType("DB_SCHEMA")
                .correlationKey(correlationKey)
                .build();
    }

    /**
     * API endpoint 변경으로부터 변경 이벤트를 생성합니다.
     *
     * <p>HTTP 메서드와 경로를 한국어 동작과 리소스명으로 변환하여
     * "스카우트 조회 기능 추가" 형태의 자연어 제목을 생성합니다.</p>
     */
    private ChangeEvent buildApiChangeEvent(UUID idAnalysisRequest, UUID idProject,
                                             ApiAnalyzerResult.EndpointChange change) {
        String title;
        String severity;
        String description;
        String correlationKey = extractApiPrefix(change.getPath());
        String resource = extractResourceFromPath(change.getPath());
        String action = httpMethodToAction(change.getHttpMethod());
        switch (change.getChangeType()) {
            case "ENDPOINT_ADDED":
                title = resource + " " + action + " 기능 추가";
                severity = "MEDIUM";
                description = resource + " " + action + " 기능 추가";
                break;
            case "ENDPOINT_REMOVED":
                title = resource + " " + action + " 기능 삭제";
                severity = "HIGH";
                description = resource + " " + action + " 기능 삭제";
                break;
            default:
                log.warn("알 수 없는 API 변경 타입: {}", change.getChangeType());
                return null;
        }
        return ChangeEvent.builder()
                .idAnalysisRequest(idAnalysisRequest)
                .idProject(idProject)
                .category("API_CHANGE")
                .title(title)
                .description(description)
                .severity(severity)
                .confidenceScore(1.0)
                .sourceType("API_ENDPOINT")
                .correlationKey(correlationKey)
                .build();
    }

    /**
     * Git diff 결과에서 코드 변경 이벤트를 레포지토리 단위로 생성합니다.
     *
     * <p>같은 레포지토리의 여러 커밋을 하나의 이벤트로 통합하여,
     * 중복 파일을 제거하고 커밋 이력/레이어/키워드를 구조화합니다.</p>
     */
    private List<ChangeEvent> buildCodeChangeEvents(UUID idAnalysisRequest, UUID idProject,
                                                     List<GitDiffResult> gitResults,
                                                     boolean mergeRepositories) {
        List<ChangeEvent> events = new ArrayList<>();
        if (mergeRepositories) {
            // 병합 모드: 모든 레포지토리의 커밋을 하나의 이벤트로 통합
            List<GitDiffResult.CommitInfo> allValidCommits = new ArrayList<>();
            List<String> repoNames = new ArrayList<>();
            for (GitDiffResult gitResult : gitResults) {
                if (gitResult.getCommits() == null || gitResult.getCommits().isEmpty()) continue;
                List<GitDiffResult.CommitInfo> validCommits = gitResult.getCommits().stream()
                        .filter(c -> c.getFileChanges() != null && !c.getFileChanges().isEmpty())
                        .toList();
                if (!validCommits.isEmpty()) {
                    allValidCommits.addAll(validCommits);
                    repoNames.add(gitResult.getRepositoryName());
                }
            }
            if (!allValidCommits.isEmpty()) {
                String mergedRepoName = String.join(", ", repoNames);
                String description = buildRepoDescription(mergedRepoName, allValidCommits);
                String title = buildTitleFromDescription(description, allValidCommits.size());
                events.add(ChangeEvent.builder()
                        .idAnalysisRequest(idAnalysisRequest)
                        .idProject(idProject)
                        .category("CODE_CHANGE")
                        .title(title)
                        .description(description)
                        .severity("LOW")
                        .confidenceScore(0.8)
                        .sourceType("GIT")
                        .correlationKey("PROJECT")
                        .build());
            }
        } else {
            // 분리 모드: 레포지토리별 개별 이벤트 생성
            for (GitDiffResult gitResult : gitResults) {
                if (gitResult.getCommits() == null || gitResult.getCommits().isEmpty()) continue;
                String repoName = gitResult.getRepositoryName();
                List<GitDiffResult.CommitInfo> validCommits = gitResult.getCommits().stream()
                        .filter(c -> c.getFileChanges() != null && !c.getFileChanges().isEmpty())
                        .toList();
                if (validCommits.isEmpty()) continue;
                String description = buildRepoDescription(repoName, validCommits);
                String title = buildTitleFromDescription(description, validCommits.size());
                events.add(ChangeEvent.builder()
                        .idAnalysisRequest(idAnalysisRequest)
                        .idProject(idProject)
                        .category("CODE_CHANGE")
                        .title(title)
                        .description(description)
                        .severity("LOW")
                        .confidenceScore(0.8)
                        .sourceType("GIT")
                        .correlationKey(repoName)
                        .build());
            }
        }
        return events;
    }
    /**
     * description에서 실제 변경 항목을 추출하여 구체적인 제목을 생성합니다.
     *
     * <p>description의 "- " 항목들 중 주요 내용을 조합하여
     * "스카우트 관리 항목 추가, 날씨 조회 기능 추가 등" 형태의 제목을 만듭니다.</p>
     */
    private String buildTitleFromDescription(String description, int commitCount) {
        if (description == null || description.isBlank()) {
            return "소프트웨어 변경 (" + commitCount + "건)";
        }
        // "- " 항목에서 한국어가 포함된 실제 변경 내용만 추출
        List<String> items = new ArrayList<>();
        for (String line : description.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- ")) {
                String item = trimmed.substring(2).trim();
                if (item.length() >= 3 && item.matches(".*[가-힣].*")) {
                    items.add(item);
                }
            }
        }
        if (items.isEmpty()) {
            return "소프트웨어 변경 (" + commitCount + "건)";
        }
        // 최대 3개 항목만 표시, 초과 시 "등" 추가
        int displayCount = Math.min(items.size(), 3);
        String titleText = String.join(", ", items.subList(0, displayCount));
        if (items.size() > displayCount) {
            titleText += " 등";
        }
        return titleText;
    }
    /**
     * 레포지토리의 전체 커밋을 통합하여 기능 변경 중심의 description을 생성합니다.
     *
     * <p>파일 목록 나열 대신, 어떤 기능이 어떻게 변경되었는지를 중심으로 서술합니다.
     * 포함 정보: 작성자, 기간, 영향 레이어, 기능별 변경 내용</p>
     */
    private String buildRepoDescription(String repoName, List<GitDiffResult.CommitInfo> commits) {
        Set<String> authors = new LinkedHashSet<>();
        Set<String> allFilePaths = new LinkedHashSet<>();
        String earliestDate = null;
        String latestDate = null;
        for (GitDiffResult.CommitInfo commit : commits) {
            authors.add(commit.getAuthorName());
            String dt = commit.getDateTime();
            if (dt != null) {
                if (earliestDate == null || dt.compareTo(earliestDate) < 0) earliestDate = dt;
                if (latestDate == null || dt.compareTo(latestDate) > 0) latestDate = dt;
            }
            for (GitDiffResult.FileChange fc : commit.getFileChanges()) {
                if (fc.getFilePath() != null && !isGeneratedFile(fc.getFilePath())) {
                    allFilePaths.add(fc.getFilePath());
                }
            }
        }
        // 레이어/키워드 분석
        Set<String> layers = LayerDetector.detectLayers(new ArrayList<>(allFilePaths));
        Map<String, Integer> keywordFreq = new LinkedHashMap<>();
        for (String fp : allFilePaths) {
            for (String kw : LayerDetector.extractKeywords(fp)) {
                keywordFreq.merge(kw, 1, Integer::sum);
            }
        }
        List<String> topKeywords = keywordFreq.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .map(Map.Entry::getKey)
                .toList();
        // 기능별 변경 상세 수집 (changeSummary → 비개발자용 변환)
        // 설정/SQL/빌드 등 클라이언트가 몰라도 되는 파일은 기능별 변경에서 제외
        Map<String, Set<String>> changesByFeature = new LinkedHashMap<>();
        Set<String> processedFiles = new HashSet<>();
        for (GitDiffResult.CommitInfo commit : commits) {
            for (GitDiffResult.FileChange fc : commit.getFileChanges()) {
                if (fc.getFilePath() == null || isGeneratedFile(fc.getFilePath())
                        || ReportNoiseFilter.isInfraFile(fc.getFilePath())
                        || !processedFiles.add(fc.getFilePath())) continue;
                String featureArea = detectFeatureArea(fc.getFilePath());
                String changeSummary = fc.getChangeSummary();
                if (changeSummary != null && !changeSummary.isEmpty()) {
                    List<String> converted = convertToBusinessDescription(changeSummary, fc.getFilePath());
                    changesByFeature.computeIfAbsent(featureArea, k -> new LinkedHashSet<>()).addAll(converted);
                }
            }
        }
        // LLM으로 카테고리별 변경 내용 압축 (LLM 없으면 원본 카테고리 구조 유지)
        Map<String, List<String>> compressedByCategory = llmDescriptionCompressorService.compress(changesByFeature);
        // description 조립 (변경 내용 중심, 통계는 부록으로)
        StringBuilder sb = new StringBuilder();
        if (!compressedByCategory.isEmpty()) {
            for (Map.Entry<String, List<String>> entry : compressedByCategory.entrySet()) {
                if (entry.getValue().isEmpty()) continue;
                sb.append("[").append(entry.getKey()).append("]");
                for (String summary : entry.getValue()) {
                    sb.append("\n- ").append(summary);
                }
                sb.append("\n");
            }
        }
        return sb.toString().trim();
    }
    /**
     * 개발자용 changeSummary를 의도 기반 비즈니스 설명으로 변환합니다.
     *
     * <p>개별 필드명/메서드명을 나열하지 않고, 파일 경로에서 추출한 소속 엔티티 단위로
     * 의도를 압축합니다. 이를 통해 LLM에 전달되는 데이터가 의도 기반으로 사전 압축됩니다.</p>
     *
     * <p>변환 예시:
     * - 선수 상세 엔티티 파일 + 필드 추가 → "선수 상세 관리 항목 추가"
     * - 스카우트 후보 서비스 파일 + 저장소 참조 추가 → "날씨 데이터 연동 추가"
     * - 스카우트 후보 서비스 파일 + 메서드 추가 → "스카우트 후보 처리 기능 추가"
     * - 신규 파일 생성 → "스카우트 관찰 태그 조회 기능 신규 개발"</p>
     *
     * @param changeSummary Git diff에서 추출된 변경 요약
     * @param filePath 변경된 파일 경로 (엔티티명 추출에 사용)
     */
    private List<String> convertToBusinessDescription(String changeSummary, String filePath) {
        String entityName = extractEntityFromPath(filePath);
        List<String> results = new ArrayList<>();
        String[] parts = changeSummary.split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            if (ReportNoiseFilter.shouldFilterSummary(trimmed)) continue;
            // 필드 추가 → 의도 기반 압축: 개별 필드명 대신 소속 엔티티 단위로 표현
            if (trimmed.startsWith("추가 필드:")) {
                String fieldsPart = trimmed.substring("추가 필드:".length()).trim();
                String[] fieldNames = fieldsPart.split(",");
                List<String> repoEntityNames = new ArrayList<>();
                boolean hasRegularField = false;
                for (String fieldName : fieldNames) {
                    String fn = fieldName.trim();
                    if (fn.endsWith("Repository")) {
                        repoEntityNames.add(toReadableName(fn.replace("Repository", "")));
                    } else if (fn.endsWith("Service")) {
                        String name = toReadableName(fn.replace("Service", ""));
                        results.add(name + " 처리 연동");
                    } else {
                        hasRegularField = true;
                    }
                }
                // Repository 필드: 참조 엔티티 데이터 연동
                if (!repoEntityNames.isEmpty()) {
                    results.add(String.join(", ", repoEntityNames) + " 데이터 연동 추가");
                }
                // 일반 필드: 소속 엔티티 단위로 의도 압축 (주민번호, 여권번호 → 선수상세 필드 추가)
                if (hasRegularField) {
                    results.add(entityName != null ? entityName + " 관리 항목 추가" : "관리 항목 추가");
                }
                continue;
            }
            // 새 클래스 → 기능명 추출, 한국어로 변환 가능한 경우만 포함
            if (trimmed.startsWith("새 클래스:")) {
                String className = trimmed.substring("새 클래스:".length()).trim();
                String featureName = extractFeatureName(className);
                if (featureName.matches(".*[가-힣].*")) {
                    results.add(featureName + " 기능 신규 개발");
                }
                continue;
            }
            // 메서드 추가 → 의도 기반 압축: 개별 메서드 동작 대신 소속 엔티티 단위로 표현
            if (trimmed.startsWith("추가 메서드:")) {
                if (entityName != null) {
                    results.add(entityName + " 처리 기능 추가");
                } else {
                    String methodsPart = trimmed.substring("추가 메서드:".length()).trim();
                    for (String methodName : methodsPart.split(",")) {
                        String desc = convertMethodToAction(methodName.trim());
                        if (desc != null) results.add(desc);
                    }
                }
                continue;
            }
            // 그 외: 기술 용어 제거 후 한국어가 포함된 내용만 포함
            String cleaned = trimmed
                    .replaceAll("\\([+\\-\\d/\\s]+lines?\\)", "")
                    .replaceAll("@\\w+", "")
                    .replaceAll("\\b[A-Z][a-zA-Z]*(Service|Controller|Repository|Entity|Dto|Handler|Impl|Mapper|Converter)\\b", "")
                    .replaceAll("\\b[a-z]+\\.[a-z]+\\.[a-z.]+\\b", "")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (!cleaned.isEmpty() && cleaned.matches(".*[가-힣].*")) {
                results.add(cleaned);
            }
        }
        return results.stream().distinct().toList();
    }
    /**
     * 파일 경로에서 소속 엔티티명을 추출합니다.
     *
     * <p>파일명에서 클래스 접미사(Service, Controller 등)를 제거하고
     * 도메인 용어로 변환하여 비개발자가 이해할 수 있는 엔티티명을 반환합니다.
     * 한국어로 번역되지 않는 엔티티(shape, afc 등)는 비개발자에게 무의미하므로
     * null을 반환하여 출력에서 제외합니다.</p>
     *
     * <p>예: "PlayerDetailService.java" → "선수 상세",
     * "ScoutCandidateEntity.java" → "스카우트 후보",
     * "ShapeService.java" → null (번역 불가)</p>
     *
     * @param filePath 파일 경로
     * @return 한국어 엔티티명 또는 null (번역 불가 시)
     */
    private String extractEntityFromPath(String filePath) {
        if (filePath == null) return null;
        String fileName = extractFileName(filePath);
        if (!fileName.endsWith(".java")) return null;
        String className = fileName.replace(".java", "");
        String entityBase = className
                .replaceAll("(Service|Controller|Repository|Impl|Handler|Listener|Mapper|Converter|Dto|Entity|Request|Response|Spec|Specification)$", "")
                .trim();
        if (entityBase.isEmpty()) return null;
        String readable = toReadableName(entityBase);
        if (readable.isBlank() || readable.length() < 2) return null;
        // 한국어로 번역되지 않은 엔티티명은 비개발자에게 무의미하므로 null 반환
        if (!readable.matches(".*[가-힣].*")) return null;
        return readable;
    }
    /** CamelCase 이름에서 도메인 의미를 추출하여 읽기 쉬운 한국어로 변환합니다. */
    private String toReadableName(String camelCase) {
        if (camelCase == null || camelCase.isBlank()) return "";
        // CamelCase 분리
        String spaced = camelCase
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .toLowerCase().trim();
        // 도메인 용어 한국어 매핑
        return applyDomainTerms(spaced);
    }
    /** 클래스명에서 기능명을 추출합니다. (접미사 제거 + 도메인 변환) */
    private String extractFeatureName(String className) {
        String name = className
                .replaceAll("(Service|Controller|Repository|Impl|Handler|Listener|Mapper|Converter|Dto|Entity)$", "")
                .trim();
        if (name.isEmpty()) return className;
        return toReadableName(name);
    }
    /**
     * 메서드 동작을 "대상 + 동작 + 기능 추가" 형식의 업무 설명으로 변환합니다.
     *
     * <p>한국어로 변환 가능한 대상만 출력하며, 번역 불가능한 경우 null을 반환합니다.
     * 예: 팀 외부 정보 조회/생성 → "팀 외부 정보 조회/생성 기능 추가"</p>
     */
    private String convertMethodToAction(String methodName) {
        if (methodName == null || methodName.isBlank()) return null;
        String spaced = methodName
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .toLowerCase().trim();
        // 동작부와 대상부 분리
        String action = "";
        String target = spaced;
        // 동사 패턴 매칭 (가장 긴 패턴부터)
        String[][] actionPatterns = {
                {"find or create ", "조회/생성"},
                {"find all ", "전체 조회"}, {"find by ", "조건 조회"}, {"find ", "조회"},
                {"get all ", "전체 조회"}, {"get ", "조회"},
                {"create ", "생성"}, {"save ", "저장"}, {"add ", "추가"},
                {"update ", "수정"}, {"edit ", "수정"}, {"modify ", "수정"},
                {"delete ", "삭제"}, {"remove ", "삭제"},
                {"validate ", "검증"}, {"check ", "확인"}, {"verify ", "검증"},
                {"convert ", "변환"}, {"transform ", "변환"},
                {"build ", "구성"}, {"generate ", "생성"},
                {"calculate ", "계산"}, {"compute ", "계산"},
                {"process ", "처리"}, {"execute ", "실행"},
                {"send ", "전송"}, {"notify ", "알림"},
                {"search ", "검색"}, {"filter ", "필터링"},
                {"import ", "가져오기"}, {"export ", "내보내기"},
                {"sync ", "동기화"}, {"load ", "로딩"}, {"init ", "초기화"},
                {"is ", ""}, {"has ", ""}, {"can ", ""},
        };
        for (String[] pattern : actionPatterns) {
            if (spaced.startsWith(pattern[0])) {
                action = pattern[1];
                target = spaced.substring(pattern[0].length()).trim();
                break;
            }
        }
        String targetKr = applyDomainTerms(target);
        if (action.isEmpty()) {
            // is/has/can 같은 확인 메서드는 건너뜀
            return null;
        }
        // 한국어로 변환되지 않은 대상은 비개발자에게 무의미하므로 제외
        if (!targetKr.matches(".*[가-힣].*")) return null;
        return targetKr + " " + action + " 기능 추가";
    }
    /**
     * 영문 도메인 용어를 한국어로 변환합니다.
     *
     * <p>단어 경계(\b) 기반 정규식으로 매칭하여 부분 문자열 오치환을 방지합니다.
     * 영어 복수형 접미사(s, es, ies)도 함께 처리합니다.
     * 매핑되지 않는 단어는 원문 그대로 유지합니다.</p>
     */
    private String applyDomainTerms(String text) {
        // 순서 중요: 긴 복합어부터 매핑
        String[][] domainMap = {
                // 복합 도메인 용어
                {"observation note", "관찰 메모"}, {"observation tag", "관찰 태그"},
                {"dominant foot ref", "주발 정보"}, {"dominant foot", "주발"},
                {"secondary position", "보조 포지션"}, {"primary position", "주 포지션"},
                {"team history", "팀 이력"}, {"team external", "팀 외부 정보"},
                {"note priority ref", "메모 우선순위"}, {"note priority", "메모 우선순위"},
                {"scout candidate", "스카우트 후보"}, {"content block", "콘텐츠 블록"},
                {"change event", "변경 이벤트"}, {"analysis request", "분석 요청"},
                {"file change", "파일 변경"}, {"api endpoint", "API 엔드포인트"},
                {"schema change", "스키마 변경"}, {"project repository", "프로젝트 저장소"},
                {"user role", "사용자 권한"}, {"access token", "접근 토큰"},
                // 단일 도메인 용어
                {"scout", "스카우트"}, {"player", "선수"}, {"team", "팀"},
                {"match", "경기"}, {"league", "리그"}, {"season", "시즌"},
                {"evaluation", "평가"}, {"observation", "관찰"}, {"assessment", "평가"},
                {"candidate", "후보"}, {"position", "포지션"}, {"transfer", "이적"},
                {"contract", "계약"}, {"salary", "급여"}, {"agent", "에이전트"},
                {"schedule", "일정"}, {"event", "이벤트"}, {"note", "메모"},
                {"tag", "태그"}, {"category", "카테고리"}, {"priority", "우선순위"},
                {"report", "보고서"}, {"document", "문서"}, {"template", "템플릿"},
                {"notification", "알림"}, {"message", "메시지"}, {"comment", "댓글"},
                {"user", "사용자"}, {"member", "회원"}, {"admin", "관리자"},
                {"role", "역할"}, {"permission", "권한"}, {"auth", "인증"},
                {"profile", "프로필"}, {"setting", "설정"}, {"config", "설정"},
                {"dashboard", "대시보드"}, {"statistics", "통계"}, {"summary", "요약"},
                {"history", "이력"}, {"log", "로그"}, {"record", "기록"},
                {"status", "상태"}, {"type", "유형"}, {"level", "레벨"},
                {"content", "콘텐츠"}, {"block", "블록"}, {"page", "페이지"},
                {"image", "이미지"}, {"file", "파일"}, {"attachment", "첨부"},
                {"order", "주문"}, {"payment", "결제"}, {"invoice", "청구서"},
                {"product", "상품"}, {"item", "항목"}, {"price", "가격"},
                {"customer", "고객"}, {"client", "클라이언트"}, {"company", "회사"},
                {"project", "프로젝트"}, {"task", "작업"}, {"issue", "이슈"},
                {"external", "외부"}, {"internal", "내부"}, {"ref", "참조"},
                {"lookup", "조회"}, {"search", "검색"}, {"filter", "필터"},
                {"preference", "환경설정"}, {"preferred", "선호"},
                {"reference", "참조 정보"},
                {"detail", "상세"}, {"info", "정보"}, {"management", "관리"},
        };
        String result = text;
        for (String[] mapping : domainMap) {
            // 단어 경계 기반 매칭 + 복수형 접미사(s/es/ies) 포함
            String pattern = "\\b" + java.util.regex.Pattern.quote(mapping[0]) + "(?:ies|es|s)?\\b";
            result = result.replaceAll(pattern, mapping[1]);
        }
        // 혹시 남아 있는 한글 뒤 영어 복수형 접미사 제거 (예: "팀s" → "팀")
        result = result.replaceAll("([가-힣])(ies|es|s)\\b", "$1");
        return result.trim();
    }
    /** snake_case 식별자를 한국어 자연어로 변환합니다. */
    private String translateSnakeCase(String snakeName) {
        if (snakeName == null || snakeName.isBlank()) return "";
        String spaced = snakeName.replace('_', ' ').toLowerCase().trim();
        return applyDomainTerms(spaced);
    }
    /** API 경로에서 리소스명을 추출하여 한국어로 변환합니다. */
    private String extractResourceFromPath(String path) {
        if (path == null) return "기능";
        String[] segments = path.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            String seg = segments[i].trim();
            if (!seg.isEmpty() && !seg.startsWith("{") && !seg.equals("api")) {
                String translated = translateSnakeCase(seg);
                if (translated.matches(".*[가-힣].*")) return translated;
            }
        }
        return "기능";
    }
    /** HTTP 메서드를 한국어 동작으로 변환합니다. */
    private String httpMethodToAction(String method) {
        if (method == null) return "처리";
        return switch (method.toUpperCase()) {
            case "GET" -> "조회";
            case "POST" -> "등록";
            case "PUT", "PATCH" -> "수정";
            case "DELETE" -> "삭제";
            default -> "처리";
        };
    }

    /**
     * 파일 경로에서 사용자 관점의 의도 영역을 추출합니다.
     *
     * <p>기술적 레이어(controller, service 등) 대신 도메인 키워드를 중심으로
     * "어떤 업무 영역의 변경인지"를 사용자 관점에서 표현합니다.
     * 예: /service/scout/weather/WeatherService.java → "스카우트 날씨"</p>
     */
    private String detectFeatureArea(String filePath) {
        String normalized = filePath.replace('\\', '/');
        // 키워드 추출 (파일명 기반)
        List<String> keywords = LayerDetector.extractKeywords(filePath);
        // 디렉토리 구조에서 부모 기능 감지
        String parentFeature = extractParentFeature(normalized);
        if (!keywords.isEmpty() && parentFeature != null) {
            String childKeyword = null;
            for (String kw : keywords) {
                if (!kw.equalsIgnoreCase(parentFeature)) {
                    childKeyword = kw;
                    break;
                }
            }
            if (childKeyword != null) {
                // 부모/자식 키워드를 도메인 용어로 변환하여 의도 영역 표현
                String parentName = applyDomainTerms(parentFeature);
                String childName = applyDomainTerms(childKeyword);
                return parentName + " " + childName;
            }
            return applyDomainTerms(parentFeature);
        }
        if (!keywords.isEmpty()) {
            return applyDomainTerms(keywords.get(0));
        }
        return "기타";
    }
    /** 부모 기능으로 인식하지 않을 구조적/레이어 디렉토리명 */
    private static final Set<String> NON_FEATURE_DIRS = Set.of(
            "impl", "common", "base", "util", "utils", "helper", "helpers",
            "exception", "exceptions", "error", "errors",
            "mapper", "mappers", "converter", "converters",
            "handler", "handlers", "listener", "listeners",
            "interceptor", "interceptors", "filter", "filters",
            "aspect", "aspects", "annotation", "annotations",
            "enums", "enum", "constant", "constants",
            "model", "entity", "domain", "dto", "vo",
            "request", "response", "spec", "specification",
            "querydsl", "repository", "service", "controller",
            "api", "web", "rest", "support",
            "core", "internal", "config", "configuration",
            "custom", "abstract", "type", "types"
    );
    /**
     * 디렉토리 구조에서 부모 기능 키워드를 추출합니다.
     *
     * <p>레이어 디렉토리 이후 3단계 이상 중첩(부모/자식/파일)일 때만
     * 첫 번째 하위 디렉토리를 부모로 판별합니다.
     * 구조적 디렉토리(impl, request, common 등)는 부모로 인식하지 않습니다.</p>
     *
     * <p>예: /service/scout/weather/WeatherService.java → "scout" (부모)
     * /service/scout/ScoutService.java → null (자식 디렉토리 없음)
     * /dto/request/ScoutRequest.java → null (request는 구조적 디렉토리)</p>
     *
     * @param normalizedPath 정규화된 파일 경로 (/ 구분자)
     * @return 부모 기능 키워드 또는 null
     */
    private String extractParentFeature(String normalizedPath) {
        String[] layerMarkers = {"/controller/", "/service/", "/domain/", "/entity/",
                "/dto/", "/repository/", "/config/"};
        for (String marker : layerMarkers) {
            int idx = normalizedPath.indexOf(marker);
            if (idx >= 0) {
                String afterLayer = normalizedPath.substring(idx + marker.length());
                String[] parts = afterLayer.split("/");
                // 3단계 이상 중첩 필수: 부모디렉토리/자식디렉토리/파일.java
                if (parts.length > 2) {
                    String candidate = parts[0].toLowerCase();
                    if (!NON_FEATURE_DIRS.contains(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }
    /**
     * QueryDSL Q클래스 등 자동 생성 파일을 판별합니다.
     */
    private boolean isGeneratedFile(String filePath) {
        String fileName = extractFileName(filePath);
        // QueryDSL Q클래스: Q로 시작하고 대문자가 이어지는 Java 파일
        if (fileName.matches("Q[A-Z].*\\.java")) return true;
        // generated-sources, build/generated 등 자동 생성 경로
        String normalized = filePath.replace('\\', '/');
        return normalized.contains("/generated/") || normalized.contains("/generated-sources/");
    }
    /**
     * 파일 경로에서 파일명만 추출합니다.
     */
    private String extractFileName(String filePath) {
        int lastSlash = filePath.lastIndexOf('/');
        int lastBackSlash = filePath.lastIndexOf('\\');
        int idx = Math.max(lastSlash, lastBackSlash);
        return idx >= 0 ? filePath.substring(idx + 1) : filePath;
    }

    /**
     * API 경로에서 prefix를 추출합니다.
     * 예: "/api/projects/{id}/repos" -> "/api/projects"
     */
    private String extractApiPrefix(String path) {
        if (path == null) return null;
        String[] segments = path.split("/");
        StringBuilder prefix = new StringBuilder();
        int count = 0;
        for (String segment : segments) {
            if (segment.isEmpty()) continue;
            if (segment.startsWith("{")) break;
            prefix.append("/").append(segment);
            count++;
            if (count >= 2) break;
        }
        return prefix.length() > 0 ? prefix.toString() : path;
    }
}
