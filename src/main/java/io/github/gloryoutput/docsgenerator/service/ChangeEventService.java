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

}
