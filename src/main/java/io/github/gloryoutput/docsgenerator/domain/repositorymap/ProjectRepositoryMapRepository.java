package io.github.gloryoutput.docsgenerator.domain.repositorymap;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/**
 * 프로젝트-레포지토리 매핑 JPA Repository
 *
 * @author Lodong
 * @since 1.0.0
 */
public interface ProjectRepositoryMapRepository extends JpaRepository<ProjectRepositoryMap, String> {
    List<ProjectRepositoryMap> findByProjectUuidAndIsDeletedFalse(String projectUuid);
}
