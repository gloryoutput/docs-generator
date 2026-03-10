package io.github.gloryoutput.docsgenerator.dto.response;

import io.github.gloryoutput.docsgenerator.domain.report.Report;
import lombok.Builder;
import lombok.Getter;
import java.time.LocalDateTime;

/**
 * 보고서 응답 DTO
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@Builder
public class ReportResponse {
    private String idReport;
    private String idAnalysisRequest;
    private String idProject;
    private String reportContent;
    private LocalDateTime generatedAt;

    /**
     * Report 엔티티로부터 응답 DTO를 생성합니다.
     *
     * @param report 보고서 엔티티
     * @return 보고서 응답 DTO
     */
    public static ReportResponse from(Report report) {
        return ReportResponse.builder()
                .idReport(report.getIdReport().toString())
                .idAnalysisRequest(report.getIdAnalysisRequest().toString())
                .idProject(report.getIdProject().toString())
                .reportContent(report.getReportContent())
                .generatedAt(report.getGeneratedAt())
                .build();
    }
}
