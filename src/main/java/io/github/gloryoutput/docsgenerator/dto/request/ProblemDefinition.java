package io.github.gloryoutput.docsgenerator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 문제 정의 DTO
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@NoArgsConstructor
@Schema(description = "문제 정의 (발생한 문제 + 원인)")
public class ProblemDefinition {
    @NotEmpty(message = "발생한 문제 목록은 필수입니다")
    @Valid
    @Schema(description = "발생한 문제 목록")
    private List<Issue> issues;
    @NotEmpty(message = "문제 원인 목록은 필수입니다")
    @Valid
    @Schema(description = "문제 원인 목록")
    private List<Cause> causes;
}
