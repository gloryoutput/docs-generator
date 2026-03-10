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
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@Builder
@JsonPropertyOrder({"idAnalysisRequest", "idProject", "startDate", "endDate",
        "status", "startedAt", "completedAt", "repositoryResults", "schemaResults", "apiResult"})
public class AnalysisResponse {
    private String idAnalysisRequest;
    private String idProject;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private List<GitDiffResult> repositoryResults;
    private List<DbSchemaResult> schemaResults;
    private ApiAnalyzerResult apiResult;

    public static AnalysisResponse from(AnalysisRequest request,
                                         List<GitDiffResult> gitResults,
                                         List<DbSchemaResult> schemaResults,
                                         ApiAnalyzerResult apiResult) {
        return AnalysisResponse.builder()
                .idAnalysisRequest(request.getIdAnalysisRequest().toString())
                .idProject(request.getIdProject().toString())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .status(request.getStatus())
                .startedAt(request.getStartedAt())
                .completedAt(request.getCompletedAt())
                .repositoryResults(gitResults)
                .schemaResults(schemaResults)
                .apiResult(apiResult)
                .build();
    }
}
