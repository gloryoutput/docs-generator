package io.github.gloryoutput.docsgenerator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * group 타입 섹션의 서브행 DTO
 *
 * <p>3열 구조에서 두 번째 열(서브 라벨)과 세 번째 열(내용)을 정의합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@NoArgsConstructor
@Schema(description = "group 섹션의 서브행")
public class SectionSubRow {
    @NotBlank(message = "서브행 라벨은 필수입니다")
    @Schema(description = "서브행 라벨 (두 번째 열)", example = "발생한 문제")
    private String label;
    @NotNull(message = "콘텐츠 타입은 필수입니다")
    @Schema(description = "콘텐츠 타입: text, numberedList, titledList, steps")
    private ContentType contentType;
    @Schema(description = "텍스트 값 (contentType=text일 때 사용)")
    private String textValue;
    @Schema(description = "문자열 목록 (contentType=numberedList일 때 사용)")
    private List<String> listItems;
    @Valid
    @Schema(description = "제목+설명 목록 (contentType=titledList일 때 사용)")
    private List<TitledItem> titledItems;
    @Valid
    @Schema(description = "단계 목록 (contentType=steps일 때 사용)")
    private List<ResolutionStep> steps;
}
