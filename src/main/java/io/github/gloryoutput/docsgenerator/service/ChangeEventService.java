package io.github.gloryoutput.docsgenerator.service;

import io.github.gloryoutput.docsgenerator.analyzer.api.ApiAnalyzerResult;
import io.github.gloryoutput.docsgenerator.analyzer.database.DbSchemaResult;
import io.github.gloryoutput.docsgenerator.analyzer.git.GitDiffResult;
import io.github.gloryoutput.docsgenerator.domain.changeevent.ChangeEvent;
import io.github.gloryoutput.docsgenerator.domain.changeevent.ChangeEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 분석 결과로부터 변경 이벤트를 생성하는 서비스
 *
 * <p>Git diff, DB 스키마, API endpoint 분석 결과를 기반으로
 * 규칙 엔진 로직을 적용하여 변경 이벤트를 도출합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChangeEventService {
    private final ChangeEventRepository changeEventRepository;

    /**
     * 분석 결과로부터 변경 이벤트를 생성하고 저장합니다.
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
        List<ChangeEvent> events = new ArrayList<>();
        // 스키마 변경 이벤트 생성
        for (DbSchemaResult schemaResult : schemaResults) {
            if (schemaResult.getChanges() == null) continue;
            for (DbSchemaResult.SchemaChange change : schemaResult.getChanges()) {
                ChangeEvent event = buildSchemaChangeEvent(idAnalysisRequest, idProject, change);
                if (event != null) {
                    events.add(event);
                }
            }
        }
        // API 변경 이벤트 생성
        if (apiResult != null && apiResult.getChanges() != null) {
            for (ApiAnalyzerResult.EndpointChange change : apiResult.getChanges()) {
                ChangeEvent event = buildApiChangeEvent(idAnalysisRequest, idProject, change);
                if (event != null) {
                    events.add(event);
                }
            }
        }
        // 코드 변경 이벤트 생성 (디렉토리 단위로 그룹핑)
        events.addAll(buildCodeChangeEvents(idAnalysisRequest, idProject, gitResults));
        if (!events.isEmpty()) {
            changeEventRepository.saveAll(events);
            log.info("변경 이벤트 {}건 생성 완료 (분석 요청: {})", events.size(), idAnalysisRequest);
        }
        return events;
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
     * Git diff 결과에서 코드 변경 이벤트를 디렉토리 단위로 그룹핑하여 생성합니다.
     */
    private List<ChangeEvent> buildCodeChangeEvents(UUID idAnalysisRequest, UUID idProject,
                                                     List<GitDiffResult> gitResults) {
        // 모든 파일 변경을 디렉토리 기준으로 그룹핑
        Map<String, List<GitDiffResult.FileChange>> directoryGroups = new LinkedHashMap<>();
        for (GitDiffResult gitResult : gitResults) {
            if (gitResult.getFileChanges() == null) continue;
            for (GitDiffResult.FileChange fileChange : gitResult.getFileChanges()) {
                String directory = extractDirectory(fileChange.getFilePath());
                directoryGroups.computeIfAbsent(directory, k -> new ArrayList<>()).add(fileChange);
            }
        }
        return directoryGroups.entrySet().stream()
                .map(entry -> ChangeEvent.builder()
                        .idAnalysisRequest(idAnalysisRequest)
                        .idProject(idProject)
                        .category("CODE_CHANGE")
                        .title("코드 변경: " + entry.getKey() + " (" + entry.getValue().size() + "개 파일)")
                        .description("디렉토리 '" + entry.getKey() + "'에서 " + entry.getValue().size() + "개 파일이 변경되었습니다.")
                        .severity("LOW")
                        .confidenceScore(0.8)
                        .sourceType("GIT")
                        .correlationKey(entry.getKey())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * API 경로에서 prefix를 추출합니다.
     * 예: "/api/projects/{id}/repos" → "/api/projects"
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

    /**
     * 파일 경로에서 디렉토리(처음 2개 경로 세그먼트)를 추출합니다.
     */
    private String extractDirectory(String filePath) {
        if (filePath == null) return "unknown";
        String normalized = filePath.replace("\\", "/");
        String[] segments = normalized.split("/");
        if (segments.length <= 2) {
            return normalized;
        }
        return segments[0] + "/" + segments[1];
    }
}
