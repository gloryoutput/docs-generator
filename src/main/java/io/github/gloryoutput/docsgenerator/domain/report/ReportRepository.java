package io.github.gloryoutput.docsgenerator.domain.report;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

/**
 * 보고서 리포지토리
 *
 * @author Lodong
 * @since 1.0.0
 */
public interface ReportRepository extends JpaRepository<Report, UUID> {
    /**
     * 특정 분석 요청의 보고서를 조회합니다.
     *
     * @param idAnalysisRequest 분석 요청 ID
     * @return 보고서
     */
    Optional<Report> findByIdAnalysisRequestAndIsDeletedFalse(UUID idAnalysisRequest);
}
