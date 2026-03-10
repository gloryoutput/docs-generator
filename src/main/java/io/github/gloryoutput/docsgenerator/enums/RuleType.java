package io.github.gloryoutput.docsgenerator.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 규칙 유형을 정의하는 열거형
 *
 * <p>각 규칙이 어떤 분석 영역에 적용되는지를 구분합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@RequiredArgsConstructor
public enum RuleType {
    /** DB 스키마 변경 관련 규칙 */
    SCHEMA_RULE("스키마 규칙"),
    /** API 변경 관련 규칙 */
    API_RULE("API 규칙"),
    /** 쿼리 변경 관련 규칙 */
    QUERY_RULE("쿼리 규칙"),
    /** 모듈 구조 변경 관련 규칙 */
    MODULE_RULE("모듈 규칙"),
    /** 설정 변경 관련 규칙 */
    CONFIG_RULE("설정 규칙"),
    /** 계층 간 영향 분석 규칙 */
    CROSS_LAYER_RULE("크로스 레이어 규칙");

    private final String description;
}
