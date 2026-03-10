package io.github.gloryoutput.docsgenerator.dto.request;

import io.github.gloryoutput.docsgenerator.enums.AuthType;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 레포지토리 인증 정보 생성/수정 요청 DTO
 *
 * <p>authType에 따라 필요한 필드만 입력합니다.</p>
 * <ul>
 *   <li>PERSONAL_ACCESS_TOKEN: username(선택), token(필수)</li>
 *   <li>BASIC_AUTH: username(필수), password(필수)</li>
 *   <li>SSH_KEY: sshKey(필수)</li>
 * </ul>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@NoArgsConstructor
public class CredentialCreateRequest {
    private AuthType authType;
    private String username;
    private String token;
    private String password;
    private String sshKey;
}
