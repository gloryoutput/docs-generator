package io.github.gloryoutput.docsgenerator.domain.database;

import io.github.gloryoutput.docsgenerator.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DB 스키마 스냅샷 엔티티
 *
 * <p>분석 시점의 DB 스키마(테이블, 컬럼, 인덱스)를 JSON으로 저장하여
 * 이전 스냅샷과 비교해 변경 사항을 감지합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Entity
@Table(name = "schema_snapshot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SchemaSnapshot extends BaseEntity {
    @Id
    @Column(name = "id_schema_snapshot", nullable = false, updatable = false, columnDefinition = "binary(16)")
    private UUID idSchemaSnapshot;
    @Column(name = "id_project", nullable = false, columnDefinition = "binary(16)")
    private UUID idProject;
    @Column(name = "id_analysis_request", nullable = false, columnDefinition = "binary(16)")
    private UUID idAnalysisRequest;
    /** 테이블/컬럼/인덱스 정보를 담은 JSON */
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "LONGTEXT")
    private String snapshotJson;
    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    @Builder
    public SchemaSnapshot(UUID idProject, UUID idAnalysisRequest, String snapshotJson) {
        this.idSchemaSnapshot = UUID.randomUUID();
        this.idProject = idProject;
        this.idAnalysisRequest = idAnalysisRequest;
        this.snapshotJson = snapshotJson;
        this.capturedAt = LocalDateTime.now();
    }
}
