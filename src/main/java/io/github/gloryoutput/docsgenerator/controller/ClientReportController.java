package io.github.gloryoutput.docsgenerator.controller;

import io.github.gloryoutput.docsgenerator.domain.clientreport.ClientReport;
import io.github.gloryoutput.docsgenerator.domain.clientreport.ClientReportRepository;
import io.github.gloryoutput.docsgenerator.domain.project.Project;
import io.github.gloryoutput.docsgenerator.domain.project.ProjectRepository;
import io.github.gloryoutput.docsgenerator.dto.response.ApiResponse;
import io.github.gloryoutput.docsgenerator.dto.response.ClientReportResponse;
import io.github.gloryoutput.docsgenerator.generator.DocxConverterService;
import io.github.gloryoutput.docsgenerator.service.ClientReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 클라이언트 보고서 생성 API 컨트롤러
 *
 * <p>로컬 시스템 문서 경로와 카카오톡 대화 파일을 받아 LLM 기반 보고서를 생성합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/client-reports")
@RequiredArgsConstructor
@Tag(name = "클라이언트 보고서", description = "시스템 문서 + 카카오톡 대화 기반 보고서 생성 API")
public class ClientReportController {
    private final ClientReportService clientReportService;
    private final ClientReportRepository clientReportRepository;
    private final ProjectRepository projectRepository;
    private final DocxConverterService docxConverterService;
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyMMdd_HHmmss");

    /**
     * 로컬 시스템 문서 경로와 카카오톡 대화 파일을 기반으로 보고서를 생성합니다.
     *
     * @param idProject 프로젝트 ID
     * @param startDate 보고 시작일 (yyyy-MM-dd)
     * @param endDate 보고 종료일 (yyyy-MM-dd)
     * @param requestedBy 요청자
     * @param systemDocumentsPath 시스템 문서가 위치한 로컬 디렉토리 경로
     * @param chatFile 카카오톡 대화 txt 파일
     * @param urls 참고할 웹페이지 URL 목록
     * @return 생성된 보고서
     */
    @PostMapping(consumes = "multipart/form-data")
    @Operation(summary = "클라이언트 보고서 생성",
            description = "로컬 디렉토리 경로의 시스템 문서, 카카오톡 대화 txt 파일, 웹페이지 URL을 기반으로 LLM 보고서를 생성합니다")
    public ApiResponse<ClientReportResponse> generate(
            @RequestParam String idProject,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(required = false) String requestedBy,
            @RequestParam(required = false) String systemDocumentsPath,
            @RequestPart(value = "chatFile", required = false) MultipartFile chatFile,
            @RequestParam(required = false) List<String> urls) {
        return ApiResponse.ok(clientReportService.generate(idProject, startDate, endDate,
                requestedBy, systemDocumentsPath, chatFile, urls));
    }

    /**
     * 프로젝트 ID로 클라이언트 보고서 목록을 조회합니다.
     *
     * @param idProject 프로젝트 ID
     * @return 보고서 목록
     */
    @GetMapping
    @Operation(summary = "클라이언트 보고서 목록 조회",
            description = "프로젝트 ID로 생성된 클라이언트 보고서 목록을 조회합니다")
    public ApiResponse<List<ClientReportResponse>> getByProject(@RequestParam String idProject) {
        return ApiResponse.ok(clientReportService.getByProject(idProject));
    }

    /**
     * 보고서 ID로 클라이언트 보고서를 조회합니다.
     *
     * @param idClientReport 보고서 ID
     * @return 보고서
     */
    @GetMapping("/{idClientReport}")
    @Operation(summary = "클라이언트 보고서 상세 조회",
            description = "보고서 ID로 클라이언트 보고서를 조회합니다")
    public ApiResponse<ClientReportResponse> getById(@PathVariable String idClientReport) {
        return ApiResponse.ok(clientReportService.getById(idClientReport));
    }

    /**
     * 클라이언트 보고서를 파일로 다운로드합니다.
     *
     * @param idClientReport 보고서 ID
     * @param format 파일 형식 (docx 또는 md, 기본값: docx)
     * @return 파일 바이너리 응답
     */
    @GetMapping("/{idClientReport}/download")
    @Operation(summary = "클라이언트 보고서 다운로드",
            description = "클라이언트 보고서를 파일로 다운로드합니다 (format: docx, md)")
    public ResponseEntity<byte[]> download(
            @PathVariable String idClientReport,
            @RequestParam(defaultValue = "docx") String format) throws IOException {
        UUID reportId = UUID.fromString(idClientReport);
        ClientReport report = clientReportRepository.findByIdClientReportAndIsDeletedFalse(reportId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "클라이언트 보고서를 찾을 수 없습니다: " + idClientReport));
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
            contentType = MediaType.valueOf(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        }
        Project project = projectRepository.findById(report.getIdProject())
                .orElseThrow(() -> new IllegalArgumentException(
                        "프로젝트를 찾을 수 없습니다: " + report.getIdProject()));
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        String rawFileName = project.getProjectName() + " 클라이언트보고서_" + timestamp + extension;
        String fileName = URLEncoder.encode(rawFileName, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                .contentType(contentType)
                .contentLength(fileBytes.length)
                .body(fileBytes);
    }
}
