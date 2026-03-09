package io.github.gloryoutput.docsgenerator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 문제 해결 과정 단계 DTO
 *
 * <p>sub_steps 또는 descriptions 중 하나는 반드시 존재해야 합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@NoArgsConstructor
@Schema(description = "문제 해결 과정 단계")
public class ResolutionStep {
    @Schema(description = "단계 번호", example = "1")
    private int stepNumber;
    @NotBlank(message = "단계 제목은 필수입니다")
    @Schema(description = "단계 제목 (볼드 표시됨)", example = "상품 디테일 페이지 접속 불가 해결")
    private String title;
    @Valid
    @Schema(description = "하위 단계 목록 (소제목 + 항목)", nullable = true)
    private List<SubStep> subSteps;
    @Schema(description = "하위 단계 없이 단순 설명만 있는 경우", nullable = true)
    private List<String> descriptions;
}
