package io.github.gloryoutput.docsgenerator.domain.database;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

/**
 * 스키마 스냅샷 리포지토리
 *
 * @author Lodong
 * @since 1.0.0
 */
public interface SchemaSnapshotRepository extends JpaRepository<SchemaSnapshot, UUID> {
    /**
     * 특정 프로젝트의 가장 최근 스냅샷을 조회합니다.
     */
    Optional<SchemaSnapshot> findTopByIdProjectOrderByCapturedAtDesc(UUID idProject);
}
