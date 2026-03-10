package io.github.gloryoutput.docsgenerator.domain.report;

import io.github.gloryoutput.docsgenerator.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 자동 생성 보고서 엔티티
 *
 * <p>분석 결과를 종합하여 생성된 Markdown 보고서를 저장합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Entity
@Table(name = "report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report extends BaseEntity {
    @Id
    @Column(name = "id_report", nullable = false, updatable = false, columnDefinition = "binary(16)")
    private UUID idReport;
    @Column(name = "id_analysis_request", nullable = false, columnDefinition = "binary(16)")
    private UUID idAnalysisRequest;
    @Column(name = "id_project", nullable = false, columnDefinition = "binary(16)")
    private UUID idProject;
    @Column(name = "report_content", nullable = false, columnDefinition = "LONGTEXT")
    private String reportContent;
    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    @Builder
    public Report(UUID idAnalysisRequest, UUID idProject, String reportContent) {
        this.idReport = UUID.randomUUID();
        this.idAnalysisRequest = idAnalysisRequest;
        this.idProject = idProject;
        this.reportContent = reportContent;
        this.generatedAt = LocalDateTime.now();
    }
}
