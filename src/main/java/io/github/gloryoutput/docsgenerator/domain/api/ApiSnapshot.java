package io.github.gloryoutput.docsgenerator.domain.api;

import io.github.gloryoutput.docsgenerator.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * API endpoint 스냅샷 엔티티
 *
 * <p>분석 시점의 endpoint 목록을 JSON으로 저장하여
 * 이전 스냅샷과 비교해 변경 사항을 감지합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Entity
@Table(name = "api_snapshot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiSnapshot extends BaseEntity {
    @Id
    @Column(name = "id_api_snapshot", nullable = false, updatable = false, columnDefinition = "binary(16)")
    private UUID idApiSnapshot;
    @Column(name = "id_project", nullable = false, columnDefinition = "binary(16)")
    private UUID idProject;
    @Column(name = "id_analysis_request", nullable = false, columnDefinition = "binary(16)")
    private UUID idAnalysisRequest;
    /** endpoint 목록 JSON */
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "LONGTEXT")
    private String snapshotJson;
    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    @Builder
    public ApiSnapshot(UUID idProject, UUID idAnalysisRequest, String snapshotJson) {
        this.idApiSnapshot = UUID.randomUUID();
        this.idProject = idProject;
        this.idAnalysisRequest = idAnalysisRequest;
        this.snapshotJson = snapshotJson;
        this.capturedAt = LocalDateTime.now();
    }
}
