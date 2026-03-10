package io.github.gloryoutput.docsgenerator.domain.evidence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

/**
 * Evidence JPA Repository
 *
 * @author Lodong
 * @since 1.0.0
 */
public interface EvidenceRepository extends JpaRepository<Evidence, UUID> {
    /**
     * 분석 요청 ID로 삭제되지 않은 증거를 조회합니다.
     *
     * @param idAnalysisRequest 분석 요청 ID
     * @return 증거 목록
     */
    List<Evidence> findByIdAnalysisRequestAndIsDeletedFalse(UUID idAnalysisRequest);

    /**
     * 프로젝트 ID로 삭제되지 않은 증거를 조회합니다.
     *
     * @param idProject 프로젝트 ID
     * @return 증거 목록
     */
    List<Evidence> findByIdProjectAndIsDeletedFalse(UUID idProject);
}
