package io.github.gloryoutput.docsgenerator.domain.project;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

/**
 * 프로젝트 코드 JPA Repository
 *
 * @author Lodong
 * @since 1.0.0
 */
public interface ProjectCodeRepository extends JpaRepository<ProjectCode, UUID> {
    Optional<ProjectCode> findByProjectCode(String projectCode);
}
