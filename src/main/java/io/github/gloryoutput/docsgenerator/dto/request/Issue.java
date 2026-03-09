package io.github.gloryoutput.docsgenerator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 발생한 문제 DTO
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@NoArgsConstructor
@Schema(description = "발생한 문제")
public class Issue {
    @NotBlank(message = "문제 제목은 필수입니다")
    @Schema(description = "문제 제목 (볼드 표시됨)", example = "상품 디테일 페이지 접속 불가")
    private String title;
    @NotEmpty(message = "문제 상세 설명은 필수입니다")
    @Schema(description = "문제 상세 설명 (줄 단위)")
    private List<String> descriptions;
}
