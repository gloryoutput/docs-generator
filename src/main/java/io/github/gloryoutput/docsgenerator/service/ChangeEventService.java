package io.github.gloryoutput.docsgenerator.service;

import io.github.gloryoutput.docsgenerator.analyzer.api.ApiAnalyzerResult;
import io.github.gloryoutput.docsgenerator.analyzer.database.DbSchemaResult;
import io.github.gloryoutput.docsgenerator.analyzer.git.GitDiffResult;
import io.github.gloryoutput.docsgenerator.domain.changeevent.ChangeEvent;
import io.github.gloryoutput.docsgenerator.domain.changeevent.ChangeEventRepository;
import io.github.gloryoutput.docsgenerator.service.RuleEngineService.AnalysisContext;
import io.github.gloryoutput.docsgenerator.service.RuleEngineService.ChangeEventTemplate;
import io.github.gloryoutput.docsgenerator.service.RuleEngineService.MatchedRule;
import io.github.gloryoutput.docsgenerator.util.LayerDetector;
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

    /**
     * 분석 결과로부터 변경 이벤트를 생성하고 저장합니다.
     *
     * <p>Section 12 알고리즘에 따라 다음 순서로 처리합니다:
     * 1) AnalysisContext 구성 (레이어 판별, 키워드 추출)
     * 2) 규칙 엔진 매칭
     * 3) 규칙 기반 이벤트 생성 + 미매칭 증거의 폴백 이벤트 생성
     * 4) 유사 이벤트 병합
     * 5) 제목/설명 정규화</p>
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
                                                ApiAnalyzerResult apiResult) {
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
            events.addAll(buildCodeChangeEvents(idAnalysisRequest, idProject, gitResults));
        }
        // 5. 유사 이벤트 병합
        events = mergeEvents(events);
        // 6. 제목 정규화
        for (ChangeEvent event : events) {
            // ChangeEvent는 불변이므로 정규화된 제목으로 새로 생성하지 않고,
            // 빌드 시점에 정규화를 적용하기 위해 이 단계에서는 리스트를 재구성
        }
        events = normalizeEventTitles(events);
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
                                                     List<GitDiffResult> gitResults) {
        List<ChangeEvent> events = new ArrayList<>();
        for (GitDiffResult gitResult : gitResults) {
            if (gitResult.getCommits() == null || gitResult.getCommits().isEmpty()) continue;
            String repoName = gitResult.getRepositoryName();
            // 유효한 커밋만 필터링
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
     * 레포지토리의 전체 커밋을 통합하여 중복 제거된 구조화 description을 생성합니다.
     *
     * <p>포함 정보: 작성자, 기간, 커밋 이력, 영향 레이어, 키워드,
     * 변경 타입별 파일 목록(중복 제거)</p>
     */
    private String buildRepoDescription(String repoName, List<GitDiffResult.CommitInfo> commits) {
        Set<String> authors = new LinkedHashSet<>();
        Map<String, Set<String>> filesByChangeType = new LinkedHashMap<>();
        Set<String> allFilePaths = new LinkedHashSet<>();
        String earliestDate = null;
        String latestDate = null;
        for (GitDiffResult.CommitInfo commit : commits) {
            authors.add(commit.getAuthorName());
            // 날짜 범위
            String dt = commit.getDateTime();
            if (dt != null) {
                if (earliestDate == null || dt.compareTo(earliestDate) < 0) earliestDate = dt;
                if (latestDate == null || dt.compareTo(latestDate) > 0) latestDate = dt;
            }
            // 파일 수집 (중복 제거)
            for (GitDiffResult.FileChange fc : commit.getFileChanges()) {
                String ct = fc.getChangeType() != null ? fc.getChangeType() : "MODIFY";
                String filePath = fc.getFilePath();
                if (filePath == null || isGeneratedFile(filePath)) continue;
                allFilePaths.add(filePath);
                filesByChangeType.computeIfAbsent(ct, k -> new LinkedHashSet<>()).add(extractFileName(filePath));
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
                .limit(10)
                .map(Map.Entry::getKey)
                .toList();
        // description 조립
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(repoName).append("] ");
        sb.append("커밋 ").append(commits.size()).append("건");
        sb.append(", 변경 파일 ").append(allFilePaths.size()).append("개 (중복 제거)");
        sb.append("\n작성자: ").append(String.join(", ", authors));
        if (earliestDate != null && latestDate != null) {
            sb.append(" | 기간: ").append(earliestDate).append(" ~ ").append(latestDate);
        }
        if (!layers.isEmpty()) {
            sb.append("\n영향 레이어: ").append(String.join(", ", layers));
        }
        if (!topKeywords.isEmpty()) {
            sb.append("\n관련 키워드: ").append(String.join(", ", topKeywords));
        }
        // 변경 타입별 파일 목록 (중복 제거됨)
        sb.append("\n\n변경 파일 목록:");
        for (Map.Entry<String, Set<String>> entry : filesByChangeType.entrySet()) {
            Set<String> files = entry.getValue();
            sb.append("\n[").append(entry.getKey()).append("] ");
            List<String> fileList = new ArrayList<>(files);
            if (fileList.size() <= 15) {
                sb.append(String.join(", ", fileList));
            } else {
                sb.append(String.join(", ", fileList.subList(0, 15)));
                sb.append(" 외 ").append(fileList.size() - 15).append("개");
            }
        }
        // 서비스 단위 변경 상세 (changeSummary가 있는 파일만)
        List<String> changeSummaries = new ArrayList<>();
        Set<String> processedFiles = new HashSet<>();
        for (GitDiffResult.CommitInfo commit : commits) {
            for (GitDiffResult.FileChange fc : commit.getFileChanges()) {
                if (fc.getChangeSummary() != null && !fc.getChangeSummary().isEmpty()
                        && fc.getFilePath() != null && !isGeneratedFile(fc.getFilePath())
                        && processedFiles.add(fc.getFilePath())) {
                    String fileName = extractFileName(fc.getFilePath());
                    String summary = fileName + ": " + fc.getChangeSummary();
                    if (fc.getAddedLines() > 0 || fc.getDeletedLines() > 0) {
                        summary += " (+" + fc.getAddedLines() + "/-" + fc.getDeletedLines() + ")";
                    }
                    changeSummaries.add(summary);
                }
            }
        }
        if (!changeSummaries.isEmpty()) {
            sb.append("\n\n변경 상세:");
            for (String summary : changeSummaries) {
                sb.append("\n- ").append(summary);
            }
        }
        return sb.toString();
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
