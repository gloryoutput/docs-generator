package io.github.gloryoutput.docsgenerator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 하위 단계 DTO
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@NoArgsConstructor
@Schema(description = "하위 단계 (소제목 + 항목)")
public class SubStep {
    @NotBlank(message = "소제목은 필수입니다")
    @Schema(description = "소제목 (볼드 표시됨)", example = "초기 조사")
    private String title;
    @NotEmpty(message = "항목 목록은 필수입니다")
    @Schema(description = "항목 목록 ('- ' 접두사로 표시)")
    private List<String> items;
}
