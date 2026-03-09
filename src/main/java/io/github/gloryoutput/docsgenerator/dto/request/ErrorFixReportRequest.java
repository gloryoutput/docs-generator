package io.github.gloryoutput.docsgenerator.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 오류 수정 완료 보고서 생성 요청 DTO
 *
 * <p>docx 형식의 오류 수정 완료 보고서를 생성하기 위한 입력 데이터를 담습니다.
 * 테이블 행은 {@code sections} 리스트로 동적으로 구성됩니다.</p>
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
    @Schema(description = "수신자", example = "㈜고영 김철수", nullable = true)
    private String recipient;
    @Schema(description = "담당자", example = "조민정", nullable = true)
    private String manager;
    @NotEmpty(message = "섹션은 최소 1개 필수입니다")
    @Valid
    @Schema(description = "테이블 섹션(행) 목록 - 순서대로 테이블 행이 생성됩니다")
    private List<ReportSection> sections;
}
