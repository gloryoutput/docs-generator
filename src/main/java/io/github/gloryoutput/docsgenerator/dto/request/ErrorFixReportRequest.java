package io.github.gloryoutput.docsgenerator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 오류 수정 완료 보고서 생성 요청 DTO
 *
 * <p>docx 형식의 오류 수정 완료 보고서를 생성하기 위한 입력 데이터를 담습니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@NoArgsConstructor
@Schema(description = "오류 수정 완료 보고서 생성 요청")
public class ErrorFixReportRequest {
    @NotBlank(message = "보고서 제목은 필수입니다")
    @Schema(description = "보고서 제목", example = "오류 수정 완료 보고서")
    private String title;
    @NotBlank(message = "작성일자는 필수입니다")
    @Schema(description = "작성일자", example = "2025년 1월 23일")
    private String createdDate;
    @NotBlank(message = "회사명은 필수입니다")
    @Schema(description = "회사명", example = "㈜로동")
    private String companyName;
    @NotBlank(message = "제출자 성명은 필수입니다")
    @Schema(description = "제출자 성명", example = "조민정")
    private String authorName;
    @NotBlank(message = "목적은 필수입니다")
    @Schema(description = "보고서 목적", example = "본 보고서는 고객사 웹사이트에서 발생한 오류 문제를 해결한 과정을 정리하고, 작업 결과를 공유하기 위해 작성되었습니다")
    private String purpose;
    @NotNull(message = "문제 정의는 필수입니다")
    @Valid
    @Schema(description = "문제 정의 (발생한 문제 + 원인)")
    private ProblemDefinition problemDefinition;
    @NotEmpty(message = "문제 해결 과정은 필수입니다")
    @Valid
    @Schema(description = "문제 해결 과정 (번호별 단계)")
    private List<ResolutionStep> resolutionProcess;
    @NotEmpty(message = "결과는 필수입니다")
    @Schema(description = "결과 항목 목록")
    private List<String> result;
    @NotEmpty(message = "개선 및 예방 방안은 필수입니다")
    @Valid
    @Schema(description = "개선 및 예방 방안 목록")
    private List<PreventionItem> prevention;
}
