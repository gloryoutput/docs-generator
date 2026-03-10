package io.github.gloryoutput.docsgenerator.service;

import io.github.gloryoutput.docsgenerator.domain.repositorymap.ProjectRepositoryMapRepository;
import io.github.gloryoutput.docsgenerator.domain.repositorymap.RepositoryCredential;
import io.github.gloryoutput.docsgenerator.domain.repositorymap.RepositoryCredentialRepository;
import io.github.gloryoutput.docsgenerator.dto.request.CredentialCreateRequest;
import io.github.gloryoutput.docsgenerator.dto.response.CredentialResponse;
import io.github.gloryoutput.docsgenerator.enums.AuthType;
import io.github.gloryoutput.docsgenerator.util.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;

/**
 * 레포지토리 인증 정보 관리 서비스
 *
 * <p>인증 정보를 AES-GCM으로 암호화하여 저장하고,
 * 조회 시 민감 정보는 마스킹 처리합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CredentialService {
    private final RepositoryCredentialRepository credentialRepository;
    private final ProjectRepositoryMapRepository repositoryMapRepository;
    @Value("${app.encryption.key:docs-generator-default-key-32ch}")
    private String encryptionKey;

    /**
     * 레포지토리에 인증 정보를 설정합니다 (없으면 생성, 있으면 갱신).
     *
     * @param idProjectRepositoryMap 레포지토리 매핑 ID
     * @param request 인증 정보 요청
     * @return 설정된 인증 정보 (마스킹)
     */
    @Transactional
    public CredentialResponse setCredential(String idProjectRepositoryMap, CredentialCreateRequest request) {
        UUID mapId = UUID.fromString(idProjectRepositoryMap);
        repositoryMapRepository.findById(mapId)
                .orElseThrow(() -> new IllegalArgumentException("레포지토리를 찾을 수 없습니다: " + idProjectRepositoryMap));
        validateRequest(request);
        String encryptedToken = encryptIfPresent(request.getToken());
        String encryptedPassword = encryptIfPresent(request.getPassword());
        String encryptedSshKey = encryptIfPresent(request.getSshKey());
        Optional<RepositoryCredential> existing =
                credentialRepository.findByIdProjectRepositoryMapAndIsDeletedFalse(mapId);
        if (existing.isPresent()) {
            RepositoryCredential credential = existing.get();
            credential.update(request.getAuthType(), request.getUsername(),
                    encryptedToken, encryptedPassword, encryptedSshKey);
            return CredentialResponse.from(credentialRepository.save(credential));
        }
        RepositoryCredential credential = RepositoryCredential.builder()
                .idProjectRepositoryMap(mapId)
                .authType(request.getAuthType())
                .username(request.getUsername())
                .encryptedToken(encryptedToken)
                .encryptedPassword(encryptedPassword)
                .encryptedSshKey(encryptedSshKey)
                .build();
        return CredentialResponse.from(credentialRepository.save(credential));
    }

    /**
     * 레포지토리의 인증 정보를 조회합니다 (마스킹 처리).
     *
     * @param idProjectRepositoryMap 레포지토리 매핑 ID
     * @return 인증 정보 (마스킹)
     */
    public CredentialResponse getCredential(String idProjectRepositoryMap) {
        RepositoryCredential credential = credentialRepository
                .findByIdProjectRepositoryMapAndIsDeletedFalse(UUID.fromString(idProjectRepositoryMap))
                .orElseThrow(() -> new IllegalArgumentException("인증 정보가 설정되지 않았습니다: " + idProjectRepositoryMap));
        return CredentialResponse.from(credential);
    }

    /**
     * 레포지토리의 인증 정보를 삭제합니다 (논리 삭제).
     *
     * @param idProjectRepositoryMap 레포지토리 매핑 ID
     */
    @Transactional
    public void deleteCredential(String idProjectRepositoryMap) {
        RepositoryCredential credential = credentialRepository
                .findByIdProjectRepositoryMapAndIsDeletedFalse(UUID.fromString(idProjectRepositoryMap))
                .orElseThrow(() -> new IllegalArgumentException("인증 정보가 설정되지 않았습니다: " + idProjectRepositoryMap));
        credential.softDelete();
        credentialRepository.save(credential);
    }

    /**
     * 복호화된 토큰을 반환합니다 (내부 서비스용, API 노출 금지).
     *
     * @param idProjectRepositoryMap 레포지토리 매핑 ID
     * @return 복호화된 토큰
     */
    public String getDecryptedToken(String idProjectRepositoryMap) {
        RepositoryCredential credential = credentialRepository
                .findByIdProjectRepositoryMapAndIsDeletedFalse(UUID.fromString(idProjectRepositoryMap))
                .orElseThrow(() -> new IllegalArgumentException("인증 정보가 설정되지 않았습니다"));
        if (credential.getEncryptedToken() != null) {
            return EncryptionUtil.decrypt(credential.getEncryptedToken(), encryptionKey);
        }
        if (credential.getEncryptedPassword() != null) {
            return EncryptionUtil.decrypt(credential.getEncryptedPassword(), encryptionKey);
        }
        return null;
    }

    private void validateRequest(CredentialCreateRequest request) {
        if (request.getAuthType() == null) {
            throw new IllegalArgumentException("authType은 필수입니다");
        }
        switch (request.getAuthType()) {
            case PERSONAL_ACCESS_TOKEN -> {
                if (request.getToken() == null || request.getToken().isBlank()) {
                    throw new IllegalArgumentException("PERSONAL_ACCESS_TOKEN 방식은 token이 필수입니다");
                }
            }
            case BASIC_AUTH -> {
                if (request.getUsername() == null || request.getUsername().isBlank()) {
                    throw new IllegalArgumentException("BASIC_AUTH 방식은 username이 필수입니다");
                }
                if (request.getPassword() == null || request.getPassword().isBlank()) {
                    throw new IllegalArgumentException("BASIC_AUTH 방식은 password가 필수입니다");
                }
            }
            case SSH_KEY -> {
                if (request.getSshKey() == null || request.getSshKey().isBlank()) {
                    throw new IllegalArgumentException("SSH_KEY 방식은 sshKey가 필수입니다");
                }
            }
        }
    }

    private String encryptIfPresent(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return EncryptionUtil.encrypt(value, encryptionKey);
    }
}
