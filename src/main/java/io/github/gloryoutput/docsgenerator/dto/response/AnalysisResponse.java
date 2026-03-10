package io.github.gloryoutput.docsgenerator.dto.response;

import io.github.gloryoutput.docsgenerator.analyzer.git.GitDiffResult;
import io.github.gloryoutput.docsgenerator.domain.analysis.AnalysisRequest;
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
public class AnalysisResponse {
    private String idAnalysisRequest;
    private String projectCode;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private List<GitDiffResult> repositoryResults;

    public static AnalysisResponse from(AnalysisRequest request, String projectCode,
                                         List<GitDiffResult> results) {
        return AnalysisResponse.builder()
                .idAnalysisRequest(request.getIdAnalysisRequest().toString())
                .projectCode(projectCode)
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .status(request.getStatus())
                .startedAt(request.getStartedAt())
                .completedAt(request.getCompletedAt())
                .repositoryResults(results)
                .build();
    }
}
