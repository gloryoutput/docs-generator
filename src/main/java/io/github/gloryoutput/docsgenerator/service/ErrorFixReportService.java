package io.github.gloryoutput.docsgenerator.service;

import io.github.gloryoutput.docsgenerator.dto.request.*;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.impl.xb.xmlschema.SpaceAttribute;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.stereotype.Service;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

/**
 * 오류 수정 완료 보고서 docx 생성 서비스
 *
 * <p>입력 데이터를 기반으로 원본 문서 레이아웃에 맞는 docx 파일을 생성합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@RequiredArgsConstructor
public class ErrorFixReportService {
    private static final int HEADER_FONT_SIZE_PT = 12;
    private static final int CONTENT_SMALL_FONT_SIZE_PT = 10;
    private static final int TITLE_FONT_SIZE_PT = 24;
    private static final String FONT_NAME = "맑은 고딕";
    private static final String HEADER_BG_COLOR = "F2F2F2";
    // 원본 기준 열 너비 비율 (5000 pct 기준)
    private static final int COL0_WIDTH = 859;   // 열0 너비
    private static final int COL1_WIDTH = 945;   // 열1 너비
    private static final int COL2_WIDTH = 3196;  // 열2 너비 (내용)
    private static final int MERGED_HEADER_WIDTH = 1804; // 열0+1 병합 시 헤더 너비
    // 들여쓰기 단위 (twip): 1cm ≈ 567 twip
    private static final int INDENT_LEVEL_1 = 567;  // 소제목/설명
    private static final int INDENT_LEVEL_2 = 1134; // 항목 (- )
    private static final int CELL_PADDING = 80;     // 셀 내부 여백 (약 1.4mm)

    /**
     * 오류 수정 완료 보고서 docx 바이트 배열을 생성합니다.
     *
     * @param request 보고서 생성 요청 DTO
     * @return 생성된 docx 파일의 바이트 배열
     * @throws IOException docx 생성 중 I/O 오류 발생 시
     */
    public byte[] generateReport(ErrorFixReportRequest request) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            setPageMargins(document);
            createTitleSection(document, request);
            createReportTable(document, request);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            document.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    /** 페이지 여백 설정 (상단 30mm, 하단/좌/우 25mm) */
    private void setPageMargins(XWPFDocument document) {
        CTSectPr sectPr = document.getDocument().getBody().addNewSectPr();
        CTPageMar pageMar = sectPr.addNewPgMar();
        pageMar.setTop(BigInteger.valueOf(1701));
        pageMar.setBottom(BigInteger.valueOf(1418));
        pageMar.setLeft(BigInteger.valueOf(1418));
        pageMar.setRight(BigInteger.valueOf(1418));
    }

    /** 제목 영역 생성 (보고서 제목 + 작성일자) */
    private void createTitleSection(XWPFDocument document, ErrorFixReportRequest request) {
        // 보고서 제목 - 중앙 정렬, 볼드, 24pt
        XWPFParagraph titlePara = document.createParagraph();
        titlePara.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun titleRun = titlePara.createRun();
        titleRun.setText(request.getTitle());
        titleRun.setBold(true);
        titleRun.setFontSize(TITLE_FONT_SIZE_PT);
        titleRun.setFontFamily(FONT_NAME);
        // 작성일자 - 우측 정렬, "작성일자" 부분만 볼드
        XWPFParagraph datePara = document.createParagraph();
        datePara.setAlignment(ParagraphAlignment.RIGHT);
        XWPFRun dateLabelRun = datePara.createRun();
        dateLabelRun.setText("작성일자");
        dateLabelRun.setBold(true);
        dateLabelRun.setFontFamily(FONT_NAME);
        XWPFRun dateValueRun = datePara.createRun();
        dateValueRun.setText(": " + request.getCreatedDate());
        dateValueRun.setFontFamily(FONT_NAME);
    }

    /** 보고서 테이블 전체 생성 (XML 직접 제어) */
    private void createReportTable(XWPFDocument document, ErrorFixReportRequest request) {
        // document body에 직접 tbl 요소를 추가하여 테이블 생성
        CTBody body = document.getDocument().getBody();
        CTTbl ctTbl = body.addNewTbl();
        // 테이블 속성: 너비 100%, 테두리
        CTTblPr tblPr = ctTbl.addNewTblPr();
        CTTblWidth tblW = tblPr.addNewTblW();
        tblW.setW(BigInteger.valueOf(5000));
        tblW.setType(STTblWidth.PCT);
        setTableBorders(tblPr);
        // tblGrid: 3열 너비 정의
        CTTblGrid tblGrid = ctTbl.addNewTblGrid();
        tblGrid.addNewGridCol().setW(BigInteger.valueOf(1546));
        tblGrid.addNewGridCol().setW(BigInteger.valueOf(1700));
        tblGrid.addNewGridCol().setW(BigInteger.valueOf(5750));
        // 행 생성
        addMergedRow(ctTbl, "회사명", request.getCompanyName(), HEADER_FONT_SIZE_PT, CONTENT_SMALL_FONT_SIZE_PT);
        addMergedRow(ctTbl, "제출자 성명", request.getAuthorName(), HEADER_FONT_SIZE_PT, CONTENT_SMALL_FONT_SIZE_PT);
        addMergedRow(ctTbl, "목적", request.getPurpose(), HEADER_FONT_SIZE_PT, CONTENT_SMALL_FONT_SIZE_PT);
        addProblemIssueRow(ctTbl, request.getProblemDefinition());
        addProblemCauseRow(ctTbl, request.getProblemDefinition());
        addResolutionRow(ctTbl, request.getResolutionProcess());
        addResultRow(ctTbl, request.getResult());
        addPreventionRow(ctTbl, request.getPrevention());
        // XWPFTable로 래핑하여 document에 등록
        new XWPFTable(ctTbl, document);
    }

    /** 테이블 테두리 설정 */
    private void setTableBorders(CTTblPr tblPr) {
        CTTblBorders borders = tblPr.addNewTblBorders();
        setBorder(borders.addNewTop());
        setBorder(borders.addNewBottom());
        setBorder(borders.addNewLeft());
        setBorder(borders.addNewRight());
        setBorder(borders.addNewInsideH());
        setBorder(borders.addNewInsideV());
    }

    private void setBorder(CTBorder border) {
        border.setVal(STBorder.SINGLE);
        border.setSz(BigInteger.valueOf(4));
        border.setColor("000000");
    }

    // ============================================================
    // 행 생성 메서드들 (원본과 동일한 2셀/3셀 구조)
    // ============================================================

    /** 2셀 병합 행 생성 (회사명, 제출자 성명, 목적 등) */
    private void addMergedRow(CTTbl ctTbl, String headerText, String contentText, int headerFontSize, int contentFontSize) {
        CTRow ctRow = ctTbl.addNewTr();
        // 셀 0: 헤더 (gridSpan=2, 배경색, 수직 중앙)
        CTTc headerTc = ctRow.addNewTc();
        CTTcPr headerPr = headerTc.addNewTcPr();
        headerPr.addNewGridSpan().setVal(BigInteger.valueOf(2));
        setCellWidth(headerPr, MERGED_HEADER_WIDTH);
        setCellShading(headerPr, HEADER_BG_COLOR);
        setCellVAlign(headerPr, STVerticalJc.CENTER);
        addCellParagraph(headerTc, headerText, true, headerFontSize, ParagraphAlignment.CENTER);
        // 셀 1: 내용 (수직 중앙)
        CTTc contentTc = ctRow.addNewTc();
        CTTcPr contentPr = contentTc.addNewTcPr();
        setCellWidth(contentPr, COL2_WIDTH);
        setCellVAlign(contentPr, STVerticalJc.CENTER);
        addCellParagraph(contentTc, contentText, false, contentFontSize, ParagraphAlignment.CENTER);
    }

    /** 문제 정의 - 발생한 문제 행 (3셀, 열0 세로병합 시작) */
    private void addProblemIssueRow(CTTbl ctTbl, ProblemDefinition problemDef) {
        CTRow ctRow = ctTbl.addNewTr();
        // 셀 0: "문제 정의" (세로 병합 시작)
        CTTc col0 = ctRow.addNewTc();
        CTTcPr col0Pr = col0.addNewTcPr();
        setCellWidth(col0Pr, COL0_WIDTH);
        setCellShading(col0Pr, HEADER_BG_COLOR);
        setCellVAlign(col0Pr, STVerticalJc.CENTER);
        CTVMerge vMerge = col0Pr.addNewVMerge();
        vMerge.setVal(STMerge.RESTART);
        addCellParagraph(col0, "문제 정의", true, 0, ParagraphAlignment.CENTER);
        // 셀 1: "발생한 문제"
        CTTc col1 = ctRow.addNewTc();
        CTTcPr col1Pr = col1.addNewTcPr();
        setCellWidth(col1Pr, COL1_WIDTH);
        setCellShading(col1Pr, HEADER_BG_COLOR);
        setCellVAlign(col1Pr, STVerticalJc.CENTER);
        addCellParagraph(col1, "발생한 문제", true, 0, ParagraphAlignment.CENTER);
        // 셀 2: 발생한 문제 내용
        CTTc col2 = ctRow.addNewTc();
        CTTcPr col2Pr = col2.addNewTcPr();
        setCellWidth(col2Pr, COL2_WIDTH);
        setCellVAlign(col2Pr, STVerticalJc.CENTER);
        writeIssuesContent(col2, problemDef.getIssues());
    }

    /** 문제 정의 - 문제 원인 행 (3셀, 열0 세로병합 계속) */
    private void addProblemCauseRow(CTTbl ctTbl, ProblemDefinition problemDef) {
        CTRow ctRow = ctTbl.addNewTr();
        // 셀 0: 세로 병합 계속 (빈 셀)
        CTTc col0 = ctRow.addNewTc();
        CTTcPr col0Pr = col0.addNewTcPr();
        setCellWidth(col0Pr, COL0_WIDTH);
        setCellShading(col0Pr, HEADER_BG_COLOR);
        setCellVAlign(col0Pr, STVerticalJc.CENTER);
        col0Pr.addNewVMerge(); // val 생략 = continue
        col0.addNewP(); // 빈 paragraph 필수
        // 셀 1: "문제 원인"
        CTTc col1 = ctRow.addNewTc();
        CTTcPr col1Pr = col1.addNewTcPr();
        setCellWidth(col1Pr, COL1_WIDTH);
        setCellShading(col1Pr, HEADER_BG_COLOR);
        setCellVAlign(col1Pr, STVerticalJc.CENTER);
        addCellParagraph(col1, "문제 원인", true, 0, ParagraphAlignment.CENTER);
        // 셀 2: 문제 원인 내용
        CTTc col2 = ctRow.addNewTc();
        CTTcPr col2Pr = col2.addNewTcPr();
        setCellWidth(col2Pr, COL2_WIDTH);
        setCellVAlign(col2Pr, STVerticalJc.CENTER);
        writeCausesContent(col2, problemDef.getCauses());
    }

    /** 문제 해결 과정 행 (2셀, 열0+1 병합) */
    private void addResolutionRow(CTTbl ctTbl, List<ResolutionStep> steps) {
        CTRow ctRow = ctTbl.addNewTr();
        // 셀 0: 헤더 (gridSpan=2)
        CTTc headerTc = ctRow.addNewTc();
        CTTcPr headerPr = headerTc.addNewTcPr();
        headerPr.addNewGridSpan().setVal(BigInteger.valueOf(2));
        setCellWidth(headerPr, MERGED_HEADER_WIDTH);
        setCellShading(headerPr, HEADER_BG_COLOR);
        setCellVAlign(headerPr, STVerticalJc.CENTER);
        addCellParagraph(headerTc, "문제 해결 과정", true, 0, ParagraphAlignment.CENTER);
        // 셀 1: 내용
        CTTc contentTc = ctRow.addNewTc();
        CTTcPr contentPr = contentTc.addNewTcPr();
        setCellWidth(contentPr, COL2_WIDTH);
        setCellVAlign(contentPr, STVerticalJc.CENTER);
        writeResolutionContent(contentTc, steps);
    }

    /** 결과 행 (2셀, 열0+1 병합) */
    private void addResultRow(CTTbl ctTbl, List<String> results) {
        CTRow ctRow = ctTbl.addNewTr();
        CTTc headerTc = ctRow.addNewTc();
        CTTcPr headerPr = headerTc.addNewTcPr();
        headerPr.addNewGridSpan().setVal(BigInteger.valueOf(2));
        setCellWidth(headerPr, MERGED_HEADER_WIDTH);
        setCellShading(headerPr, HEADER_BG_COLOR);
        setCellVAlign(headerPr, STVerticalJc.CENTER);
        addCellParagraph(headerTc, "결과", true, 0, ParagraphAlignment.CENTER);
        CTTc contentTc = ctRow.addNewTc();
        CTTcPr contentPr = contentTc.addNewTcPr();
        setCellWidth(contentPr, COL2_WIDTH);
        setCellVAlign(contentPr, STVerticalJc.CENTER);
        for (String result : results) {
            addCellParagraph(contentTc, result, false, 0, null);
        }
    }

    /** 개선 및 예방 방안 행 (2셀, 열0+1 병합) */
    private void addPreventionRow(CTTbl ctTbl, List<PreventionItem> items) {
        CTRow ctRow = ctTbl.addNewTr();
        CTTc headerTc = ctRow.addNewTc();
        CTTcPr headerPr = headerTc.addNewTcPr();
        headerPr.addNewGridSpan().setVal(BigInteger.valueOf(2));
        setCellWidth(headerPr, MERGED_HEADER_WIDTH);
        setCellShading(headerPr, HEADER_BG_COLOR);
        setCellVAlign(headerPr, STVerticalJc.CENTER);
        addCellParagraph(headerTc, "개선 및 예방 방안", true, 0, ParagraphAlignment.CENTER);
        CTTc contentTc = ctRow.addNewTc();
        CTTcPr contentPr = contentTc.addNewTcPr();
        setCellWidth(contentPr, COL2_WIDTH);
        setCellVAlign(contentPr, STVerticalJc.CENTER);
        writePreventionContent(contentTc, items);
    }

    // ============================================================
    // 내용 셀 텍스트 작성 메서드들 (CTTc 직접 조작)
    // ============================================================

    /** 발생한 문제 내용 작성 */
    private void writeIssuesContent(CTTc tc, List<Issue> issues) {
        for (Issue issue : issues) {
            addCellParagraph(tc, issue.getTitle(), true, 0, null);
            for (String desc : issue.getDescriptions()) {
                addCellParagraph(tc, desc, false, 0, null);
            }
        }
    }

    /** 문제 원인 내용 작성 */
    private void writeCausesContent(CTTc tc, List<Cause> causes) {
        for (Cause cause : causes) {
            addCellParagraph(tc, cause.getTitle(), true, 0, null);
            addCellParagraph(tc, cause.getDescription(), false, 0, null);
        }
    }

    /** 문제 해결 과정 내용 작성 (들여쓰기 적용) */
    private void writeResolutionContent(CTTc tc, List<ResolutionStep> steps) {
        for (ResolutionStep step : steps) {
            // 단계 제목 (볼드, 들여쓰기 없음)
            addCellParagraph(tc, step.getStepNumber() + ". " + step.getTitle(), true, 0, null, 0);
            if (step.getSubSteps() != null && !step.getSubSteps().isEmpty()) {
                for (int i = 0; i < step.getSubSteps().size(); i++) {
                    SubStep subStep = step.getSubSteps().get(i);
                    // 소제목 (볼드, 1단계 들여쓰기, 자동 번호)
                    addCellParagraph(tc, (i + 1) + ". " + subStep.getTitle(), true, 0, null, INDENT_LEVEL_1);
                    // 항목들 ("- " 접두사, 2단계 들여쓰기)
                    for (String item : subStep.getItems()) {
                        addIndentedDashParagraph(tc, item, INDENT_LEVEL_2);
                    }
                }
            } else if (step.getDescriptions() != null) {
                // 단순 설명 (1단계 들여쓰기, 자동 번호)
                for (int i = 0; i < step.getDescriptions().size(); i++) {
                    addCellParagraph(tc, (i + 1) + ". " + step.getDescriptions().get(i), false, 0, null, INDENT_LEVEL_1);
                }
            }
        }
    }

    /** 개선 및 예방 방안 내용 작성 */
    private void writePreventionContent(CTTc tc, List<PreventionItem> items) {
        for (PreventionItem item : items) {
            addCellParagraph(tc, item.getTitle(), true, 0, null);
            for (String desc : item.getDescriptions()) {
                addCellParagraph(tc, desc, false, 0, null);
            }
        }
    }

    // ============================================================
    // 저수준 유틸리티 메서드들
    // ============================================================

    /** CTTc에 서식이 적용된 paragraph 추가 (들여쓰기 없음) */
    private void addCellParagraph(CTTc tc, String text, boolean bold, int fontSize, ParagraphAlignment alignment) {
        addCellParagraph(tc, text, bold, fontSize, alignment, 0);
    }

    /** CTTc에 서식이 적용된 paragraph 추가 (들여쓰기 지원) */
    private void addCellParagraph(CTTc tc, String text, boolean bold, int fontSize, ParagraphAlignment alignment, int indentTwip) {
        CTP ctp = tc.addNewP();
        CTPPr pPr = ctp.addNewPPr();
        // 정렬 설정
        if (alignment != null) {
            pPr.addNewJc().setVal(STJc.Enum.forInt(alignment.getValue()));
        }
        // 들여쓰기 설정
        if (indentTwip > 0) {
            CTInd ind = pPr.addNewInd();
            ind.setLeft(BigInteger.valueOf(indentTwip));
        }
        // 텍스트 run
        CTR ctr = ctp.addNewR();
        setRunProperties(ctr, bold, fontSize);
        CTText ctText = ctr.addNewT();
        ctText.setStringValue(text);
        ctText.setSpace(SpaceAttribute.Space.PRESERVE);
    }

    /** "- " 접두사가 붙은 들여쓰기 paragraph 추가 */
    private void addIndentedDashParagraph(CTTc tc, String text, int indentTwip) {
        CTP ctp = tc.addNewP();
        CTPPr pPr = ctp.addNewPPr();
        CTInd ind = pPr.addNewInd();
        ind.setLeft(BigInteger.valueOf(indentTwip));
        // "- " run
        CTR dashR = ctp.addNewR();
        setRunProperties(dashR, false, 0);
        CTText dashText = dashR.addNewT();
        dashText.setStringValue("- ");
        dashText.setSpace(SpaceAttribute.Space.PRESERVE);
        // 내용 run
        CTR textR = ctp.addNewR();
        setRunProperties(textR, false, 0);
        CTText contentText = textR.addNewT();
        contentText.setStringValue(text);
        contentText.setSpace(SpaceAttribute.Space.PRESERVE);
    }

    /** CTR에 run 속성 설정 (볼드, 폰트, 크기) */
    private void setRunProperties(CTR ctr, boolean bold, int fontSize) {
        CTRPr rPr = ctr.addNewRPr();
        if (bold) {
            rPr.addNewB();
        }
        // 폰트 설정
        CTFonts fonts = rPr.addNewRFonts();
        fonts.setAscii(FONT_NAME);
        fonts.setHAnsi(FONT_NAME);
        fonts.setEastAsia(FONT_NAME);
        // 폰트 크기
        if (fontSize > 0) {
            rPr.addNewSz().setVal(BigInteger.valueOf((long) fontSize * 2)); // half-point 단위
            rPr.addNewSzCs().setVal(BigInteger.valueOf((long) fontSize * 2));
        }
    }

    /** 셀 너비 설정 (pct 비율) */
    private void setCellWidth(CTTcPr tcPr, int widthPct) {
        CTTblWidth tcW = tcPr.addNewTcW();
        tcW.setW(BigInteger.valueOf(widthPct));
        tcW.setType(STTblWidth.PCT);
    }

    /** 셀 배경색 설정 */
    private void setCellShading(CTTcPr tcPr, String color) {
        CTShd shd = tcPr.addNewShd();
        shd.setVal(STShd.CLEAR);
        shd.setFill(color);
    }

    /** 셀 수직 중앙 정렬 + 내부 여백 */
    private void setCellVAlign(CTTcPr tcPr, STVerticalJc.Enum alignment) {
        tcPr.addNewVAlign().setVal(alignment);
        setCellMargins(tcPr, CELL_PADDING);
    }

    /** 셀 내부 여백(padding) 설정 (twip 단위, 상하좌우 동일) */
    private void setCellMargins(CTTcPr tcPr, int twip) {
        CTTcMar mar = tcPr.addNewTcMar();
        BigInteger val = BigInteger.valueOf(twip);
        setCellMarginSide(mar.addNewTop(), val);
        setCellMarginSide(mar.addNewBottom(), val);
        setCellMarginSide(mar.addNewLeft(), val);
        setCellMarginSide(mar.addNewRight(), val);
    }

    private void setCellMarginSide(CTTblWidth side, BigInteger twip) {
        side.setW(twip);
        side.setType(STTblWidth.DXA);
    }
}
