package io.github.gloryoutput.docsgenerator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 보고서 테이블 섹션(행) DTO
 *
 * <p>테이블의 각 행을 동적으로 정의합니다.</p>
 * <ul>
 *   <li>{@code simple} - 헤더(2열 병합) + 내용 셀. contentType과 해당 필드를 사용합니다.</li>
 *   <li>{@code group} - 부모 헤더(세로 병합) + 서브행들. subRows를 사용합니다.</li>
 * </ul>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@NoArgsConstructor
@Schema(description = "보고서 테이블 섹션 (행)")
public class ReportSection {
    @NotNull(message = "섹션 타입은 필수입니다")
    @Schema(description = "섹션 타입: simple(2셀 병합) / group(3셀 세로병합)")
    private SectionType type;
    @NotBlank(message = "섹션 라벨은 필수입니다")
    @Schema(description = "섹션 라벨 (헤더 텍스트)", example = "회사명")
    private String label;
    // === simple 타입 전용 필드 ===
    @Schema(description = "콘텐츠 타입 (simple 타입일 때 필수): text, numberedList, titledList, steps")
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
    // === group 타입 전용 필드 ===
    @Valid
    @Schema(description = "서브행 목록 (group 타입일 때 필수)")
    private List<SectionSubRow> subRows;
}
