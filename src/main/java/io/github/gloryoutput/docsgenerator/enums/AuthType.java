package io.github.gloryoutput.docsgenerator.enums;

/**
 * 레포지토리 접근 인증 방식
 *
 * @author Lodong
 * @since 1.0.0
 */
public enum AuthType {
    /** GitHub/GitLab Personal Access Token */
    PERSONAL_ACCESS_TOKEN,
    /** SSH 개인 키 */
    SSH_KEY,
    /** 아이디/비밀번호 기본 인증 */
    BASIC_AUTH
}
