package io.github.gloryoutput.docsgenerator.service;

import io.github.gloryoutput.docsgenerator.domain.clientreport.ClientReport;
import io.github.gloryoutput.docsgenerator.domain.clientreport.ClientReportRepository;
import io.github.gloryoutput.docsgenerator.domain.project.Project;
import io.github.gloryoutput.docsgenerator.domain.project.ProjectRepository;
import io.github.gloryoutput.docsgenerator.dto.response.ClientReportResponse;
import io.github.gloryoutput.docsgenerator.summarizer.LlmClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 클라이언트 보고서 생성 서비스
 *
 * <p>로컬 디렉토리의 시스템 문서와 카카오톡 대화 내용을 파싱한 뒤,
 * 섹션별로 LLM을 호출하여 원본 보고서(오류 수정 완료 보고서) 형식의 보고서를 생성합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@Slf4j
public class ClientReportService {
    /** 섹션별 LLM에 전달하는 공통 원칙 */
    private static final String COMMON_RULES =
            "## 공통 원칙\n" +
            "- 공식 업무 보고서에 적합한 격식체 한국어를 사용합니다 (~했습니다, ~되었습니다).\n" +
            "- 비개발자(경영진, 고객사 담당자)가 읽는 보고서입니다.\n" +
            "- 코드명, 클래스명, 파일 경로, 테이블명 등 기술 용어를 사용하지 마세요.\n" +
            "- 카카오톡 대화를 인용하거나 발화자 이름/닉네임을 기재하지 마세요.\n" +
            "- 시스템 문서 내용을 그대로 옮기지 마세요.\n" +
            "- 원본에 없는 사실이나 추측을 추가하지 마세요.\n" +
            "- 결과물은 해당 섹션의 본문 내용만 출력하세요. 섹션 제목(###)은 출력하지 마세요.\n" +
            "- 부가 설명이나 인사말은 포함하지 마세요.\n";
    private static final int MAX_CONTENT_CHARS = 12000;
    private static final DateTimeFormatter DATE_KR_FORMATTER = DateTimeFormatter.ofPattern("yyyy년 MM월 dd일");
    private final ClientReportRepository clientReportRepository;
    private final ProjectRepository projectRepository;
    private final LlmClient llmClient;

    public ClientReportService(ClientReportRepository clientReportRepository,
                               ProjectRepository projectRepository,
                               @Autowired(required = false) LlmClient llmClient) {
        this.clientReportRepository = clientReportRepository;
        this.projectRepository = projectRepository;
        this.llmClient = llmClient;
        if (llmClient != null) {
            log.info("ClientReportService LLM 활성화 - client: {}", llmClient.getClass().getSimpleName());
        } else {
            log.info("ClientReportService LLM 비활성화 (LlmClient 빈 없음)");
        }
    }

    /**
     * 로컬 디렉토리의 시스템 문서와 카카오톡 대화 파일을 기반으로 보고서를 생성합니다.
     *
     * @param idProject 프로젝트 ID
     * @param requestedBy 요청자
     * @param systemDocumentsPath 시스템 문서가 위치한 로컬 디렉토리 경로
     * @param chatFile 카카오톡 대화 txt 파일
     * @return 생성된 보고서 응답
     */
    public ClientReportResponse generate(String idProject, String requestedBy,
                                         String systemDocumentsPath,
                                         MultipartFile chatFile) {
        UUID projectId = UUID.fromString(idProject);
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + idProject));
        // 로컬 디렉토리에서 시스템 문서 전체 읽기
        StringBuilder docsContent = new StringBuilder();
        StringBuilder docNames = new StringBuilder();
        List<File> documentFiles = readLocalDirectory(systemDocumentsPath);
        for (File file : documentFiles) {
            String fileName = file.getName();
            if (docNames.length() > 0) docNames.append(", ");
            docNames.append(fileName);
            String content = readLocalFile(file);
            docsContent.append("## 문서: ").append(fileName).append("\n\n");
            docsContent.append(content).append("\n\n");
        }
        log.info("시스템 문서 {}건 로드 완료 - path: {}", documentFiles.size(), systemDocumentsPath);
        // 카카오톡 대화 파싱
        String chatContent = "";
        String chatFileName = "";
        if (chatFile != null && !chatFile.isEmpty()) {
            chatFileName = chatFile.getOriginalFilename();
            chatContent = readMultipartFile(chatFile);
        }
        // 분석 자료 구성
        String sourceData = buildSourceData(docsContent.toString(), chatContent);
        // 섹션별 LLM 호출로 보고서 생성
        String reportContent = generateReport(project.getProjectName(), sourceData);
        // 엔티티 저장
        ClientReport entity = ClientReport.builder()
                .idProject(projectId)
                .requestedBy(requestedBy)
                .documentNames(docNames.toString())
                .chatFileName(chatFileName)
                .reportContent(reportContent)
                .build();
        clientReportRepository.save(entity);
        log.info("클라이언트 보고서 생성 완료 - id: {}, project: {}",
                entity.getIdClientReport(), project.getProjectName());
        return ClientReportResponse.from(entity);
    }

    /**
     * 프로젝트 ID로 클라이언트 보고서 목록을 조회합니다.
     */
    public List<ClientReportResponse> getByProject(String idProject) {
        UUID projectId = UUID.fromString(idProject);
        return clientReportRepository.findByIdProjectAndIsDeletedFalseOrderByGeneratedAtDesc(projectId)
                .stream()
                .map(ClientReportResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * 보고서 ID로 클라이언트 보고서를 조회합니다.
     */
    public ClientReportResponse getById(String idClientReport) {
        UUID reportId = UUID.fromString(idClientReport);
        ClientReport entity = clientReportRepository.findByIdClientReportAndIsDeletedFalse(reportId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "클라이언트 보고서를 찾을 수 없습니다: " + idClientReport));
        return ClientReportResponse.from(entity);
    }

    /**
     * 섹션별로 LLM을 호출하여 6개 섹션 보고서를 조립합니다.
     *
     * <p>각 섹션의 ### 제목은 코드가 고정하고, 내용만 LLM이 채웁니다.
     * 이를 통해 원본 보고서(오류 수정 완료 보고서)와 동일한 구조를 보장합니다.</p>
     */
    private String generateReport(String projectName, String sourceData) {
        if (llmClient == null) {
            throw new IllegalStateException(
                    "LLM이 비활성화되어 보고서를 생성할 수 없습니다. " +
                    "app.llm.enabled=true 설정이 필요합니다.");
        }
        String createdDate = LocalDateTime.now().format(DATE_KR_FORMATTER);
        StringBuilder report = new StringBuilder();
        report.append("# 소프트웨어 변경 보고서\n\n");
        report.append("작성일자: ").append(createdDate).append("\n\n");
        // 1. 목적
        log.info("[1/6] 목적 섹션 생성 중...");
        String purpose = callLlmForSection(projectName, sourceData,
                "이 보고서의 '목적' 섹션을 작성하세요.\n" +
                "보고서 작성 배경과 목적을 2~3문장으로 서술합니다.\n" +
                "예: '본 보고서는 {프로젝트}에서 클라이언트 요청에 따라 수행한 기능 수정, 신규 개발, " +
                "오류 수정 작업의 과정과 결과를 정리하기 위해 작성되었습니다.'\n" +
                "전체 요청 건수와 주요 변경 유형(기획수정/신기능/오류수정)별 건수를 포함하세요.");
        report.append("### 목적\n\n").append(purpose).append("\n\n");
        // 2. 발생한 문제
        log.info("[2/6] 발생한 문제 섹션 생성 중...");
        String problems = callLlmForSection(projectName, sourceData,
                "이 보고서의 '발생한 문제' 섹션을 작성하세요.\n" +
                "클라이언트가 요청하거나 제보한 사항을 대분류(기획수정/신기능/오류수정)별로 정리합니다.\n" +
                "각 항목마다:\n" +
                "- 어떤 기능/화면에서 문제가 있었거나 변경이 필요했는지\n" +
                "- 클라이언트가 겪은 불편이나 요청 배경\n" +
                "형식:\n" +
                "1. **{기능/화면명}**\n" +
                "   {문제 상황 또는 요청 배경 2~3문장}");
        report.append("### 발생한 문제\n\n").append(problems).append("\n\n");
        // 3. 문제 원인
        log.info("[3/6] 문제 원인 섹션 생성 중...");
        String causes = callLlmForSection(projectName, sourceData,
                "이 보고서의 '문제 원인' 섹션을 작성하세요.\n" +
                "각 주요 항목별로 왜 변경/수정이 필요했는지 원인을 분석합니다.\n" +
                "형식:\n" +
                "1. **{기능/화면명}**\n" +
                "   {원인 분석 1~2문장}");
        report.append("### 문제 원인\n\n").append(causes).append("\n\n");
        // 4. 문제 해결 과정
        log.info("[4/6] 문제 해결 과정 섹션 생성 중...");
        String process = callLlmForSection(projectName, sourceData,
                "이 보고서의 '문제 해결 과정' 섹션을 작성하세요.\n" +
                "실제 수행한 작업을 단계별로 정리합니다.\n" +
                "형식:\n" +
                "1. **{기능/화면명} 처리**\n" +
                "   1. 초기 조사\n" +
                "      - {조사 내용}\n" +
                "   2. 수정/개발 작업\n" +
                "      - {작업 내용}\n" +
                "   3. 테스트 및 확인\n" +
                "      - {검증 내용}");
        report.append("### 문제 해결 과정\n\n").append(process).append("\n\n");
        // 5. 결과
        log.info("[5/6] 결과 섹션 생성 중...");
        String results = callLlmForSection(projectName, sourceData,
                "이 보고서의 '결과' 섹션을 작성하세요.\n" +
                "완료된 작업을 번호 목록으로 정리합니다.\n" +
                "형식:\n" +
                "1. {기능/화면명}: {완료된 내용 요약}\n" +
                "2. {기능/화면명}: {완료된 내용 요약}");
        report.append("### 결과\n\n").append(results).append("\n\n");
        // 6. 개선 및 예방 방안
        log.info("[6/6] 개선 및 예방 방안 섹션 생성 중...");
        String improvement = callLlmForSection(projectName, sourceData,
                "이 보고서의 '개선 및 예방 방안' 섹션을 작성하세요.\n" +
                "향후 점검이 필요한 사항을 구체적으로 기술합니다.\n" +
                "형식:\n" +
                "1. **{제안 제목}**\n" +
                "   {구체적 설명}");
        report.append("### 개선 및 예방 방안\n\n").append(improvement).append("\n");
        log.info("보고서 전체 조립 완료 - {}자", report.length());
        return report.toString();
    }

    /**
     * 단일 섹션에 대해 LLM을 호출합니다.
     *
     * @param projectName 프로젝트명
     * @param sourceData 분석 자료 (시스템 문서 + 카톡 대화)
     * @param sectionInstruction 해당 섹션의 작성 지시
     * @return LLM 응답 (think 태그 제거됨)
     */
    private String callLlmForSection(String projectName, String sourceData, String sectionInstruction) {
        String systemPrompt = COMMON_RULES;
        String userPrompt = "## 프로젝트명: " + projectName + "\n\n" +
                sourceData + "\n---\n\n" +
                sectionInstruction + "\n\n" +
                "해당 섹션의 본문 내용만 출력하세요. 섹션 제목(###)은 출력하지 마세요.";
        try {
            String result = llmClient.chat(systemPrompt, userPrompt);
            String cleaned = cleanThinkingTags(result);
            // ### 제목이 포함되었으면 제거
            cleaned = cleaned.replaceAll("(?m)^###\\s+.*$", "").trim();
            log.info("섹션 LLM 응답: {}자", cleaned.length());
            return cleaned.isEmpty() ? "(내용 없음)" : cleaned;
        } catch (Exception e) {
            log.warn("섹션 LLM 호출 실패: {}", e.getMessage());
            return "(LLM 호출 실패)";
        }
    }

    /**
     * 분석 자료(시스템 문서 + 카톡 대화)를 구성합니다.
     */
    private String buildSourceData(String docsContent, String chatContent) {
        StringBuilder sb = new StringBuilder();
        if (!docsContent.isEmpty()) {
            sb.append("## [분석 자료] 시스템 기능 문서\n\n");
            sb.append(truncateIfNeeded(docsContent, MAX_CONTENT_CHARS));
            sb.append("\n\n");
        }
        if (!chatContent.isEmpty()) {
            sb.append("## [분석 자료] 클라이언트 카카오톡 대화\n\n");
            sb.append(truncateIfNeeded(chatContent, MAX_CONTENT_CHARS));
            sb.append("\n\n");
        }
        return sb.toString();
    }

    /**
     * LLM 응답에서 &lt;think&gt; 태그를 제거합니다.
     */
    private String cleanThinkingTags(String text) {
        if (text == null) return "";
        return text.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    private List<File> readLocalDirectory(String directoryPath) {
        if (directoryPath == null || directoryPath.isBlank()) {
            throw new IllegalArgumentException("시스템 문서 경로가 비어있습니다");
        }
        File dir = new File(directoryPath);
        if (!dir.exists()) {
            throw new IllegalArgumentException("시스템 문서 경로가 존재하지 않습니다: " + directoryPath);
        }
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("시스템 문서 경로가 디렉토리가 아닙니다: " + directoryPath);
        }
        File[] files = dir.listFiles(File::isFile);
        if (files == null || files.length == 0) {
            log.warn("시스템 문서 디렉토리가 비어있습니다: {}", directoryPath);
            return List.of();
        }
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return Arrays.asList(files);
    }

    private String readLocalFile(File file) {
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("로컬 파일 읽기 실패: {} - {}", file.getName(), e.getMessage());
            return "(파일 읽기 실패: " + file.getName() + ")";
        }
    }

    private String readMultipartFile(MultipartFile file) {
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("파일 읽기 실패: {} - {}", file.getOriginalFilename(), e.getMessage());
            return "(파일 읽기 실패: " + file.getOriginalFilename() + ")";
        }
    }

    private String truncateIfNeeded(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        int cutIndex = text.lastIndexOf('\n', maxChars);
        if (cutIndex <= 0) {
            cutIndex = maxChars;
        }
        log.info("콘텐츠 잘라냄: {}자 → {}자", text.length(), cutIndex);
        return text.substring(0, cutIndex) + "\n\n(... 이하 생략)";
    }
}
