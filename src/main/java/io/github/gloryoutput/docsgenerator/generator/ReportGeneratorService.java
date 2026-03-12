package io.github.gloryoutput.docsgenerator.generator;

import io.github.gloryoutput.docsgenerator.correlation.CorrelatedGroup;
import io.github.gloryoutput.docsgenerator.domain.analysis.AnalysisRequest;
import io.github.gloryoutput.docsgenerator.domain.changeevent.ChangeEvent;
import io.github.gloryoutput.docsgenerator.domain.report.Report;
import io.github.gloryoutput.docsgenerator.domain.report.ReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 최종 Markdown 보고서를 생성하고 저장하는 서비스
 *
 * <p>분석 요청 정보, 상관관계 그룹, LLM이 다듬은 초안을 종합하여
 * 원본 보고서 형식(목적, 문제 정의, 해결 과정, 결과, 개선 방안)에 맞는
 * Markdown 보고서를 만듭니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReportGeneratorService {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_KR_FORMATTER = DateTimeFormatter.ofPattern("yyyy년 MM월 dd일");
    private final ReportRepository reportRepository;

    /**
     * 최종 Markdown 보고서를 생성합니다.
     *
     * <p>원본 보고서(고영 오류 수정 완료 보고서) 형식을 따라 다음 구조로 생성합니다:
     * 1) 메타 정보 테이블
     * 2) 6개 섹션 (목적, 발생한 문제, 문제 원인, 문제 해결 과정, 결과, 개선 및 예방 방안)
     * rawDraft(기능별 변경 상세)는 "문제 해결 과정" 섹션 내에 collapse 블록으로 포함됩니다.</p>
     *
     * @param analysisRequest 분석 요청 엔티티
     * @param projectName 프로젝트명
     * @param groups 상관관계 그룹 목록
     * @param polishedDraft LLM이 다듬은 6개 섹션
     * @param rawDraft 기능별 변경 상세 (collapse 블록)
     * @return 완성된 Markdown 보고서 텍스트
     */
    public String generateReport(AnalysisRequest analysisRequest, String projectName,
                                  List<CorrelatedGroup> groups, String polishedDraft, String rawDraft) {
        int totalEvents = groups.stream()
                .mapToInt(g -> g.getEvents() != null ? g.getEvents().size() : 0)
                .sum();
        String startDate = analysisRequest.getStartDate().format(DATE_FORMATTER);
        String endDate = analysisRequest.getEndDate().format(DATE_FORMATTER);
        String createdDate = LocalDateTime.now().format(DATE_KR_FORMATTER);
        // 카테고리별·심각도별 통계
        Map<String, Integer> categoryCount = new LinkedHashMap<>();
        Map<String, Integer> severityCount = new LinkedHashMap<>();
        for (CorrelatedGroup group : groups) {
            if (group.getEvents() == null) continue;
            for (ChangeEvent event : group.getEvents()) {
                categoryCount.merge(event.getCategory(), 1, Integer::sum);
                severityCount.merge(event.getSeverity(), 1, Integer::sum);
            }
        }
        StringBuilder report = new StringBuilder();
        // 보고서 제목
        report.append("# 소프트웨어 변경 보고서\n\n");
        report.append("작성일자: ").append(createdDate).append("\n\n");
        // 메타 정보 테이블
        report.append("| 항목 | 내용 |\n");
        report.append("|------|------|\n");
        report.append("| 프로젝트 | ").append(projectName).append(" |\n");
        report.append("| 분석 기간 | ").append(startDate).append(" ~ ").append(endDate).append(" |\n");
        report.append("| 총 변경 이벤트 | ").append(totalEvents).append("건 |\n");
        if (!categoryCount.isEmpty()) {
            StringBuilder catSummary = new StringBuilder();
            categoryCount.forEach((k, v) -> {
                if (!catSummary.isEmpty()) catSummary.append(", ");
                catSummary.append(formatCategoryName(k)).append(" ").append(v).append("건");
            });
            report.append("| 변경 유형 | ").append(catSummary).append(" |\n");
        }
        if (!severityCount.isEmpty()) {
            StringBuilder sevSummary = new StringBuilder();
            severityCount.forEach((k, v) -> {
                if (!sevSummary.isEmpty()) sevSummary.append(", ");
                sevSummary.append(formatSeverityName(k)).append(" ").append(v).append("건");
            });
            report.append("| 심각도 분포 | ").append(sevSummary).append(" |\n");
        }
        report.append("\n");
        // 6개 섹션 본문 (목적, 발생한 문제, 문제 원인, 문제 해결 과정, 결과, 개선 및 예방 방안)
        // LLM 성공 시 LLM 결과, 실패 시 이벤트 데이터 기반 fallback 생성
        String sections = (polishedDraft != null && !polishedDraft.isBlank())
                ? polishedDraft
                : buildFallbackSections(projectName, startDate, endDate, groups, categoryCount, severityCount);
        // rawDraft(기능별 변경 상세)를 "문제 해결 과정" 섹션 끝에 삽입
        sections = injectRawDraftIntoSections(sections, rawDraft);
        report.append(sections);
        if (!sections.endsWith("\n")) {
            report.append("\n");
        }
        log.info("최종 보고서 생성 완료 (프로젝트: {}, 이벤트: {}건)", projectName, totalEvents);
        return report.toString();
    }

    /**
     * 보고서를 생성하고 DB에 저장합니다.
     *
     * @param analysisRequest 분석 요청 엔티티
     * @param projectName 프로젝트명
     * @param groups 상관관계 그룹 목록
     * @param polishedDraft LLM이 다듬은 6개 섹션
     * @param rawDraft 원본 초안 (프로젝트 코드 변경 상세)
     * @return 저장된 Report 엔티티
     */
    @Transactional
    public Report generateAndSave(AnalysisRequest analysisRequest, String projectName,
                                   List<CorrelatedGroup> groups, String polishedDraft, String rawDraft) {
        String reportContent = generateReport(analysisRequest, projectName, groups, polishedDraft, rawDraft);
        Report report = Report.builder()
                .idAnalysisRequest(analysisRequest.getIdAnalysisRequest())
                .idProject(analysisRequest.getIdProject())
                .reportContent(reportContent)
                .build();
        reportRepository.save(report);
        log.info("보고서 저장 완료 (ID: {})", report.getIdReport());
        return report;
    }

    /**
     * LLM 호출 실패 시 이벤트 데이터로부터 6개 섹션 fallback 콘텐츠를 생성합니다.
     *
     * <p>원본 보고서(고영 오류 수정 완료 보고서) 형식에 맞추어
     * 목적, 발생한 문제, 문제 원인, 문제 해결 과정, 결과, 개선 및 예방 방안
     * 6개 섹션을 ### 제목으로 구분하여 생성합니다.</p>
     */
    private String buildFallbackSections(String projectName, String startDate, String endDate,
                                          List<CorrelatedGroup> groups,
                                          Map<String, Integer> categoryCount,
                                          Map<String, Integer> severityCount) {
        log.info("LLM 미사용 - fallback 6개 섹션 생성 (프로젝트: {})", projectName);
        // 전체 이벤트를 단일 리스트로 수집
        List<ChangeEvent> allEvents = new ArrayList<>();
        List<ChangeEvent> highEvents = new ArrayList<>();
        for (CorrelatedGroup group : groups) {
            if (group.getEvents() == null) continue;
            for (ChangeEvent event : group.getEvents()) {
                allEvents.add(event);
                if ("HIGH".equals(event.getSeverity())) highEvents.add(event);
            }
        }
        StringBuilder sb = new StringBuilder();
        // ### 목적
        sb.append("### 목적\n\n");
        sb.append("본 보고서는 ").append(projectName).append(" 프로젝트에서 ")
                .append(startDate).append(" ~ ").append(endDate)
                .append(" 기간 동안 수행된 소프트웨어 변경 작업에 대한 보고서입니다. ")
                .append("해당 기간에 총 ").append(allEvents.size()).append("건의 변경이 이루어졌으며, ");
        // 카테고리별 요약을 목적에 포함
        if (!categoryCount.isEmpty()) {
            List<String> catDescriptions = new ArrayList<>();
            categoryCount.forEach((k, v) -> catDescriptions.add(formatCategoryName(k) + " 변경 " + v + "건"));
            sb.append(String.join(", ", catDescriptions)).append("이 포함되어 있습니다. ");
        }
        sb.append("각 변경의 배경, 진행 과정, 결과 및 후속 조치 사항을 정리하였습니다.\n\n");
        // ### 발생한 문제
        sb.append("### 발생한 문제\n\n");
        sb.append("아래 항목들에 대해 신규 개발 또는 기존 기능 개선이 필요하였습니다.\n\n");
        for (ChangeEvent event : allEvents) {
            sb.append("- **").append(event.getTitle()).append("**: ");
            String problemLine = extractFirstMeaningfulLine(event.getDescription());
            if (problemLine != null) {
                sb.append(problemLine);
            } else {
                sb.append("해당 기능에 대한 신규 구현 또는 기존 동작 방식의 개선이 요구되었습니다");
            }
            sb.append("\n");
        }
        sb.append("\n");
        // ### 문제 원인
        sb.append("### 문제 원인\n\n");
        sb.append("위 변경 사항들은 다음과 같은 업무상의 요구사항 및 기술적 필요에 의해 진행되었습니다.\n\n");
        for (ChangeEvent event : allEvents) {
            sb.append("- **").append(event.getTitle()).append("**\n");
            List<String> causeLines = extractMeaningfulLines(
                    event.getDescription() != null ? event.getDescription() : "", 3);
            if (!causeLines.isEmpty()) {
                for (String line : causeLines) {
                    sb.append("  - ").append(line).append("\n");
                }
            } else {
                sb.append("  - 해당 기능이 기존 시스템에 존재하지 않거나, 현재 구현이 업무 요건에 부합하지 않아 변경이 필요하였습니다.\n");
            }
        }
        sb.append("\n");
        // ### 문제 해결 과정
        sb.append("### 문제 해결 과정\n\n");
        sb.append("위 문제를 해결하기 위해 다음과 같은 단계로 작업을 수행하였습니다.\n\n");
        int stepIndex = 1;
        for (CorrelatedGroup group : groups) {
            if (group.getEvents() == null || group.getEvents().isEmpty()) continue;
            sb.append(stepIndex++).append(". **").append(group.getTitle()).append("**\n");
            for (ChangeEvent event : group.getEvents()) {
                List<String> lines = extractMeaningfulLines(
                        event.getDescription() != null ? event.getDescription() : "", 3);
                if (!lines.isEmpty()) {
                    for (String line : lines) {
                        sb.append("   - ").append(line).append("\n");
                    }
                } else {
                    sb.append("   - ").append(event.getTitle()).append(" 작업을 수행하였습니다.\n");
                }
            }
        }
        sb.append("\n");
        // ### 결과
        sb.append("### 결과\n\n");
        sb.append("상기 작업을 통해 다음과 같은 변경이 완료되었습니다.\n\n");
        for (ChangeEvent event : allEvents) {
            sb.append("- **").append(event.getTitle()).append("** — ");
            String resultDetail = extractFirstMeaningfulLine(event.getDescription());
            if (resultDetail != null) {
                sb.append(resultDetail).append(" (완료)");
            } else {
                sb.append("정상적으로 반영 완료");
            }
            sb.append("\n");
        }
        sb.append("\n");
        // ### 개선 및 예방 방안
        sb.append("### 개선 및 예방 방안\n\n");
        sb.append("이번 변경과 관련하여 다음 사항에 대한 후속 점검이 필요합니다.\n\n");
        boolean hasFollowUp = false;
        for (ChangeEvent event : highEvents) {
            sb.append("- **").append(event.getTitle()).append("** — 심각도가 높은 변경으로, 배포 후 해당 기능의 정상 동작 여부를 반드시 확인하여야 합니다.\n");
            hasFollowUp = true;
        }
        for (ChangeEvent event : allEvents) {
            if ("HIGH".equals(event.getSeverity())) continue;
            if ("SCHEMA_CHANGE".equals(event.getCategory())) {
                sb.append("- **").append(event.getTitle()).append("** — 데이터베이스 스키마가 변경되었으므로, 기존 데이터의 정합성 및 관련 쿼리·인덱스의 정상 동작을 확인하여야 합니다.\n");
                hasFollowUp = true;
            } else if (containsAny(event.getTitle(), "삭제", "제거")) {
                sb.append("- **").append(event.getTitle()).append("** — 기존 기능이 삭제 또는 제거되었으므로, 해당 기능을 사용하던 외부 시스템 및 화면의 영향 범위를 확인하여야 합니다.\n");
                hasFollowUp = true;
            } else if ("API_CHANGE".equals(event.getCategory())) {
                sb.append("- **").append(event.getTitle()).append("** — API가 변경되었으므로, 해당 API를 호출하는 클라이언트의 호환성을 확인하여야 합니다.\n");
                hasFollowUp = true;
            }
        }
        if (!hasFollowUp) {
            sb.append("- 이번 변경은 기존 기능에 대한 영향이 제한적이므로, 별도의 후속 조치가 필요하지 않습니다.\n");
        }
        return sb.toString();
    }
    private boolean containsAny(String text, String... keywords) {
        if (text == null) return false;
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
    /**
     * description의 첫 번째 의미 있는 줄을 반환합니다.
     */
    private String extractFirstMeaningfulLine(String description) {
        if (description == null || description.isBlank()) return null;
        for (String line : description.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.equals("기능별 변경 내용:")) continue;
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) continue;
            if (trimmed.startsWith("- ")) trimmed = trimmed.substring(2).trim();
            if (trimmed.length() >= 5) return trimmed;
        }
        return null;
    }
    /**
     * description에서 의미 있는 줄만 추출합니다.
     *
     * <p>빈 줄, 메타 정보(대괄호 헤더), 너무 짧은 줄을 건너뛰고
     * 실질적 내용이 담긴 줄을 최대 maxLines개까지 반환합니다.</p>
     */
    private List<String> extractMeaningfulLines(String description, int maxLines) {
        List<String> result = new ArrayList<>();
        for (String line : description.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // [카테고리명] 형태의 헤더 라인은 건너뜀
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) continue;
            // "기능별 변경 내용:" 같은 메타 라인은 건너뜀
            if (trimmed.equals("기능별 변경 내용:")) continue;
            // "- " 접두사가 있으면 제거
            if (trimmed.startsWith("- ")) trimmed = trimmed.substring(2).trim();
            if (trimmed.length() < 3) continue;
            result.add(trimmed);
            if (result.size() >= maxLines) break;
        }
        return result;
    }
    /**
     * rawDraft(기능별 변경 상세 collapse 블록)를 "문제 해결 과정" 섹션 끝에 삽입합니다.
     *
     * <p>"### 결과" 섹션 시작 직전에 rawDraft를 삽입하여,
     * "문제 해결 과정" 섹션의 마지막 부분에 기능별 상세가 포함되도록 합니다.</p>
     */
    private String injectRawDraftIntoSections(String sections, String rawDraft) {
        if (rawDraft == null || rawDraft.isBlank()) {
            return sections;
        }
        // "### 결과" 앞에 rawDraft 삽입 → "문제 해결 과정" 섹션 내에 위치
        int resultIndex = sections.indexOf("### 결과");
        if (resultIndex > 0) {
            String before = sections.substring(0, resultIndex);
            String after = sections.substring(resultIndex);
            // "문제 해결 과정" 본문 뒤에 기능별 상세 추가
            if (!before.endsWith("\n\n")) {
                before = before.stripTrailing() + "\n\n";
            }
            return before + rawDraft.stripTrailing() + "\n\n" + after;
        }
        // "### 결과"를 찾지 못하면 섹션 끝에 추가
        return sections.stripTrailing() + "\n\n" + rawDraft;
    }

    /**
     * 이벤트 목록의 제목을 한 줄로 이어 붙입니다.
     */
    private void appendEventTitles(StringBuilder sb, List<ChangeEvent> events) {
        if (events.size() == 1) {
            sb.append(events.get(0).getTitle()).append("\n");
        } else {
            sb.append(events.size()).append("건의 변경이 필요하였습니다.\n");
            for (ChangeEvent event : events) {
                sb.append("  - ").append(event.getTitle()).append("\n");
            }
        }
    }

    /**
     * 카테고리 코드를 한국어 표시명으로 변환합니다.
     */
    private String formatCategoryName(String category) {
        return switch (category) {
            case "SCHEMA_CHANGE" -> "DB 스키마";
            case "API_CHANGE" -> "API";
            case "CODE_CHANGE" -> "코드";
            case "DEPENDENCY_CHANGE" -> "의존성";
            default -> category;
        };
    }

    /**
     * 심각도 코드를 한국어 표시명으로 변환합니다.
     */
    private String formatSeverityName(String severity) {
        return switch (severity) {
            case "HIGH" -> "높음(HIGH)";
            case "MEDIUM" -> "보통(MEDIUM)";
            case "LOW" -> "낮음(LOW)";
            default -> severity;
        };
    }
}
