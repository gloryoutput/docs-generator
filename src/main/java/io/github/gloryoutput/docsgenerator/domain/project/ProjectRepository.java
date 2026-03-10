package io.github.gloryoutput.docsgenerator.domain.project;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * 프로젝트 JPA Repository
 *
 * @author Lodong
 * @since 1.0.0
 */
public interface ProjectRepository extends JpaRepository<Project, String> {
    Optional<Project> findByProjectCode(String projectCode);
}
