package io.github.gloryoutput.docsgenerator.domain.changeevent;

import io.github.gloryoutput.docsgenerator.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.UUID;

/**
 * 변경 이벤트 엔티티
 *
 * <p>분석 결과에서 도출된 개별 변경 사항을 나타냅니다.
 * 스키마 변경, API 변경, 코드 변경, 의존성 변경 등을 포함합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Entity
@Table(name = "change_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChangeEvent extends BaseEntity {
    @Id
    @Column(name = "id_change_event", nullable = false, updatable = false, columnDefinition = "binary(16)")
    private UUID idChangeEvent;
    @Column(name = "id_analysis_request", nullable = false, columnDefinition = "binary(16)")
    private UUID idAnalysisRequest;
    @Column(name = "id_project", nullable = false, columnDefinition = "binary(16)")
    private UUID idProject;
    @Column(name = "category", nullable = false)
    private String category;
    @Column(name = "title", nullable = false, columnDefinition = "TEXT")
    private String title;
    @Column(name = "description", columnDefinition = "MEDIUMTEXT")
    private String description;
    @Column(name = "severity", nullable = false)
    private String severity;
    @Column(name = "confidence_score", nullable = false)
    private Double confidenceScore;
    @Column(name = "source_type", nullable = false)
    private String sourceType;
    @Column(name = "correlation_key")
    private String correlationKey;

    @Builder
    public ChangeEvent(UUID idAnalysisRequest, UUID idProject, String category, String title,
                       String description, String severity, Double confidenceScore,
                       String sourceType, String correlationKey) {
        this.idChangeEvent = UUID.randomUUID();
        this.idAnalysisRequest = idAnalysisRequest;
        this.idProject = idProject;
        this.category = category;
        this.title = title;
        this.description = description;
        this.severity = severity;
        this.confidenceScore = confidenceScore;
        this.sourceType = sourceType;
        this.correlationKey = correlationKey;
    }
}
