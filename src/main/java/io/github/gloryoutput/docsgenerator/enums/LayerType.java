package io.github.gloryoutput.docsgenerator.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 프로젝트 계층(레이어) 유형을 정의하는 열거형
 *
 * <p>소스 파일이 속하는 아키텍처 계층을 구분합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@RequiredArgsConstructor
public enum LayerType {
    /** 컨트롤러 계층 */
    CONTROLLER("컨트롤러"),
    /** 서비스 계층 */
    SERVICE("서비스"),
    /** 레포지토리 계층 */
    REPOSITORY("레포지토리"),
    /** 엔티티 계층 */
    ENTITY("엔티티"),
    /** 마이그레이션 계층 */
    MIGRATION("마이그레이션"),
    /** 설정 계층 */
    CONFIG("설정"),
    /** 분류 불가 */
    UNKNOWN("기타");

    private final String description;
}
