package io.github.gloryoutput.docsgenerator.generator;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.impl.xb.xmlschema.SpaceAttribute;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.stereotype.Service;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 보고서를 원본 보고서 형식의 표 기반 .docx 파일로 변환하는 서비스
 *
 * <p>Markdown 보고서에서 메타 정보와 6개 섹션(목적, 발생한 문제, 문제 원인,
 * 문제 해결 과정, 결과, 개선 및 예방 방안)을 파싱하여,
 * 원본 보고서(고영 오류 수정 완료 보고서) 형식의 단일 표 DOCX를 생성합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@Slf4j
public class DocxConverterService {
    private static final String FONT_NAME = "맑은 고딕";
    private static final int TITLE_FONT_SIZE_PT = 24;
    private static final int HEADER_FONT_SIZE_PT = 12;
    private static final int CONTENT_FONT_SIZE_PT = 10;
    private static final String HEADER_BG_COLOR = "D9E2F3";
    private static final String TITLE_BAR_COLOR = "4472C4";
    // 열 너비 비율 (5000 pct 기준)
    private static final int COL0_WIDTH = 859;
    private static final int COL1_WIDTH = 945;
    private static final int COL2_WIDTH = 3196;
    private static final int MERGED_HEADER_WIDTH = 1804;
    // 셀 내부 여백 (twip)
    private static final int CELL_PADDING_TB = 60;
    private static final int CELL_PADDING_LR = 113;
    // 테두리 두께
    private static final int BORDER_OUTER = 12;
    private static final int BORDER_INNER = 4;
    // 줄 간격 및 paragraph 간격
    private static final int LINE_SPACING = 276;
    private static final int PARA_SPACING_BEFORE = 40;
    private static final int PARA_SPACING_AFTER = 40;
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*");
    /** 보고서 6개 섹션 헤더 (순서 보장) */
    private static final List<String> SECTION_HEADERS = List.of(
            "목적", "발생한 문제", "문제 원인", "문제 해결 과정", "결과", "개선 및 예방 방안");
    /** "문제 정의" 그룹으로 묶이는 섹션들 */
    private static final Set<String> PROBLEM_GROUP = Set.of("발생한 문제", "문제 원인");

    /**
     * Markdown 보고서를 원본 보고서 형식의 .docx 바이트 배열로 변환합니다.
     *
     * @param markdown Markdown 형식의 보고서 텍스트
     * @return .docx 파일의 바이트 배열
     */
    public byte[] convertToDocx(String markdown) throws IOException {
        // Markdown 파싱
        ParsedReport parsed = parseMarkdown(markdown);
        try (XWPFDocument document = new XWPFDocument()) {
            setPageMargins(document);
            createTitleSection(document, parsed);
            createReportTable(document, parsed);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            log.info("Markdown → 표 기반 docx 변환 완료 ({}bytes)", out.size());
            return out.toByteArray();
        }
    }

    // ============================================================
    // Markdown 파싱
    // ============================================================

    /**
     * Markdown 보고서를 파싱하여 구조화된 데이터로 변환합니다.
     */
    private ParsedReport parseMarkdown(String markdown) {
        ParsedReport parsed = new ParsedReport();
        String[] lines = markdown.split("\n");
        // 제목 파싱 (# 소프트웨어 변경 보고서)
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ") && !trimmed.startsWith("## ")) {
                parsed.title = trimmed.substring(2).trim();
                break;
            }
        }
        if (parsed.title == null) parsed.title = "소프트웨어 변경 보고서";
        // 작성일자 파싱
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("작성일자:")) {
                parsed.createdDate = trimmed.substring("작성일자:".length()).trim();
                break;
            }
        }
        // 메타 테이블 파싱 (| 항목 | 내용 | 형식)
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (!trimmed.startsWith("|")) continue;
            if (trimmed.contains("항목") && trimmed.contains("내용")) continue;
            if (trimmed.matches("\\|[\\s\\-:|]+\\|")) continue;
            String[] cells = parseCells(trimmed);
            if (cells.length >= 2) {
                parsed.metaItems.put(cells[0].trim(), cells[1].trim());
            }
        }
        // 섹션 파싱 (### 제목 → 내용)
        String currentSection = null;
        StringBuilder currentContent = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("### ")) {
                // 이전 섹션 저장
                if (currentSection != null) {
                    parsed.sections.put(currentSection, currentContent.toString().trim());
                }
                currentSection = trimmed.substring(4).trim();
                currentContent = new StringBuilder();
            } else if (currentSection != null) {
                currentContent.append(line).append("\n");
            }
        }
        // 마지막 섹션 저장
        if (currentSection != null) {
            parsed.sections.put(currentSection, currentContent.toString().trim());
        }
        return parsed;
    }

    /** 테이블 라인에서 셀 값을 파싱합니다. */
    private String[] parseCells(String line) {
        if (line.startsWith("|")) line = line.substring(1);
        if (line.endsWith("|")) line = line.substring(0, line.length() - 1);
        return line.split("\\|");
    }

    // ============================================================
    // DOCX 생성
    // ============================================================

    /** 페이지 여백 설정 */
    private void setPageMargins(XWPFDocument document) {
        CTSectPr sectPr = document.getDocument().getBody().addNewSectPr();
        CTPageMar pageMar = sectPr.addNewPgMar();
        pageMar.setTop(BigInteger.valueOf(1701));
        pageMar.setBottom(BigInteger.valueOf(1418));
        pageMar.setLeft(BigInteger.valueOf(1418));
        pageMar.setRight(BigInteger.valueOf(1418));
    }

    /** 제목 영역 생성 (제목 + 파란 구분선 + 작성일자) */
    private void createTitleSection(XWPFDocument document, ParsedReport parsed) {
        // 보고서 제목
        XWPFParagraph titlePara = document.createParagraph();
        titlePara.setAlignment(ParagraphAlignment.CENTER);
        titlePara.setSpacingAfter(100);
        XWPFRun titleRun = titlePara.createRun();
        titleRun.setText(parsed.title);
        titleRun.setBold(true);
        titleRun.setFontSize(TITLE_FONT_SIZE_PT);
        titleRun.setFontFamily(FONT_NAME);
        // 파란 구분선
        XWPFParagraph linePara = document.createParagraph();
        linePara.setSpacingAfter(0);
        linePara.setSpacingBefore(0);
        CTPPr linePPr = linePara.getCTP().addNewPPr();
        CTPBdr pBdr = linePPr.addNewPBdr();
        CTBorder bottomBorder = pBdr.addNewBottom();
        bottomBorder.setVal(STBorder.SINGLE);
        bottomBorder.setSz(BigInteger.valueOf(18));
        bottomBorder.setColor(TITLE_BAR_COLOR);
        bottomBorder.setSpace(BigInteger.valueOf(1));
        // 작성일자
        if (parsed.createdDate != null) {
            XWPFParagraph datePara = document.createParagraph();
            datePara.setAlignment(ParagraphAlignment.RIGHT);
            datePara.setSpacingBefore(120);
            datePara.setSpacingAfter(200);
            XWPFRun labelRun = datePara.createRun();
            labelRun.setText("작성일자");
            labelRun.setBold(true);
            labelRun.setFontFamily(FONT_NAME);
            labelRun.setFontSize(11);
            XWPFRun valueRun = datePara.createRun();
            valueRun.setText(": " + parsed.createdDate);
            valueRun.setFontFamily(FONT_NAME);
            valueRun.setFontSize(11);
        }
    }

    /**
     * 원본 보고서 형식의 단일 테이블을 생성합니다.
     *
     * <p>구조:
     * - 메타 정보 행 (프로젝트, 분석 기간 등) → simple row (헤더 gridSpan=2)
     * - 목적 → simple row
     * - 문제 정의 > 발생한 문제 → group row (세로 병합)
     * - 문제 정의 > 문제 원인 → group row (세로 병합)
     * - 문제 해결 과정 → simple row
     * - 결과 → simple row
     * - 개선 및 예방 방안 → simple row</p>
     */
    private void createReportTable(XWPFDocument document, ParsedReport parsed) {
        CTBody body = document.getDocument().getBody();
        CTTbl ctTbl = body.addNewTbl();
        // 테이블 속성
        CTTblPr tblPr = ctTbl.addNewTblPr();
        CTTblWidth tblW = tblPr.addNewTblW();
        tblW.setW(BigInteger.valueOf(5000));
        tblW.setType(STTblWidth.PCT);
        setTableBorders(tblPr);
        // tblGrid (3열 구조)
        CTTblGrid tblGrid = ctTbl.addNewTblGrid();
        tblGrid.addNewGridCol().setW(BigInteger.valueOf(1546));
        tblGrid.addNewGridCol().setW(BigInteger.valueOf(1700));
        tblGrid.addNewGridCol().setW(BigInteger.valueOf(5750));
        // 1) 메타 정보 행 (simple row)
        for (Map.Entry<String, String> meta : parsed.metaItems.entrySet()) {
            addSimpleRow(ctTbl, meta.getKey(), meta.getValue());
        }
        // 2) 6개 섹션 행 (LLM이 생성한 경우) 또는 fallback
        boolean hasExpectedSections = SECTION_HEADERS.stream()
                .anyMatch(h -> parsed.sections.containsKey(h));
        if (hasExpectedSections) {
            // LLM이 정상적으로 6개 섹션을 생성한 경우
            for (String sectionName : SECTION_HEADERS) {
                String content = parsed.sections.getOrDefault(sectionName, "");
                if (PROBLEM_GROUP.contains(sectionName)) {
                    if ("발생한 문제".equals(sectionName)) {
                        String problemCause = parsed.sections.getOrDefault("문제 원인", "");
                        addGroupRows(ctTbl, "문제 정의",
                                List.of("발생한 문제", "문제 원인"),
                                List.of(content, problemCause));
                    }
                } else {
                    addSimpleRow(ctTbl, sectionName, content);
                }
            }
        } else {
            // LLM 비활성화 등으로 섹션 구조가 없는 경우: 모든 파싱된 섹션을 그대로 표시
            for (Map.Entry<String, String> section : parsed.sections.entrySet()) {
                addSimpleRow(ctTbl, section.getKey(), section.getValue());
            }
        }
        new XWPFTable(ctTbl, document);
    }

    // ============================================================
    // 행 생성 메서드
    // ============================================================

    /** simple 타입: 헤더(gridSpan=2) + 내용 셀 */
    private void addSimpleRow(CTTbl ctTbl, String label, String content) {
        CTRow ctRow = ctTbl.addNewTr();
        setRowMinHeight(ctRow);
        // 헤더 셀 (gridSpan=2)
        CTTc headerTc = ctRow.addNewTc();
        CTTcPr headerPr = headerTc.addNewTcPr();
        headerPr.addNewGridSpan().setVal(BigInteger.valueOf(2));
        setCellWidth(headerPr, MERGED_HEADER_WIDTH);
        setCellShading(headerPr, HEADER_BG_COLOR);
        setCellVAlign(headerPr, STVerticalJc.CENTER);
        addCellParagraph(headerTc, label, true, HEADER_FONT_SIZE_PT, ParagraphAlignment.CENTER);
        // 내용 셀
        CTTc contentTc = ctRow.addNewTc();
        CTTcPr contentPr = contentTc.addNewTcPr();
        setCellWidth(contentPr, COL2_WIDTH);
        setCellVAlign(contentPr, STVerticalJc.CENTER);
        writeMarkdownContent(contentTc, content);
    }

    /** group 타입: 부모 헤더(세로 병합) + 서브행들 */
    private void addGroupRows(CTTbl ctTbl, String parentLabel,
                               List<String> subLabels, List<String> subContents) {
        for (int i = 0; i < subLabels.size(); i++) {
            CTRow ctRow = ctTbl.addNewTr();
            setRowMinHeight(ctRow);
            // 열 0: 부모 라벨 (세로 병합)
            CTTc col0 = ctRow.addNewTc();
            CTTcPr col0Pr = col0.addNewTcPr();
            setCellWidth(col0Pr, COL0_WIDTH);
            setCellShading(col0Pr, HEADER_BG_COLOR);
            setCellVAlign(col0Pr, STVerticalJc.CENTER);
            if (i == 0) {
                col0Pr.addNewVMerge().setVal(STMerge.RESTART);
                addCellParagraph(col0, parentLabel, true, 0, ParagraphAlignment.CENTER);
            } else {
                col0Pr.addNewVMerge();
                col0.addNewP();
            }
            // 열 1: 서브행 라벨
            CTTc col1 = ctRow.addNewTc();
            CTTcPr col1Pr = col1.addNewTcPr();
            setCellWidth(col1Pr, COL1_WIDTH);
            setCellShading(col1Pr, HEADER_BG_COLOR);
            setCellVAlign(col1Pr, STVerticalJc.CENTER);
            addCellParagraph(col1, subLabels.get(i), true, 0, ParagraphAlignment.CENTER);
            // 열 2: 내용
            CTTc col2 = ctRow.addNewTc();
            CTTcPr col2Pr = col2.addNewTcPr();
            setCellWidth(col2Pr, COL2_WIDTH);
            setCellVAlign(col2Pr, STVerticalJc.CENTER);
            writeMarkdownContent(col2, subContents.get(i));
        }
    }

    // ============================================================
    // 콘텐츠 렌더링 (Markdown → 셀 내 paragraph)
    // ============================================================

    /**
     * Markdown 텍스트를 셀 내 paragraph들로 변환합니다.
     *
     * <p>불릿(-), 번호(1.), 볼드(**) 등을 인식하여 적절한 서식을 적용합니다.</p>
     */
    private void writeMarkdownContent(CTTc tc, String content) {
        if (content == null || content.isBlank()) {
            addCellParagraph(tc, "", false, CONTENT_FONT_SIZE_PT, null);
            return;
        }
        String[] lines = content.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // 들여쓰기 수준 감지
            int indent = detectIndent(line);
            int indentTwip = indent > 0 ? 567 * indent : 0;
            if (trimmed.startsWith("- ")) {
                // 불릿 항목
                String text = trimmed.substring(2).trim();
                addFormattedParagraph(tc, "- " + text, indentTwip);
            } else if (trimmed.matches("^\\d+\\.\\s+.*")) {
                // 번호 목록
                addFormattedParagraph(tc, trimmed, indentTwip);
            } else {
                // 일반 텍스트
                addFormattedParagraph(tc, trimmed, indentTwip);
            }
        }
    }

    /**
     * 들여쓰기 수준을 감지합니다 (공백 2개 또는 4개 = 1레벨).
     */
    private int detectIndent(String line) {
        int spaces = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') spaces++;
            else break;
        }
        return spaces / 2;
    }

    /**
     * 볼드(**text**) 패턴을 처리하여 서식이 적용된 paragraph를 추가합니다.
     */
    private void addFormattedParagraph(CTTc tc, String text, int indentTwip) {
        CTP ctp = tc.addNewP();
        CTPPr pPr = ctp.addNewPPr();
        if (indentTwip > 0) {
            pPr.addNewInd().setLeft(BigInteger.valueOf(indentTwip));
        }
        setParaSpacing(pPr);
        // 볼드 패턴 처리
        Matcher matcher = BOLD_PATTERN.matcher(text);
        int lastEnd = 0;
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                addRun(ctp, text.substring(lastEnd, matcher.start()), false);
            }
            addRun(ctp, matcher.group(1), true);
            lastEnd = matcher.end();
        }
        if (lastEnd < text.length()) {
            addRun(ctp, text.substring(lastEnd), false);
        }
    }

    /** 텍스트 run을 추가합니다. */
    private void addRun(CTP ctp, String text, boolean bold) {
        CTR ctr = ctp.addNewR();
        setRunProperties(ctr, bold, CONTENT_FONT_SIZE_PT);
        CTText ctText = ctr.addNewT();
        ctText.setStringValue(text);
        ctText.setSpace(SpaceAttribute.Space.PRESERVE);
    }

    // ============================================================
    // 저수준 유틸리티 (ErrorFixReportService 패턴)
    // ============================================================

    /** 테이블 테두리 설정 */
    private void setTableBorders(CTTblPr tblPr) {
        CTTblBorders borders = tblPr.addNewTblBorders();
        setBorder(borders.addNewTop(), BORDER_OUTER, TITLE_BAR_COLOR);
        setBorder(borders.addNewBottom(), BORDER_OUTER, TITLE_BAR_COLOR);
        setBorder(borders.addNewLeft(), BORDER_OUTER, TITLE_BAR_COLOR);
        setBorder(borders.addNewRight(), BORDER_OUTER, TITLE_BAR_COLOR);
        setBorder(borders.addNewInsideH(), BORDER_INNER, "BFBFBF");
        setBorder(borders.addNewInsideV(), BORDER_INNER, "BFBFBF");
    }

    private void setBorder(CTBorder border, int size, String color) {
        border.setVal(STBorder.SINGLE);
        border.setSz(BigInteger.valueOf(size));
        border.setColor(color);
    }

    /** paragraph 추가 (기본) */
    private void addCellParagraph(CTTc tc, String text, boolean bold, int fontSize, ParagraphAlignment alignment) {
        CTP ctp = tc.addNewP();
        CTPPr pPr = ctp.addNewPPr();
        if (alignment != null) {
            pPr.addNewJc().setVal(STJc.Enum.forInt(alignment.getValue()));
        }
        setParaSpacing(pPr);
        CTR ctr = ctp.addNewR();
        setRunProperties(ctr, bold, fontSize);
        CTText ctText = ctr.addNewT();
        ctText.setStringValue(text);
        ctText.setSpace(SpaceAttribute.Space.PRESERVE);
    }

    /** paragraph 줄간격 및 간격 설정 */
    private void setParaSpacing(CTPPr pPr) {
        CTSpacing spacing = pPr.addNewSpacing();
        spacing.setLine(BigInteger.valueOf(LINE_SPACING));
        spacing.setLineRule(STLineSpacingRule.AUTO);
        spacing.setBefore(BigInteger.valueOf(PARA_SPACING_BEFORE));
        spacing.setAfter(BigInteger.valueOf(PARA_SPACING_AFTER));
    }

    /** run 속성 (볼드, 폰트, 크기) */
    private void setRunProperties(CTR ctr, boolean bold, int fontSize) {
        CTRPr rPr = ctr.addNewRPr();
        if (bold) rPr.addNewB();
        CTFonts fonts = rPr.addNewRFonts();
        fonts.setAscii(FONT_NAME);
        fonts.setHAnsi(FONT_NAME);
        fonts.setEastAsia(FONT_NAME);
        if (fontSize > 0) {
            rPr.addNewSz().setVal(BigInteger.valueOf((long) fontSize * 2));
            rPr.addNewSzCs().setVal(BigInteger.valueOf((long) fontSize * 2));
        }
    }

    /** 행 최소 높이 설정 */
    private void setRowMinHeight(CTRow ctRow) {
        CTTrPr trPr = ctRow.addNewTrPr();
        CTHeight trHeight = trPr.addNewTrHeight();
        trHeight.setVal(BigInteger.valueOf(477));
        trHeight.setHRule(STHeightRule.AT_LEAST);
    }

    /** 셀 너비 (pct) */
    private void setCellWidth(CTTcPr tcPr, int widthPct) {
        CTTblWidth tcW = tcPr.addNewTcW();
        tcW.setW(BigInteger.valueOf(widthPct));
        tcW.setType(STTblWidth.PCT);
    }

    /** 셀 배경색 */
    private void setCellShading(CTTcPr tcPr, String color) {
        CTShd shd = tcPr.addNewShd();
        shd.setVal(STShd.CLEAR);
        shd.setFill(color);
    }

    /** 셀 수직 중앙 정렬 + padding */
    private void setCellVAlign(CTTcPr tcPr, STVerticalJc.Enum alignment) {
        tcPr.addNewVAlign().setVal(alignment);
        setCellMargins(tcPr);
    }

    /** 셀 내부 여백 */
    private void setCellMargins(CTTcPr tcPr) {
        CTTcMar mar = tcPr.addNewTcMar();
        setCellMarginSide(mar.addNewTop(), BigInteger.valueOf(CELL_PADDING_TB));
        setCellMarginSide(mar.addNewBottom(), BigInteger.valueOf(CELL_PADDING_TB));
        setCellMarginSide(mar.addNewLeft(), BigInteger.valueOf(CELL_PADDING_LR));
        setCellMarginSide(mar.addNewRight(), BigInteger.valueOf(CELL_PADDING_LR));
    }

    private void setCellMarginSide(CTTblWidth side, BigInteger twip) {
        side.setW(twip);
        side.setType(STTblWidth.DXA);
    }

    // ============================================================
    // 내부 데이터 클래스
    // ============================================================

    /** Markdown 파싱 결과를 담는 내부 클래스 */
    private static class ParsedReport {
        String title;
        String createdDate;
        /** 메타 정보 (프로젝트, 분석 기간 등) - 순서 유지 */
        Map<String, String> metaItems = new LinkedHashMap<>();
        /** 섹션별 내용 (### 제목 → 본문) */
        Map<String, String> sections = new LinkedHashMap<>();
    }
}
