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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 클라이언트 보고서 생성 서비스
 *
 * <p>로컬 디렉토리의 시스템 문서와 카카오톡 대화 내용을 파싱하여 LLM을 통해
 * 보고서를 생성합니다. LLM이 없을 경우 원본 자료를 정리하여 반환합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@Slf4j
public class ClientReportService {
    private static final String SYSTEM_PROMPT =
            "당신은 IT 프로젝트 변경 관리 보고서를 작성하는 시니어 테크니컬 라이터입니다.\n" +
            "아래 제공된 시스템 문서(기능 명세)와 클라이언트 카카오톡 대화를 **분석 자료**로만 활용하여\n" +
            "공식 업무 보고서를 작성하세요.\n\n" +
            "## 핵심 원칙\n" +
            "- 카카오톡 대화 원문이나 시스템 문서 내용을 보고서에 **그대로 복사하지 마세요.**\n" +
            "- 대화와 문서에서 **기능 수정/개발 요청, 버그 제보, 변경 완료 내역**을 추출하여 정리하세요.\n" +
            "- 공식 업무 보고서에 적합한 격식체 한국어를 사용합니다 (~했습니다, ~되었습니다).\n" +
            "- 비개발자(경영진, 고객사 담당자)가 읽는 보고서입니다. 코드명, 클래스명, 파일 경로를 사용하지 마세요.\n" +
            "- 원본에 없는 사실이나 추측을 추가하지 마세요.\n\n" +
            "## 출력 구조 (반드시 아래 섹션을 ### 제목으로 구분하여 순서대로 출력하세요)\n\n" +
            "### 목적\n" +
            "- 보고서 작성 배경과 보고 기간을 2~3문장으로 서술합니다.\n" +
            "- 전체 요청 건수와 주요 변경 유형(기능수정/신규개발/오류수정)별 건수를 포함합니다.\n\n" +
            "### 기능 수정 요청 및 처리 내역\n" +
            "- 클라이언트가 요청한 **기존 기능의 수정/변경/개선** 건을 기능 단위로 정리합니다.\n" +
            "- 각 항목마다 다음을 포함하세요:\n" +
            "  - 어떤 기능/화면에 대한 수정 요청이었는지\n" +
            "  - 클라이언트가 요청한 변경 내용 (무엇을 바꿔달라고 했는지)\n" +
            "  - 어떻게 수정되었는지 (처리 결과)\n" +
            "- 형식: '- **{기능/화면명}**: {요청 내용} → {처리 결과}'\n\n" +
            "### 신규 개발 요청 및 처리 내역\n" +
            "- 클라이언트가 요청한 **새로운 기능/화면 추가** 건을 정리합니다.\n" +
            "- 각 항목마다 다음을 포함하세요:\n" +
            "  - 어떤 기능/화면을 새로 만들어달라고 요청했는지\n" +
            "  - 개발된 기능의 주요 내용\n" +
            "- 형식: '- **{기능/화면명}**: {요청 내용} → {개발 결과}'\n" +
            "- 해당 건이 없으면 '해당 기간 내 신규 개발 요청 건은 없습니다.'로 표기\n\n" +
            "### 오류 수정 내역\n" +
            "- 클라이언트가 제보하거나 운영 중 발견된 **버그/오류/장애** 건을 정리합니다.\n" +
            "- 각 항목마다 다음을 포함하세요:\n" +
            "  - 어떤 화면/기능에서 어떤 오류가 발생했는지 (증상)\n" +
            "  - 오류의 원인\n" +
            "  - 어떻게 수정되었는지\n" +
            "- 형식: '- **{기능/화면명}**: {오류 증상} / 원인: {원인} → {수정 내용}'\n" +
            "- 해당 건이 없으면 '해당 기간 내 오류 수정 건은 없습니다.'로 표기\n\n" +
            "### 결과 요약\n" +
            "- '상기 작업을 통해 다음과 같은 변경이 완료되었습니다.'로 시작하세요.\n" +
            "- 전체 처리 건수와 유형별 건수를 요약합니다.\n" +
            "- 주요 변경 사항 3~5건을 한 줄씩 요약합니다.\n\n" +
            "## 금지 사항\n" +
            "- 카카오톡 대화를 인용하거나 발화자 이름/닉네임을 기재하지 마세요.\n" +
            "- 시스템 문서 내용을 그대로 옮기지 마세요.\n" +
            "- 코드, 클래스명, 파일 경로, 테이블명 등 기술 용어를 사용하지 마세요.\n" +
            "- 각 섹션 제목 앞에 번호를 붙이지 마세요.\n\n" +
            "결과물은 Markdown 본문만 출력하세요. 부가 설명이나 인사말은 포함하지 마세요.";
    private static final int MAX_CONTENT_CHARS = 12000;
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
        // 보고서 생성
        String reportContent = generateReport(project.getProjectName(),
                docsContent.toString(), chatContent);
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
     *
     * @param idProject 프로젝트 ID
     * @return 보고서 응답 목록
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
     *
     * @param idClientReport 보고서 ID
     * @return 보고서 응답
     */
    public ClientReportResponse getById(String idClientReport) {
        UUID reportId = UUID.fromString(idClientReport);
        ClientReport entity = clientReportRepository.findByIdClientReportAndIsDeletedFalse(reportId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "클라이언트 보고서를 찾을 수 없습니다: " + idClientReport));
        return ClientReportResponse.from(entity);
    }

    /**
     * 로컬 디렉토리 내 모든 파일을 읽어 목록으로 반환합니다.
     *
     * <p>디렉토리가 존재하지 않거나 비어있으면 빈 목록을 반환합니다.
     * 하위 디렉토리는 제외하고 파일만 수집합니다.</p>
     *
     * @param directoryPath 디렉토리 경로
     * @return 파일 목록
     */
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

    /**
     * 로컬 파일의 내용을 UTF-8 문자열로 읽습니다.
     *
     * @param file 읽을 파일
     * @return 파일 내용
     */
    private String readLocalFile(File file) {
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("로컬 파일 읽기 실패: {} - {}", file.getName(), e.getMessage());
            return "(파일 읽기 실패: " + file.getName() + ")";
        }
    }

    /**
     * LLM을 활용하여 보고서를 생성합니다.
     *
     * <p>LLM이 비활성화된 경우(app.llm.enabled=false) 예외를 발생시킵니다.</p>
     */
    private String generateReport(String projectName, String docsContent, String chatContent) {
        if (llmClient == null) {
            throw new IllegalStateException(
                    "LLM이 비활성화되어 보고서를 생성할 수 없습니다. " +
                    "app.llm.enabled=true 설정이 필요합니다.");
        }
        String userPrompt = buildUserPrompt(projectName, docsContent, chatContent);
        log.info("LLM 보고서 생성 요청 - project: {}", projectName);
        log.info("LLM User Prompt 길이: {}자", userPrompt.length());
        String result = llmClient.chat(SYSTEM_PROMPT, userPrompt);
        log.info("LLM 원본 응답 길이: {}자", result != null ? result.length() : 0);
        log.info("LLM 원본 응답:\n{}", result);
        String cleaned = cleanThinkingTags(result);
        log.info("think 태그 제거 후 길이: {}자", cleaned.length());
        if (cleaned.isEmpty()) {
            log.warn("LLM 응답에서 think 태그 제거 후 내용이 비어있습니다");
        }
        return cleaned;
    }

    /**
     * LLM 응답에서 &lt;think&gt; 태그를 제거합니다.
     *
     * <p>deepseek-r1 등 일부 모델은 추론 과정을 &lt;think&gt;...&lt;/think&gt; 태그로
     * 감싸서 출력하므로, 최종 보고서에서 해당 태그와 내용을 제거합니다.</p>
     */
    private String cleanThinkingTags(String text) {
        if (text == null) return "";
        return text.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    /**
     * LLM에 전달할 사용자 프롬프트를 구성합니다.
     */
    private String buildUserPrompt(String projectName, String docsContent, String chatContent) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 프로젝트명: ").append(projectName).append("\n\n");
        if (!docsContent.isEmpty()) {
            sb.append("## [분석 자료] 시스템 기능 문서\n");
            sb.append("아래는 현재 시스템의 기능 명세입니다. 기능 수정/개발 내역을 파악하는 참고 자료로만 사용하세요.\n\n");
            sb.append(truncateIfNeeded(docsContent, MAX_CONTENT_CHARS));
            sb.append("\n\n");
        }
        if (!chatContent.isEmpty()) {
            sb.append("## [분석 자료] 클라이언트 카카오톡 대화\n");
            sb.append("아래 대화에서 기능 수정 요청, 신규 개발 요청, 버그 제보, 처리 결과를 추출하세요.\n");
            sb.append("대화 원문을 보고서에 포함하지 마세요.\n\n");
            sb.append(truncateIfNeeded(chatContent, MAX_CONTENT_CHARS));
            sb.append("\n\n");
        }
        sb.append("---\n\n");
        sb.append("위 자료를 분석하여 시스템 프롬프트의 '출력 구조'에 따라 보고서를 작성하세요.\n");
        sb.append("대화와 문서 원문을 그대로 옮기지 말고, 기능 수정/개발 요청 및 처리 내역, 버그 수정 내역을 추출·정리하세요.\n");
        sb.append("결과물은 Markdown 본문만 출력하세요.");
        return sb.toString();
    }

    /**
     * MultipartFile의 내용을 문자열로 읽습니다.
     */
    private String readMultipartFile(MultipartFile file) {
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("파일 읽기 실패: {} - {}", file.getOriginalFilename(), e.getMessage());
            return "(파일 읽기 실패: " + file.getOriginalFilename() + ")";
        }
    }

    /**
     * 텍스트가 최대 문자 수를 초과하면 줄 단위로 잘라냅니다.
     */
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
