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
     * <p>이벤트를 문제(오류/버그 수정)와 변경(신규 개발/기능 개선)으로 분류하여
     * 각 섹션의 성격에 맞는 내용을 배치합니다.
     * - 목적/결과: 문제 수정과 기능 변경을 모두 포함
     * - 발생한 문제/문제 원인: 실제 문제(오류/버그)만 기술
     * - 문제 해결 과정: 문제 수정과 기능 개발 과정을 구분하여 서술</p>
     */
    private String buildFallbackSections(String projectName, String startDate, String endDate,
                                          List<CorrelatedGroup> groups,
                                          Map<String, Integer> categoryCount,
                                          Map<String, Integer> severityCount) {
        log.info("LLM 미사용 - fallback 6개 섹션 생성 (프로젝트: {})", projectName);
        // 전체 이벤트 수집 및 분류 (문제 vs 변경)
        List<ChangeEvent> allEvents = new ArrayList<>();
        List<ChangeEvent> highEvents = new ArrayList<>();
        List<ChangeEvent> problemEvents = new ArrayList<>();
        List<ChangeEvent> featureEvents = new ArrayList<>();
        for (CorrelatedGroup group : groups) {
            if (group.getEvents() == null) continue;
            for (ChangeEvent event : group.getEvents()) {
                allEvents.add(event);
                if ("HIGH".equals(event.getSeverity())) highEvents.add(event);
                if (isProblemEvent(event)) {
                    problemEvents.add(event);
                } else {
                    featureEvents.add(event);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        // ### 목적: 전체 변경 개요 (문제 수정 + 기능 변경 모두 포함)
        sb.append("### 목적\n\n");
        sb.append("본 보고서는 ").append(projectName).append(" 프로젝트에서 ")
                .append(startDate).append(" ~ ").append(endDate)
                .append(" 기간 동안 수행된 소프트웨어 변경 작업을 정리한 보고서입니다.\n\n");
        // 유형별 건수 요약
        if (!problemEvents.isEmpty() && !featureEvents.isEmpty()) {
            sb.append("해당 기간에 총 ").append(allEvents.size()).append("건의 변경이 이루어졌으며, ")
                    .append("오류 수정 ").append(problemEvents.size()).append("건과 ")
                    .append("신규 개발 및 기능 개선 ").append(featureEvents.size()).append("건이 포함되어 있습니다.\n\n");
        } else if (!problemEvents.isEmpty()) {
            sb.append("해당 기간에 총 ").append(problemEvents.size()).append("건의 오류 수정이 진행되었습니다.\n\n");
        } else {
            sb.append("해당 기간에 총 ").append(featureEvents.size())
                    .append("건의 신규 개발 및 기능 개선이 진행되었습니다.\n\n");
        }
        sb.append("주요 변경 내용은 다음과 같습니다.\n\n");
        appendGroupedEventItems(sb, allEvents, "");
        sb.append("\n");
        // ### 발생한 문제: 실제 오류/버그만 기술, "어떤 기능에서 어떤 문제" 형식
        sb.append("### 발생한 문제\n\n");
        if (problemEvents.isEmpty()) {
            sb.append("해당 기간에 보고된 시스템 오류 또는 장애는 없으며, ")
                    .append("신규 기능 개발 및 기존 기능 개선 위주로 작업이 진행되었습니다.\n\n");
        } else {
            sb.append("해당 기간에 다음과 같은 문제가 확인되어 수정이 필요하였습니다.\n\n");
            int probIdx = 1;
            for (ChangeEvent event : problemEvents) {
                List<String> details = extractMeaningfulLines(event.getDescription(), 10);
                sb.append(probIdx++).append(". **").append(event.getTitle()).append("**\n");
                if (details.isEmpty()) {
                    sb.append("   - ").append(buildProblemStatement(event)).append("\n");
                } else {
                    for (String detail : details) {
                        sb.append("   - ").append(detail).append("\n");
                    }
                }
            }
            sb.append("\n");
        }
        // ### 문제 원인: 실제 문제의 원인만, 기능 맥락 포함
        sb.append("### 문제 원인\n\n");
        if (problemEvents.isEmpty()) {
            sb.append("해당 기간에 오류 수정 사항이 없으므로 본 항목은 해당되지 않습니다.\n\n");
        } else {
            sb.append("위 문제들의 원인은 다음과 같이 분석되었습니다.\n\n");
            for (ChangeEvent event : problemEvents) {
                List<String> causeLines = extractMeaningfulLines(
                        event.getDescription() != null ? event.getDescription() : "", 10);
                if (!causeLines.isEmpty()) {
                    for (String line : causeLines) {
                        sb.append("- ").append(line).append("\n");
                    }
                } else {
                    String title = event.getTitle() != null ? event.getTitle() : "";
                    String feature = extractFeatureName(title);
                    if (containsAny(title, "조회", "표시", "출력")) {
                        sb.append("- ").append(feature).append(" 조회 처리 과정에서 오류 발생\n");
                    } else if (containsAny(title, "저장", "등록", "입력")) {
                        sb.append("- ").append(feature).append(" 저장 처리 과정에서 오류 발생\n");
                    } else if (containsAny(title, "삭제", "제거")) {
                        sb.append("- ").append(feature).append(" 삭제 처리 과정에서 오류 발생\n");
                    } else if (containsAny(title, "연동", "동기화")) {
                        sb.append("- ").append(feature).append(" 데이터 연동 과정에서 오류 발생\n");
                    } else if (containsAny(title, "계산", "산출", "집계")) {
                        sb.append("- ").append(feature).append(" 계산 과정에서 오류 발생\n");
                    } else {
                        sb.append("- ").append(feature).append(" 처리 과정에서 오류 발생\n");
                    }
                }
            }
            sb.append("\n");
        }
        // ### 문제 해결 과정: 문제 수정과 기능 개발을 구분하여 서술
        sb.append("### 문제 해결 과정\n\n");
        if (!problemEvents.isEmpty()) {
            sb.append("#### 오류 수정\n\n");
            appendGroupedEventItems(sb, problemEvents, "");
            sb.append("\n");
        }
        if (!featureEvents.isEmpty()) {
            if (!problemEvents.isEmpty()) {
                sb.append("#### 기능 개발 및 개선\n\n");
            }
            Map<String, List<ChangeEvent>> eventsByPurpose = groupEventsByPurpose(featureEvents);
            int stepIndex = 1;
            for (Map.Entry<String, List<ChangeEvent>> purposeEntry : eventsByPurpose.entrySet()) {
                String purpose = purposeEntry.getKey();
                List<ChangeEvent> events = purposeEntry.getValue();
                sb.append(stepIndex++).append(". **").append(purpose).append("**\n");
                appendGroupedEventItems(sb, events, "   ");
            }
            sb.append("\n");
        }
        // ### 결과: 문제 수정과 기능 변경을 구분하여 완료 상태 기술
        sb.append("### 결과\n\n");
        sb.append("상기 작업을 통해 다음과 같은 변경이 완료되었습니다.\n\n");
        appendGroupedEventItems(sb, allEvents, "");
        sb.append("\n");
        // ### 개선 및 예방 방안
        sb.append("### 개선 및 예방 방안\n\n");
        sb.append("이번 변경과 관련하여 다음 사항에 대한 후속 점검이 필요합니다.\n\n");
        boolean hasFollowUp = false;
        for (ChangeEvent event : highEvents) {
            sb.append("- **").append(event.getTitle()).append("** — 중요도가 높은 변경으로, 배포 후 해당 기능의 정상 동작 여부를 반드시 확인하여야 합니다.\n");
            hasFollowUp = true;
        }
        for (ChangeEvent event : problemEvents) {
            if ("HIGH".equals(event.getSeverity())) continue;
            sb.append("- **").append(event.getTitle()).append("** — 동일 문제가 재발하지 않도록 관련 기능의 동작을 지속적으로 점검하여야 합니다.\n");
            hasFollowUp = true;
        }
        for (ChangeEvent event : featureEvents) {
            if ("HIGH".equals(event.getSeverity())) continue;
            if ("SCHEMA_CHANGE".equals(event.getCategory())) {
                sb.append("- **").append(event.getTitle()).append("** — 데이터 관리 구조가 변경되었으므로, 기존 데이터와의 정합성을 확인하여야 합니다.\n");
                hasFollowUp = true;
            } else if (containsAny(event.getTitle(), "삭제", "제거")) {
                sb.append("- **").append(event.getTitle()).append("** — 기존 기능이 삭제되었으므로, 해당 기능을 사용하던 화면 및 관련 시스템의 영향 범위를 확인하여야 합니다.\n");
                hasFollowUp = true;
            } else if ("API_CHANGE".equals(event.getCategory())) {
                sb.append("- **").append(event.getTitle()).append("** — 화면 또는 외부 시스템에서 사용하는 기능이 변경되었으므로, 연관된 화면의 정상 동작을 확인하여야 합니다.\n");
                hasFollowUp = true;
            }
        }
        if (!hasFollowUp) {
            sb.append("- 이번 변경은 기존 기능에 대한 영향이 제한적이므로, 별도의 후속 조치가 필요하지 않습니다.\n");
        }
        return sb.toString();
    }
    /**
     * 이벤트가 문제(오류/버그 수정)인지 판별합니다.
     *
     * <p>이벤트의 title과 description에서 오류/버그 관련 키워드를 탐지합니다.
     * 신규 개발이나 기능 개선과 구분하여 보고서의 "발생한 문제" 섹션에
     * 실제 문제만 기술하기 위해 사용합니다.</p>
     */
    private boolean isProblemEvent(ChangeEvent event) {
        String title = event.getTitle() != null ? event.getTitle() : "";
        String desc = event.getDescription() != null ? event.getDescription() : "";
        String combined = title + " " + desc;
        return containsAny(combined,
                "오류", "에러", "버그", "장애", "결함", "누락", "잘못",
                "실패", "충돌", "복구", "수정", "fix", "bug", "error");
    }
    /**
     * 이벤트 제목에서 기능명을 추출합니다.
     *
     * <p>오류/수정 관련 키워드를 제거하고 기능 대상만 남겨서
     * "어떤 기능에서 문제가 발생했는지" 서술할 수 있도록 합니다.
     * 예: "스카우트 일정 날씨 조회 오류 수정" → "스카우트 일정 날씨 조회"</p>
     */
    private String extractFeatureName(String title) {
        if (title == null || title.isBlank()) return "해당";
        String cleaned = title
                .replaceAll("(오류|에러|버그|장애|결함|수정|누락|잘못|실패|충돌|복구|fix|bug|error)", "")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.isEmpty() ? "해당" : cleaned;
    }
    /**
     * 문제 이벤트에 대해 간결한 문제 현상을 서술합니다.
     *
     * <p>description이 있으면 실제 분석된 내용을 사용하고,
     * 없으면 제목에서 기능명과 문제 유형을 직접 도출하여 간결하게 기술합니다.
     * 예: "스카우트 일정 날씨 조회 오류 수정" → "스카우트 일정 날씨 조회 시 오류 발생"</p>
     */
    private String buildProblemStatement(ChangeEvent event) {
        String descLine = extractFirstMeaningfulLine(event.getDescription());
        if (descLine != null) return descLine;
        // description이 없을 때: 제목에서 직접 문제 내용 도출 (간결하게)
        String title = event.getTitle() != null ? event.getTitle() : "";
        String feature = extractFeatureName(title);
        // 제목에서 문제 유형 키워드를 감지하여 간결한 현상 기술
        if (containsAny(title, "오류", "에러", "error")) return feature + " 시 오류 발생";
        if (containsAny(title, "누락")) return feature + " 데이터 누락";
        if (containsAny(title, "실패")) return feature + " 처리 실패";
        if (containsAny(title, "충돌")) return feature + " 시 충돌 발생";
        if (containsAny(title, "장애")) return feature + " 장애 발생";
        return feature + " 오류 발생";
    }
    /**
     * 이벤트를 사용자 관점의 의도 카테고리별로 그룹핑합니다.
     *
     * <p>이벤트의 category와 title을 분석하여 사용자 의도 단위(관리 정보 체계 변경,
     * 신규 업무 기능 제공, 기존 업무 편의 개선, 외부 시스템 연계 강화 등)로 분류합니다.</p>
     */
    private Map<String, List<ChangeEvent>> groupEventsByPurpose(List<ChangeEvent> events) {
        Map<String, List<ChangeEvent>> purposeGroups = new LinkedHashMap<>();
        for (ChangeEvent event : events) {
            String purpose = classifyEventPurpose(event);
            purposeGroups.computeIfAbsent(purpose, k -> new ArrayList<>()).add(event);
        }
        return purposeGroups;
    }
    /**
     * 단일 이벤트를 사용자 관점의 의도 카테고리로 분류합니다.
     *
     * <p>기술적 변경 유형 대신, 사용자가 체감할 수 있는 변화의 성격으로 분류합니다.</p>
     */
    private String classifyEventPurpose(ChangeEvent event) {
        String category = event.getCategory();
        String title = event.getTitle() != null ? event.getTitle() : "";
        if ("SCHEMA_CHANGE".equals(category)) {
            return "관리 정보 체계 변경";
        }
        if ("DEPENDENCY_CHANGE".equals(category)) {
            return "시스템 안정화 및 유지보수";
        }
        if ("API_CHANGE".equals(category)) {
            if (containsAny(title, "추가", "신규")) return "신규 업무 기능 제공";
            if (containsAny(title, "수정", "변경")) return "기존 업무 편의 개선";
            if (containsAny(title, "삭제", "제거")) return "불필요한 기능 정리";
            return "신규 업무 기능 제공";
        }
        if (containsAny(title, "연동", "통합")) return "외부 시스템 연계 강화";
        if (containsAny(title, "개선", "수정", "리팩토링", "변경")) return "기존 업무 편의 개선";
        if (containsAny(title, "초기화", "동기화", "변환", "검증", "배치", "마이그레이션", "캐시", "인덱스", "로깅")) {
            return "시스템 안정화 및 유지보수";
        }
        return "신규 업무 기능 제공";
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
            // 통계 헤더 줄 건너뜀 (예: "[repo] 커밋 692건, 변경 파일 2081개", "관련 기능: scout, ...")
            if (trimmed.matches(".*커밋\\s*\\d+건.*변경\\s*파일\\s*\\d+개.*")) continue;
            if (trimmed.startsWith("관련 기능:")) continue;
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
            // 통계 헤더 줄 건너뜀 (예: "[repo] 커밋 692건, 변경 파일 2081개", "관련 기능: scout, ...")
            if (trimmed.matches(".*커밋\\s*\\d+건.*변경\\s*파일\\s*\\d+개.*")) continue;
            if (trimmed.startsWith("관련 기능:")) continue;
            // "- " 접두사가 있으면 제거
            if (trimmed.startsWith("- ")) trimmed = trimmed.substring(2).trim();
            if (trimmed.length() < 3) continue;
            result.add(trimmed);
            if (result.size() >= maxLines) break;
        }
        return result;
    }
    /**
     * 이벤트 목록에서 항목을 추출하고 유사 항목을 그룹핑하여
     * "번호. 그룹제목 + 하위 항목" 형식으로 출력합니다.
     *
     * <p>출력 형식 예시:
     * 1. **player 테이블 컬럼 추가**
     *    - passport_number, social_security_number
     * 2. **스카우트 관련**
     *    - 후보 처리 기능 추가
     *    - 관찰 태그 조회 기능 신규 개발</p>
     */
    private void appendGroupedEventItems(StringBuilder sb, List<ChangeEvent> events, String indent) {
        List<String> items = new ArrayList<>();
        for (ChangeEvent event : events) {
            List<String> details = extractMeaningfulLines(event.getDescription(), 10);
            if (details.isEmpty()) {
                items.add(event.getTitle());
            } else {
                items.addAll(details);
            }
        }
        if (items.isEmpty()) return;
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String item : items) {
            String[] kd = extractMergeKeyAndDetail(item);
            groups.computeIfAbsent(kd[0], k -> new ArrayList<>());
            if (!kd[1].isBlank()) {
                groups.get(kd[0]).add(kd[1]);
            }
        }
        int idx = 1;
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            String groupTitle = entry.getKey();
            List<String> subItems = entry.getValue().stream().distinct().toList();
            sb.append(indent).append(idx++).append(". **").append(groupTitle).append("**\n");
            if (!subItems.isEmpty()) {
                // 짧은 단일 키워드 항목(컬럼명 등)은 한 줄로 합침
                if (subItems.stream().allMatch(s -> !s.contains(" ") && s.length() <= 40)) {
                    sb.append(indent).append("   - ").append(String.join(", ", subItems)).append("\n");
                } else {
                    for (String sub : subItems) {
                        sb.append(indent).append("   - ").append(sub).append("\n");
                    }
                }
            }
        }
    }
    /**
     * 항목 문자열에서 병합 키와 상세 정보를 분리합니다.
     *
     * <p>"컬럼 추가: player.passport_number" → ["player 컬럼 추가", "passport_number"]
     * "스카우트 후보 처리 기능 추가" → ["스카우트 관련", "후보 처리 기능 추가"]</p>
     *
     * @return [0]: 병합 키, [1]: 상세 정보
     */
    private String[] extractMergeKeyAndDetail(String item) {
        if (item == null || item.isBlank()) return new String[]{"기타", ""};
        // "컬럼 추가: player.x" → key: "player 컬럼 추가", detail: "x"
        int colonIdx = item.indexOf(": ");
        if (colonIdx > 0) {
            String prefix = item.substring(0, colonIdx).trim();
            String value = item.substring(colonIdx + 2).trim();
            // "컬럼/인덱스" + "table.column" → 테이블 단위 그룹핑
            if (value.contains(".") && containsAny(prefix, "컬럼", "인덱스")) {
                int dotIdx = value.indexOf('.');
                String tableName = value.substring(0, dotIdx);
                String columnName = value.substring(dotIdx + 1);
                return new String[]{tableName + " " + prefix, columnName};
            }
            return new String[]{prefix, value};
        }
        // 자유 텍스트: 첫 번째 한글 단어를 키로 사용
        String[] words = item.split("\\s+", 2);
        if (words.length >= 2 && words[0].matches(".*[가-힣].*")) {
            return new String[]{words[0] + " 관련", words[1]};
        }
        return new String[]{item, ""};
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
