package io.github.gloryoutput.docsgenerator.controller;

import io.github.gloryoutput.docsgenerator.domain.report.Report;
import io.github.gloryoutput.docsgenerator.domain.report.ReportRepository;
import io.github.gloryoutput.docsgenerator.dto.request.AnalysisCreateRequest;
import io.github.gloryoutput.docsgenerator.dto.response.AnalysisResponse;
import io.github.gloryoutput.docsgenerator.dto.response.ApiResponse;
import io.github.gloryoutput.docsgenerator.dto.response.EvidenceResponse;
import io.github.gloryoutput.docsgenerator.dto.response.ReportResponse;
import io.github.gloryoutput.docsgenerator.generator.DocxConverterService;
import io.github.gloryoutput.docsgenerator.service.AnalysisService;
import io.github.gloryoutput.docsgenerator.service.EvidenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

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
    private final EvidenceService evidenceService;
    private final ReportRepository reportRepository;
    private final DocxConverterService docxConverterService;

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

    /**
     * 분석 요청에 대한 Evidence 목록을 조회합니다.
     *
     * @param idAnalysisRequest 분석 요청 ID
     * @return Evidence 응답 목록
     */
    @GetMapping("/{idAnalysisRequest}/evidences")
    @Operation(summary = "Evidence 조회", description = "분석 요청 ID로 수집된 Evidence 목록을 조회합니다")
    public ApiResponse<List<EvidenceResponse>> getEvidences(@PathVariable String idAnalysisRequest) {
        return ApiResponse.ok(evidenceService.getEvidencesByAnalysisRequest(idAnalysisRequest));
    }

    /**
     * 분석 요청에 대한 보고서를 조회합니다.
     *
     * @param idAnalysisRequest 분석 요청 ID
     * @return 보고서 응답
     */
    @GetMapping("/{idAnalysisRequest}/report")
    @Operation(summary = "보고서 조회", description = "분석 요청 ID로 생성된 보고서를 조회합니다")
    public ApiResponse<ReportResponse> getReport(@PathVariable String idAnalysisRequest) {
        UUID analysisId = UUID.fromString(idAnalysisRequest);
        Report report = reportRepository.findByIdAnalysisRequestAndIsDeletedFalse(analysisId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "보고서를 찾을 수 없습니다: " + idAnalysisRequest));
        return ApiResponse.ok(ReportResponse.from(report));
    }

    /**
     * 보고서를 파일로 다운로드합니다.
     *
     * @param idAnalysisRequest 분석 요청 ID
     * @param format 파일 형식 (docx 또는 md, 기본값: docx)
     * @return 파일 바이너리 응답
     */
    @GetMapping("/{idAnalysisRequest}/report/download")
    @Operation(summary = "보고서 다운로드", description = "분석 요청 ID로 생성된 보고서를 파일로 다운로드합니다 (format: docx, md)")
    public ResponseEntity<byte[]> downloadReport(
            @PathVariable String idAnalysisRequest,
            @RequestParam(defaultValue = "docx") String format) throws IOException {
        UUID analysisId = UUID.fromString(idAnalysisRequest);
        Report report = reportRepository.findByIdAnalysisRequestAndIsDeletedFalse(analysisId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "보고서를 찾을 수 없습니다: " + idAnalysisRequest));
        byte[] fileBytes;
        String extension;
        MediaType contentType;
        if ("md".equalsIgnoreCase(format)) {
            fileBytes = report.getReportContent().getBytes(StandardCharsets.UTF_8);
            extension = ".md";
            contentType = MediaType.TEXT_MARKDOWN;
        } else {
            fileBytes = docxConverterService.convertToDocx(report.getReportContent());
            extension = ".docx";
            contentType = MediaType.valueOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        }
        String fileName = URLEncoder.encode("보고서_" + idAnalysisRequest + extension, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                .contentType(contentType)
                .contentLength(fileBytes.length)
                .body(fileBytes);
    }
}
