package io.github.gloryoutput.docsgenerator.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 섹션 레이아웃 유형
 *
 * <ul>
 *   <li>{@code simple} - 헤더(gridSpan=2) + 내용 셀 (2셀 병합 행)</li>
 *   <li>{@code group} - 부모 헤더(세로 병합) + 서브행들 (3셀 행)</li>
 * </ul>
 *
 * @author Lodong
 * @since 1.0.0
 */
public enum SectionType {
    @JsonProperty("simple") SIMPLE,
    @JsonProperty("group") GROUP
}
