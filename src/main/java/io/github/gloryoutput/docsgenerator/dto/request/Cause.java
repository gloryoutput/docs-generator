package io.github.gloryoutput.docsgenerator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 문제 원인 DTO
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@NoArgsConstructor
@Schema(description = "문제 원인")
public class Cause {
    @NotBlank(message = "원인 제목은 필수입니다")
    @Schema(description = "원인 제목 (볼드 표시됨)", example = "호환성 문제")
    private String title;
    @NotBlank(message = "원인 상세 설명은 필수입니다")
    @Schema(description = "원인 상세 설명")
    private String description;
}
