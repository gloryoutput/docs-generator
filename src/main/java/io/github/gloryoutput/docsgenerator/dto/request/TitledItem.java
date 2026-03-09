package io.github.gloryoutput.docsgenerator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 제목 + 설명 목록 항목 DTO
 *
 * <p>발생한 문제, 문제 원인, 개선 방안 등 제목과 설명이 있는 항목을 통합 표현합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@NoArgsConstructor
@Schema(description = "제목 + 설명 목록 항목")
public class TitledItem {
    @NotBlank(message = "항목 제목은 필수입니다")
    @Schema(description = "항목 제목 (볼드 표시)", example = "호환성 문제")
    private String title;
    @NotEmpty(message = "설명은 최소 1개 필수입니다")
    @Schema(description = "설명 목록 (들여쓰기 표시)")
    private List<String> descriptions;
}
