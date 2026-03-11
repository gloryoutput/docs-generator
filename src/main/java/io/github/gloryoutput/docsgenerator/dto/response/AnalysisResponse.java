package io.github.gloryoutput.docsgenerator.dto.response;

import io.github.gloryoutput.docsgenerator.analyzer.api.ApiAnalyzerResult;
import io.github.gloryoutput.docsgenerator.analyzer.database.DbSchemaResult;
import io.github.gloryoutput.docsgenerator.analyzer.git.GitDiffResult;
import io.github.gloryoutput.docsgenerator.domain.analysis.AnalysisRequest;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;
import lombok.Getter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 분석 결과 응답 DTO
 *
 * <p>대량의 raw 데이터(Git 커밋, 파일 변경 목록 등) 대신 요약 정보만 반환합니다.
 * 상세 데이터는 Evidence 조회 API 또는 보고서 다운로드 API를 통해 확인할 수 있습니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@Builder
@JsonPropertyOrder({"idAnalysisRequest", "idProject", "startDate", "endDate",
        "status", "startedAt", "completedAt",
        "totalRepositories", "totalCommits", "totalFileChanges",
        "totalSchemaChanges", "totalApiChanges"})
public class AnalysisResponse {
    private String idAnalysisRequest;
    private String idProject;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private int totalRepositories;
    private int totalCommits;
    private int totalFileChanges;
    private int totalSchemaChanges;
    private int totalApiChanges;

    /**
     * 분석 결과로부터 요약 응답을 생성합니다.
     *
     * @param request 분석 요청 엔티티
     * @param gitResults Git diff 분석 결과 목록
     * @param schemaResults DB 스키마 분석 결과 목록
     * @param apiResult API endpoint 분석 결과
     * @return 요약 응답 DTO
     */
    public static AnalysisResponse from(AnalysisRequest request,
                                         List<GitDiffResult> gitResults,
                                         List<DbSchemaResult> schemaResults,
                                         ApiAnalyzerResult apiResult) {
        int totalCommits = 0;
        int totalFileChanges = 0;
        for (GitDiffResult git : gitResults) {
            totalCommits += git.getTotalCommits();
            if (git.getFileChanges() != null) {
                totalFileChanges += git.getFileChanges().size();
            }
        }
        int totalSchemaChanges = 0;
        for (DbSchemaResult schema : schemaResults) {
            totalSchemaChanges += schema.getTotalChanges();
        }
        int totalApiChanges = apiResult != null && apiResult.getChanges() != null
                ? apiResult.getChanges().size() : 0;
        return AnalysisResponse.builder()
                .idAnalysisRequest(request.getIdAnalysisRequest().toString())
                .idProject(request.getIdProject().toString())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .status(request.getStatus())
                .startedAt(request.getStartedAt())
                .completedAt(request.getCompletedAt())
                .totalRepositories(gitResults.size())
                .totalCommits(totalCommits)
                .totalFileChanges(totalFileChanges)
                .totalSchemaChanges(totalSchemaChanges)
                .totalApiChanges(totalApiChanges)
                .build();
    }
}
