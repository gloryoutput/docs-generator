package io.github.gloryoutput.docsgenerator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 개선 및 예방 방안 DTO
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@NoArgsConstructor
@Schema(description = "개선 및 예방 방안")
public class PreventionItem {
    @NotBlank(message = "방안 제목은 필수입니다")
    @Schema(description = "방안 제목 (볼드 표시됨)", example = "플러그인 관리 강화")
    private String title;
    @NotEmpty(message = "방안 상세 설명은 필수입니다")
    @Schema(description = "방안 상세 설명 (줄 단위)")
    private List<String> descriptions;
}
