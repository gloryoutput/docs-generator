package io.github.gloryoutput.docsgenerator.domain.repositorymap;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

/**
 * 레포지토리 인증 정보 JPA Repository
 *
 * @author Lodong
 * @since 1.0.0
 */
public interface RepositoryCredentialRepository extends JpaRepository<RepositoryCredential, UUID> {
    Optional<RepositoryCredential> findByIdProjectRepositoryMapAndIsDeletedFalse(UUID idProjectRepositoryMap);
}
