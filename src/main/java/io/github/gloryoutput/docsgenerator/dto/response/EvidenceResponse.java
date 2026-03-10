package io.github.gloryoutput.docsgenerator.dto.response;

import io.github.gloryoutput.docsgenerator.domain.evidence.Evidence;
import lombok.Builder;
import lombok.Getter;

/**
 * Evidence 응답 DTO
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@Builder
public class EvidenceResponse {
    private String idEvidence;
    private String idAnalysisRequest;
    private String idProject;
    private String sourceType;
    private String sourceName;
    private String targetPath;
    private String changeType;
    private String metadataJson;

    /**
     * Evidence 엔티티를 응답 DTO로 변환합니다.
     *
     * @param evidence Evidence 엔티티
     * @return EvidenceResponse
     */
    public static EvidenceResponse from(Evidence evidence) {
        return EvidenceResponse.builder()
                .idEvidence(evidence.getIdEvidence().toString())
                .idAnalysisRequest(evidence.getIdAnalysisRequest().toString())
                .idProject(evidence.getIdProject().toString())
                .sourceType(evidence.getSourceType())
                .sourceName(evidence.getSourceName())
                .targetPath(evidence.getTargetPath())
                .changeType(evidence.getChangeType())
                .metadataJson(evidence.getMetadataJson())
                .build();
    }
}
