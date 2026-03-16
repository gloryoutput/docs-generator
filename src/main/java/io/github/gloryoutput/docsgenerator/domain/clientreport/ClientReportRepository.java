package io.github.gloryoutput.docsgenerator.domain.clientreport;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 클라이언트 보고서 리포지토리
 *
 * @author Lodong
 * @since 1.0.0
 */
public interface ClientReportRepository extends JpaRepository<ClientReport, UUID> {
    /**
     * 프로젝트 ID로 클라이언트 보고서 목록을 조회합니다.
     *
     * @param idProject 프로젝트 ID
     * @return 보고서 목록
     */
    List<ClientReport> findByIdProjectAndIsDeletedFalseOrderByGeneratedAtDesc(UUID idProject);

    /**
     * ID로 삭제되지 않은 보고서를 조회합니다.
     *
     * @param idClientReport 보고서 ID
     * @return 보고서
     */
    Optional<ClientReport> findByIdClientReportAndIsDeletedFalse(UUID idClientReport);
}
