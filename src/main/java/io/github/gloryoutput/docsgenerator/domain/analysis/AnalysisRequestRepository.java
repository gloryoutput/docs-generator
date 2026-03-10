package io.github.gloryoutput.docsgenerator.domain.analysis;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

/**
 * 분석 요청 JPA Repository
 *
 * @author Lodong
 * @since 1.0.0
 */
public interface AnalysisRequestRepository extends JpaRepository<AnalysisRequest, UUID> {
}
