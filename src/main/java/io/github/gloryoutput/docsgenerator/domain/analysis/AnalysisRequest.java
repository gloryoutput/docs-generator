package io.github.gloryoutput.docsgenerator.domain.analysis;

import io.github.gloryoutput.docsgenerator.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 분석 요청 엔티티
 *
 * <p>project_code와 기간을 기반으로 레포지토리 변경 분석을 요청합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Entity
@Table(name = "analysis_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisRequest extends BaseEntity {
    @Id
    @Column(name = "id_analysis_request", nullable = false, updatable = false, columnDefinition = "binary(16)")
    private UUID idAnalysisRequest;
    @Column(name = "id_project", nullable = false, columnDefinition = "binary(16)")
    private UUID idProject;
    @Column(name = "requested_by")
    private String requestedBy;
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;
    @Column(name = "status", nullable = false, length = 20)
    private String status;
    @Column(name = "started_at")
    private LocalDateTime startedAt;
    @Column(name = "completed_at")
    private LocalDateTime completedAt;
    /** 여러 레포지토리를 하나의 프로젝트로 통합 출력할지 여부 */
    @Column(name = "merge_repositories", nullable = false)
    private boolean mergeRepositories;

    @Builder
    public AnalysisRequest(UUID idProject, String requestedBy, LocalDate startDate, LocalDate endDate,
                           Boolean mergeRepositories) {
        this.idAnalysisRequest = UUID.randomUUID();
        this.idProject = idProject;
        this.requestedBy = requestedBy;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = "PENDING";
        this.mergeRepositories = mergeRepositories != null ? mergeRepositories : true;
    }

    public void start() {
        this.status = "RUNNING";
        this.startedAt = LocalDateTime.now();
    }

    public void complete() {
        this.status = "COMPLETED";
        this.completedAt = LocalDateTime.now();
    }

    public void fail() {
        this.status = "FAILED";
        this.completedAt = LocalDateTime.now();
    }
}
