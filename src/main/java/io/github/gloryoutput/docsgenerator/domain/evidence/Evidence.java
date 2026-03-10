package io.github.gloryoutput.docsgenerator.domain.evidence;

import io.github.gloryoutput.docsgenerator.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.UUID;

/**
 * 분석 증거 엔티티
 *
 * <p>각 분석기(Git, DB Schema, API)의 결과를 통합된 증거 형태로 저장합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Entity
@Table(name = "evidence")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Evidence extends BaseEntity {
    @Id
    @Column(name = "id_evidence", nullable = false, updatable = false, columnDefinition = "binary(16)")
    private UUID idEvidence;
    @Column(name = "id_analysis_request", nullable = false, columnDefinition = "binary(16)")
    private UUID idAnalysisRequest;
    @Column(name = "id_project", nullable = false, columnDefinition = "binary(16)")
    private UUID idProject;
    @Column(name = "source_type", nullable = false)
    private String sourceType;
    @Column(name = "source_name")
    private String sourceName;
    @Column(name = "target_path")
    private String targetPath;
    @Column(name = "change_type", nullable = false)
    private String changeType;
    @Column(name = "metadata_json", columnDefinition = "LONGTEXT")
    @Lob
    private String metadataJson;

    @Builder
    public Evidence(UUID idAnalysisRequest, UUID idProject, String sourceType,
                    String sourceName, String targetPath, String changeType, String metadataJson) {
        this.idEvidence = UUID.randomUUID();
        this.idAnalysisRequest = idAnalysisRequest;
        this.idProject = idProject;
        this.sourceType = sourceType;
        this.sourceName = sourceName;
        this.targetPath = targetPath;
        this.changeType = changeType;
        this.metadataJson = metadataJson;
    }
}
