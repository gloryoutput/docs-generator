package io.github.gloryoutput.docsgenerator.dto.response;

import io.github.gloryoutput.docsgenerator.domain.repositorymap.RepositoryCredential;
import io.github.gloryoutput.docsgenerator.enums.AuthType;
import lombok.Builder;
import lombok.Getter;
import java.time.LocalDateTime;

/**
 * 레포지토리 인증 정보 응답 DTO
 *
 * <p>민감 정보(토큰, 비밀번호, SSH 키)는 마스킹 처리하여 반환합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@Builder
public class CredentialResponse {
    private String idRepositoryCredential;
    private String idProjectRepositoryMap;
    private AuthType authType;
    private String username;
    /** 토큰 존재 여부만 표시 (값은 마스킹) */
    private boolean hasToken;
    /** 비밀번호 존재 여부만 표시 (값은 마스킹) */
    private boolean hasPassword;
    /** SSH 키 존재 여부만 표시 (값은 마스킹) */
    private boolean hasSshKey;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static CredentialResponse from(RepositoryCredential credential) {
        return CredentialResponse.builder()
                .idRepositoryCredential(credential.getIdRepositoryCredential().toString())
                .idProjectRepositoryMap(credential.getIdProjectRepositoryMap().toString())
                .authType(credential.getAuthType())
                .username(credential.getUsername())
                .hasToken(credential.getEncryptedToken() != null)
                .hasPassword(credential.getEncryptedPassword() != null)
                .hasSshKey(credential.getEncryptedSshKey() != null)
                .createdAt(credential.getCreatedAt())
                .updatedAt(credential.getUpdatedAt())
                .build();
    }
}
