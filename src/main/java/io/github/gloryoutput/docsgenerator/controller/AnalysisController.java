package io.github.gloryoutput.docsgenerator.controller;

import io.github.gloryoutput.docsgenerator.dto.request.AnalysisCreateRequest;
import io.github.gloryoutput.docsgenerator.dto.response.AnalysisResponse;
import io.github.gloryoutput.docsgenerator.dto.response.ApiResponse;
import io.github.gloryoutput.docsgenerator.service.AnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 분석 요청 API 컨트롤러
 *
 * <p>project_code와 기간을 전달하여 레포지토리의 Git 변경 데이터를 수집합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
@Tag(name = "분석 요청", description = "레포지토리 변경 분석 API")
public class AnalysisController {
    private final AnalysisService analysisService;

    /**
     * 분석을 요청합니다.
     *
     * <p>project_code에 등록된 모든 레포지토리에 대해
     * 지정 기간 내 Git 커밋/변경 파일을 수집합니다.</p>
     *
     * @param request 분석 요청 (projectCode, startDate, endDate)
     * @return 분석 결과
     */
    @PostMapping
    @Operation(summary = "분석 요청", description = "project_code와 기간을 전달하여 레포지토리 변경 데이터를 수집합니다")
    public ApiResponse<AnalysisResponse> analyze(@RequestBody AnalysisCreateRequest request) {
        return ApiResponse.ok(analysisService.analyze(request));
    }
}
