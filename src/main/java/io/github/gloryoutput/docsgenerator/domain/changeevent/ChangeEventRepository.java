package io.github.gloryoutput.docsgenerator.domain.changeevent;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

/**
 * 변경 이벤트 리포지토리
 *
 * @author Lodong
 * @since 1.0.0
 */
public interface ChangeEventRepository extends JpaRepository<ChangeEvent, UUID> {
    /**
     * 특정 분석 요청의 변경 이벤트 목록을 조회합니다.
     *
     * @param idAnalysisRequest 분석 요청 ID
     * @return 변경 이벤트 목록
     */
    List<ChangeEvent> findByIdAnalysisRequestAndIsDeletedFalse(UUID idAnalysisRequest);
}
