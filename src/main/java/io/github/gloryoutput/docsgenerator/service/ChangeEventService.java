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
     */
    private ChangeEvent buildSchemaChangeEvent(UUID idAnalysisRequest, UUID idProject,
                                                DbSchemaResult.SchemaChange change) {
        String title;
        String severity;
        String correlationKey = change.getTableName();
        String description;
        switch (change.getChangeType()) {
            case "TABLE_ADDED":
                title = "테이블 추가: " + change.getTableName();
                severity = "HIGH";
                description = "새로운 테이블 '" + change.getTableName() + "'이(가) 추가되었습니다.";
                break;
            case "TABLE_REMOVED":
                title = "테이블 삭제: " + change.getTableName();
                severity = "HIGH";
                description = "테이블 '" + change.getTableName() + "'이(가) 삭제되었습니다.";
                break;
            case "COLUMN_ADDED":
                title = "컬럼 추가: " + change.getTableName() + "." + change.getColumnName();
                severity = "MEDIUM";
                description = "테이블 '" + change.getTableName() + "'에 컬럼 '" + change.getColumnName() + "'이(가) 추가되었습니다.";
                break;
            case "COLUMN_REMOVED":
                title = "컬럼 삭제: " + change.getTableName() + "." + change.getColumnName();
                severity = "HIGH";
                description = "테이블 '" + change.getTableName() + "'에서 컬럼 '" + change.getColumnName() + "'이(가) 삭제되었습니다.";
                break;
            case "COLUMN_TYPE_CHANGED":
                title = "컬럼 타입 변경: " + change.getTableName() + "." + change.getColumnName();
                severity = "HIGH";
                description = "테이블 '" + change.getTableName() + "'의 컬럼 '" + change.getColumnName()
                        + "' 타입이 '" + change.getOldDataType() + "'에서 '" + change.getNewDataType() + "'으로 변경되었습니다.";
                break;
            case "INDEX_ADDED":
                title = "인덱스 추가: " + change.getTableName() + "." + change.getIndexName();
                severity = "LOW";
                description = "테이블 '" + change.getTableName() + "'에 인덱스 '" + change.getIndexName() + "'이(가) 추가되었습니다.";
                break;
            case "INDEX_REMOVED":
                title = "인덱스 삭제: " + change.getTableName() + "." + change.getIndexName();
                severity = "LOW";
                description = "테이블 '" + change.getTableName() + "'에서 인덱스 '" + change.getIndexName() + "'이(가) 삭제되었습니다.";
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
     */
    private ChangeEvent buildApiChangeEvent(UUID idAnalysisRequest, UUID idProject,
                                             ApiAnalyzerResult.EndpointChange change) {
        String title;
        String severity;
        String description;
        String correlationKey = extractApiPrefix(change.getPath());
        switch (change.getChangeType()) {
            case "ENDPOINT_ADDED":
                title = "API 추가: " + change.getHttpMethod() + " " + change.getPath();
                severity = "MEDIUM";
                description = "새로운 API endpoint '" + change.getHttpMethod() + " " + change.getPath() + "'이(가) 추가되었습니다.";
                break;
            case "ENDPOINT_REMOVED":
                title = "API 삭제: " + change.getHttpMethod() + " " + change.getPath();
                severity = "HIGH";
                description = "API endpoint '" + change.getHttpMethod() + " " + change.getPath() + "'이(가) 삭제되었습니다.";
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
                String title = "프로젝트 코드 변경 (" + allValidCommits.size() + "건 커밋)";
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
                String title = buildRepoTitle(repoName, validCommits);
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
     * 레포지토리의 커밋 이력을 요약한 제목을 생성합니다.
     */
    private String buildRepoTitle(String repoName, List<GitDiffResult.CommitInfo> commits) {
        if (commits.size() == 1) {
            return commits.get(0).getMessage();
        }
        return repoName + " 코드 변경 (" + commits.size() + "건 커밋)";
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
                    List<String> converted = convertToBusinessDescription(changeSummary);
                    changesByFeature.computeIfAbsent(featureArea, k -> new LinkedHashSet<>()).addAll(converted);
                }
            }
        }
        // LLM으로 카테고리별 변경 내용 압축 (LLM 없으면 원본 카테고리 구조 유지)
        Map<String, List<String>> compressedByCategory = llmDescriptionCompressorService.compress(changesByFeature);
        // description 조립 (기능 변경 중심)
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(repoName).append("] ");
        sb.append("커밋 ").append(commits.size()).append("건, 변경 파일 ").append(allFilePaths.size()).append("개");
        if (!topKeywords.isEmpty()) {
            sb.append("\n관련 기능: ").append(String.join(", ", topKeywords));
        }
        if (!compressedByCategory.isEmpty()) {
            sb.append("\n\n기능별 변경 내용:");
            for (Map.Entry<String, List<String>> entry : compressedByCategory.entrySet()) {
                if (entry.getValue().isEmpty()) continue;
                sb.append("\n[").append(entry.getKey()).append("]");
                for (String summary : entry.getValue()) {
                    sb.append("\n  - ").append(summary);
                }
            }
        }
        return sb.toString();
    }
    /**
     * 개발자용 changeSummary를 비개발자가 이해할 수 있는 기능 설명으로 변환합니다.
     *
     * <p>기술 용어(필드명, 어노테이션, 줄 수 변경 등)를 제거하고,
     * 도메인 엔티티명을 추출하여 업무 관점의 설명으로 변환합니다.</p>
     *
     * <p>변환 예시:
     * - "추가 필드: evaluationRepository, observationNoteRepository" → "평가, 관찰 메모 정보 관리 추가"
     * - "추가 메서드: findOrCreateTeamExternal" → "팀 외부 정보 조회/생성 기능 추가"
     * - "새 클래스: ScoutObservationTagLookupService" → "스카우트 관찰 태그 조회 기능 신규 추가"</p>
     */
    private List<String> convertToBusinessDescription(String changeSummary) {
        List<String> results = new ArrayList<>();
        // 세미콜론으로 분리된 여러 변경사항 처리
        String[] parts = changeSummary.split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            // 보고서 노출 불필요 항목 필터링 (ReportNoiseFilter에서 통합 관리)
            if (ReportNoiseFilter.shouldFilterSummary(trimmed)) continue;
            // 필드 추가 → 도메인 엔티티 추출
            if (trimmed.startsWith("추가 필드:")) {
                String fieldsPart = trimmed.substring("추가 필드:".length()).trim();
                String[] fieldNames = fieldsPart.split(",");
                List<String> entityNames = new ArrayList<>();
                boolean hasServiceField = false;
                for (String fieldName : fieldNames) {
                    String fn = fieldName.trim();
                    if (fn.endsWith("Repository")) {
                        entityNames.add(toReadableName(fn.replace("Repository", "")));
                    } else if (fn.endsWith("Service")) {
                        String name = toReadableName(fn.replace("Service", ""));
                        results.add(name + " 처리 기능 연동");
                        hasServiceField = true;
                    } else {
                        entityNames.add(toReadableName(fn));
                    }
                }
                if (!entityNames.isEmpty()) {
                    results.add(String.join(", ", entityNames) + " 정보 관리 추가");
                }
                continue;
            }
            // 새 클래스 → 기능명 추출
            if (trimmed.startsWith("새 클래스:")) {
                String className = trimmed.substring("새 클래스:".length()).trim();
                String featureName = extractFeatureName(className);
                results.add(featureName + " 기능 신규 추가");
                continue;
            }
            // 메서드 추가 → 동작 + 대상 추출
            if (trimmed.startsWith("추가 메서드:")) {
                String methodsPart = trimmed.substring("추가 메서드:".length()).trim();
                for (String methodName : methodsPart.split(",")) {
                    String desc = convertMethodToAction(methodName.trim());
                    if (desc != null) results.add(desc);
                }
                continue;
            }
            // 그 외: 기술 용어 제거 후 남은 내용
            String cleaned = trimmed
                    .replaceAll("\\([+\\-\\d/\\s]+lines?\\)", "")
                    .replaceAll("@\\w+", "")
                    .trim();
            if (!cleaned.isEmpty() && !cleaned.matches("^\\s*$")) {
                results.add(cleaned);
            }
        }
        return results.stream().distinct().toList();
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
     * 메서드명을 "동작 + 대상" 형식의 업무 설명으로 변환합니다.
     * 예: findOrCreateTeamExternal → "팀 외부 정보 조회/생성 기능 추가"
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

    /**
     * 파일 경로에서 기능 영역을 추출합니다.
     *
     * <p>패키지 구조를 기반으로 controller/service/domain 등의 레이어와
     * 기능 키워드를 결합하여 기능 영역명을 반환합니다.
     * 디렉토리 구조에서 부모 기능이 감지되면 계층적 키워드(parent/child)로 표현합니다.
     * 예: /service/scout/weather/WeatherService.java → "비즈니스 로직 (scout/weather)"</p>
     */
    private String detectFeatureArea(String filePath) {
        String normalized = filePath.replace('\\', '/');
        // 레이어 판별
        String layer = "";
        if (normalized.contains("/controller/")) layer = "API";
        else if (normalized.contains("/service/")) layer = "비즈니스 로직";
        else if (normalized.contains("/domain/") || normalized.contains("/entity/")) layer = "데이터 모델";
        else if (normalized.contains("/dto/")) layer = "데이터 전송";
        else if (normalized.contains("/config/")) layer = "설정";
        else if (normalized.contains("/util/") || normalized.contains("/common/")) layer = "공통 모듈";
        else if (normalized.contains("/repository/")) layer = "데이터 접근";
        else if (normalized.endsWith(".sql")) layer = "DB 스키마";
        else if (normalized.endsWith(".yml") || normalized.endsWith(".yaml") || normalized.endsWith(".properties")) layer = "설정";
        // 키워드 추출 (파일명 기반)
        List<String> keywords = LayerDetector.extractKeywords(filePath);
        // 디렉토리 구조에서 부모 기능 감지
        String parentFeature = extractParentFeature(normalized);
        if (!keywords.isEmpty() && parentFeature != null
                && !parentFeature.equalsIgnoreCase(keywords.get(0))) {
            // 부모 기능과 파일 키워드가 다르면 계층적 키워드 생성 (parent/child)
            String hierarchicalKeyword = parentFeature + "/" + keywords.get(0);
            if (!layer.isEmpty()) {
                return layer + " (" + hierarchicalKeyword + ")";
            }
            return hierarchicalKeyword;
        }
        if (!keywords.isEmpty() && !layer.isEmpty()) {
            return layer + " (" + keywords.get(0) + ")";
        }
        if (!layer.isEmpty()) return layer;
        if (!keywords.isEmpty()) return keywords.get(0);
        return "기타";
    }
    /**
     * 디렉토리 구조에서 부모 기능 키워드를 추출합니다.
     *
     * <p>레이어 디렉토리(controller, service, domain 등) 이후의 첫 번째 하위 디렉토리를
     * 부모 기능으로 판별합니다. 하위 디렉토리가 존재해야(2단계 이상 중첩) 부모-자식 관계입니다.</p>
     *
     * <p>예: /service/scout/weather/WeatherService.java → "scout" (부모)
     * /service/WeatherService.java → null (단일 레벨, 부모 없음)</p>
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
                // 2단계 이상 중첩이어야 부모-자식 관계 (디렉토리 + 파일 = 최소 2 parts)
                if (parts.length > 1) {
                    return parts[0].toLowerCase();
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
