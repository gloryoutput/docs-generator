package io.github.gloryoutput.docsgenerator.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDate;

/**
 * 분석 요청 DTO
 *
 * <p>id_project와 분석 기간(startDate, endDate)을 전달합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@NoArgsConstructor
public class AnalysisCreateRequest {
    private String idProject;
    private LocalDate startDate;
    private LocalDate endDate;
    private String requestedBy;
    /** 여러 레포지토리를 하나의 프로젝트로 통합하여 보고서를 출력할지 여부 (기본값: true) */
    private Boolean mergeRepositories;
}
