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
            // 그룹 내에서 가장 높은 confidenceScore를 가진 이벤트를 기준으로 병합
            ChangeEvent primary = group.get(0);
            for (ChangeEvent event : group) {
                if (event.getConfidenceScore() > primary.getConfidenceScore()) {
                    primary = event;
                }
            }
            // 설명 병합: 기준 이벤트의 설명 + 나머지 이벤트 설명 추가
            StringBuilder mergedDescription = new StringBuilder();
            mergedDescription.append(primary.getDescription());
            for (ChangeEvent event : group) {
                if (event != primary && event.getDescription() != null) {
                    mergedDescription.append(" / ").append(event.getDescription());
                }
            }
            merged.add(ChangeEvent.builder()
                    .idAnalysisRequest(primary.getIdAnalysisRequest())
                    .idProject(primary.getIdProject())
                    .category(primary.getCategory())
                    .title(primary.getTitle())
                    .description(mergedDescription.toString())
                    .severity(primary.getSeverity())
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
     * Git diff 결과에서 코드 변경 이벤트를 커밋 단위로 생성합니다.
     *
     * <p>커밋 메시지를 기능명으로 사용하여 파일 단위가 아닌 이벤트 단위로 보고서를 작성합니다.
     * noise 필터링 후 변경 파일이 없는 커밋은 건너뜁니다.</p>
     */
    private List<ChangeEvent> buildCodeChangeEvents(UUID idAnalysisRequest, UUID idProject,
                                                     List<GitDiffResult> gitResults) {
        List<ChangeEvent> events = new ArrayList<>();
        for (GitDiffResult gitResult : gitResults) {
            if (gitResult.getCommits() == null) continue;
            String repoName = gitResult.getRepositoryName();
            for (GitDiffResult.CommitInfo commit : gitResult.getCommits()) {
                // noise 필터링 후 변경 파일이 없으면 건너뜀
                if (commit.getFileChanges() == null || commit.getFileChanges().isEmpty()) {
                    continue;
                }
                int fileCount = commit.getFileChanges().size();
                events.add(ChangeEvent.builder()
                        .idAnalysisRequest(idAnalysisRequest)
                        .idProject(idProject)
                        .category("CODE_CHANGE")
                        .title(commit.getMessage())
                        .description(repoName + " 레포지토리에서 " + fileCount
                                + "개 파일이 변경되었습니다. (작성자: " + commit.getAuthorName()
                                + ", " + commit.getDateTime() + ")")
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
