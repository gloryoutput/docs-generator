package io.github.gloryoutput.docsgenerator.domain.api;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

/**
 * API 스냅샷 리포지토리
 *
 * @author Lodong
 * @since 1.0.0
 */
public interface ApiSnapshotRepository extends JpaRepository<ApiSnapshot, UUID> {
    /**
     * 특정 프로젝트의 가장 최근 API 스냅샷을 조회합니다.
     */
    Optional<ApiSnapshot> findTopByIdProjectOrderByCapturedAtDesc(UUID idProject);
}
