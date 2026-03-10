package io.github.gloryoutput.docsgenerator.domain.repositorymap;

import io.github.gloryoutput.docsgenerator.common.BaseEntity;
import io.github.gloryoutput.docsgenerator.enums.AuthType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.UUID;

/**
 * 레포지토리 접근 인증 정보 엔티티
 *
 * <p>Git 레포지토리에 접근하기 위한 인증 정보를 암호화하여 저장합니다.
 * token, password, sshKey 필드는 AES-GCM으로 암호화된 값입니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Entity
@Table(name = "repository_credential")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepositoryCredential extends BaseEntity {
    @Id
    @Column(name = "id_repository_credential", nullable = false, updatable = false, columnDefinition = "binary(16)")
    private UUID idRepositoryCredential;
    @Column(name = "id_project_repository_map", nullable = false, unique = true, columnDefinition = "binary(16)")
    private UUID idProjectRepositoryMap;
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 30)
    private AuthType authType;
    /** BASIC_AUTH, PERSONAL_ACCESS_TOKEN 시 사용 */
    @Column(name = "username")
    private String username;
    /** PERSONAL_ACCESS_TOKEN 시 암호화된 토큰 */
    @Column(name = "encrypted_token", columnDefinition = "TEXT")
    private String encryptedToken;
    /** BASIC_AUTH 시 암호화된 비밀번호 */
    @Column(name = "encrypted_password", columnDefinition = "TEXT")
    private String encryptedPassword;
    /** SSH_KEY 시 암호화된 SSH 개인 키 */
    @Column(name = "encrypted_ssh_key", columnDefinition = "TEXT")
    private String encryptedSshKey;

    @Builder
    public RepositoryCredential(UUID idProjectRepositoryMap, AuthType authType,
                                 String username, String encryptedToken,
                                 String encryptedPassword, String encryptedSshKey) {
        this.idRepositoryCredential = UUID.randomUUID();
        this.idProjectRepositoryMap = idProjectRepositoryMap;
        this.authType = authType;
        this.username = username;
        this.encryptedToken = encryptedToken;
        this.encryptedPassword = encryptedPassword;
        this.encryptedSshKey = encryptedSshKey;
    }

    /**
     * 인증 정보를 갱신합니다.
     */
    public void update(AuthType authType, String username, String encryptedToken,
                       String encryptedPassword, String encryptedSshKey) {
        this.authType = authType;
        this.username = username;
        this.encryptedToken = encryptedToken;
        this.encryptedPassword = encryptedPassword;
        this.encryptedSshKey = encryptedSshKey;
    }
}
