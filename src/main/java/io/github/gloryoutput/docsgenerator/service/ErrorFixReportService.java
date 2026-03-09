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
 * <p>입력 데이터를 기반으로 동적 테이블 구조의 docx 파일을 생성합니다.
 * 섹션 리스트에 따라 행 개수와 구조가 결정됩니다.</p>
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
    private static final String HEADER_BG_COLOR = "D9E2F3";  // 연한 파랑 (보고서 스타일)
    private static final String TITLE_BAR_COLOR = "4472C4";   // 진한 파랑 (제목 바)
    // 원본 기준 열 너비 비율 (5000 pct 기준)
    private static final int COL0_WIDTH = 859;
    private static final int COL1_WIDTH = 945;
    private static final int COL2_WIDTH = 3196;
    private static final int MERGED_HEADER_WIDTH = 1804;
    // 들여쓰기 (twip)
    private static final int INDENT_LEVEL_1 = 567;
    private static final int INDENT_LEVEL_2 = 1134;
    // 셀 내부 여백 (twip)
    private static final int CELL_PADDING_TB = 60;   // 상하
    private static final int CELL_PADDING_LR = 113;  // 좌우 (약 2mm)
    // 테두리 두께
    private static final int BORDER_OUTER = 12;  // 외곽 두꺼움
    private static final int BORDER_INNER = 4;   // 내부 얇음
    // 행 최소 높이 (twip)
    private static final int HEADER_ROW_HEIGHT = 477;
    // 줄 간격 (240 = single, 276 = 1.15배, 360 = 1.5배)
    private static final int LINE_SPACING = 276;
    // paragraph 간격 (twip)
    private static final int PARA_SPACING_BEFORE = 40;
    private static final int PARA_SPACING_AFTER = 40;

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

    /** 페이지 여백 설정 */
    private void setPageMargins(XWPFDocument document) {
        CTSectPr sectPr = document.getDocument().getBody().addNewSectPr();
        CTPageMar pageMar = sectPr.addNewPgMar();
        pageMar.setTop(BigInteger.valueOf(1701));
        pageMar.setBottom(BigInteger.valueOf(1418));
        pageMar.setLeft(BigInteger.valueOf(1418));
        pageMar.setRight(BigInteger.valueOf(1418));
    }

    /** 제목 영역 생성 */
    private void createTitleSection(XWPFDocument document, ErrorFixReportRequest request) {
        // 보고서 제목 - 중앙 정렬, 볼드, 24pt
        XWPFParagraph titlePara = document.createParagraph();
        titlePara.setAlignment(ParagraphAlignment.CENTER);
        titlePara.setSpacingAfter(100);
        XWPFRun titleRun = titlePara.createRun();
        titleRun.setText(request.getTitle());
        titleRun.setBold(true);
        titleRun.setFontSize(TITLE_FONT_SIZE_PT);
        titleRun.setFontFamily(FONT_NAME);
        // 구분선 - 진한 파랑 가로선
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
        // 수신자 - 우측 정렬 (선택)
        if (request.getRecipient() != null && !request.getRecipient().isBlank()) {
            addMetaLine(document, "수신자", request.getRecipient(), 120, 0);
        }
        // 담당자 - 우측 정렬 (선택)
        if (request.getManager() != null && !request.getManager().isBlank()) {
            addMetaLine(document, "담당자", request.getManager(), 0, 0);
        }
        // 작성일자 - 우측 정렬
        int spacingBefore = (request.getRecipient() != null || request.getManager() != null) ? 0 : 120;
        addMetaLine(document, "작성일자", request.getCreatedDate(), spacingBefore, 200);
    }

    /** 제목 영역 메타 정보 행 (우측 정렬, "라벨: 값" 형식) */
    private void addMetaLine(XWPFDocument document, String label, String value, int spacingBefore, int spacingAfter) {
        XWPFParagraph para = document.createParagraph();
        para.setAlignment(ParagraphAlignment.RIGHT);
        para.setSpacingBefore(spacingBefore);
        para.setSpacingAfter(spacingAfter);
        XWPFRun labelRun = para.createRun();
        labelRun.setText(label);
        labelRun.setBold(true);
        labelRun.setFontFamily(FONT_NAME);
        labelRun.setFontSize(11);
        XWPFRun valueRun = para.createRun();
        valueRun.setText(": " + value);
        valueRun.setFontFamily(FONT_NAME);
        valueRun.setFontSize(11);
    }

    /** 보고서 테이블 전체 생성 - 섹션 리스트에 따라 동적으로 행 생성 */
    private void createReportTable(XWPFDocument document, ErrorFixReportRequest request) {
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
        // 동적 행 생성
        for (ReportSection section : request.getSections()) {
            if (section.getType() == SectionType.GROUP) {
                addGroupRows(ctTbl, section);
            } else {
                addSimpleRow(ctTbl, section);
            }
        }
        new XWPFTable(ctTbl, document);
    }

    /** 테이블 테두리: 외곽 두껍게, 내부 얇게 */
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

    // ============================================================
    // 행 생성 메서드
    // ============================================================

    /** simple 타입: 헤더(gridSpan=2) + 내용 셀 */
    private void addSimpleRow(CTTbl ctTbl, ReportSection section) {
        CTRow ctRow = ctTbl.addNewTr();
        setRowHeight(ctRow, HEADER_ROW_HEIGHT);
        // 헤더 셀 (gridSpan=2)
        CTTc headerTc = ctRow.addNewTc();
        CTTcPr headerPr = headerTc.addNewTcPr();
        headerPr.addNewGridSpan().setVal(BigInteger.valueOf(2));
        setCellWidth(headerPr, MERGED_HEADER_WIDTH);
        setCellShading(headerPr, HEADER_BG_COLOR);
        setCellVAlign(headerPr, STVerticalJc.CENTER);
        addCellParagraph(headerTc, section.getLabel(), true, HEADER_FONT_SIZE_PT, ParagraphAlignment.CENTER);
        // 내용 셀
        CTTc contentTc = ctRow.addNewTc();
        CTTcPr contentPr = contentTc.addNewTcPr();
        setCellWidth(contentPr, COL2_WIDTH);
        setCellVAlign(contentPr, STVerticalJc.CENTER);
        writeContent(contentTc, section.getContentType(), section.getTextValue(),
                section.getListItems(), section.getTitledItems(), section.getSteps());
    }

    /** group 타입: 부모 헤더(세로 병합) + 서브행들 */
    private void addGroupRows(CTTbl ctTbl, ReportSection section) {
        List<SectionSubRow> subRows = section.getSubRows();
        for (int i = 0; i < subRows.size(); i++) {
            SectionSubRow subRow = subRows.get(i);
            CTRow ctRow = ctTbl.addNewTr();
            // 열 0: 부모 라벨 (세로 병합)
            CTTc col0 = ctRow.addNewTc();
            CTTcPr col0Pr = col0.addNewTcPr();
            setCellWidth(col0Pr, COL0_WIDTH);
            setCellShading(col0Pr, HEADER_BG_COLOR);
            setCellVAlign(col0Pr, STVerticalJc.CENTER);
            if (i == 0) {
                col0Pr.addNewVMerge().setVal(STMerge.RESTART);
                addCellParagraph(col0, section.getLabel(), true, 0, ParagraphAlignment.CENTER);
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
            addCellParagraph(col1, subRow.getLabel(), true, 0, ParagraphAlignment.CENTER);
            // 열 2: 내용
            CTTc col2 = ctRow.addNewTc();
            CTTcPr col2Pr = col2.addNewTcPr();
            setCellWidth(col2Pr, COL2_WIDTH);
            setCellVAlign(col2Pr, STVerticalJc.CENTER);
            writeContent(col2, subRow.getContentType(), subRow.getTextValue(),
                    subRow.getListItems(), subRow.getTitledItems(), subRow.getSteps());
        }
    }

    // ============================================================
    // 콘텐츠 렌더링
    // ============================================================

    /** contentType에 따라 셀 내용을 작성 */
    private void writeContent(CTTc tc, ContentType contentType, String textValue,
                              List<String> listItems, List<TitledItem> titledItems,
                              List<ResolutionStep> steps) {
        switch (contentType) {
            case TEXT -> addCellParagraph(tc, textValue, false, CONTENT_SMALL_FONT_SIZE_PT, ParagraphAlignment.CENTER);
            case NUMBERED_LIST -> writeNumberedList(tc, listItems);
            case TITLED_LIST -> writeTitledList(tc, titledItems);
            case STEPS -> writeStepsContent(tc, steps);
        }
    }

    /** 번호 목록 (1. xxx, 2. xxx) */
    private void writeNumberedList(CTTc tc, List<String> items) {
        for (int i = 0; i < items.size(); i++) {
            addCellParagraph(tc, (i + 1) + ". " + items.get(i), false, 0, null, 0);
        }
    }

    /** 제목+설명 목록 */
    private void writeTitledList(CTTc tc, List<TitledItem> items) {
        for (int i = 0; i < items.size(); i++) {
            TitledItem item = items.get(i);
            addCellParagraph(tc, (i + 1) + ". " + item.getTitle(), true, 0, null, 0);
            for (String desc : item.getDescriptions()) {
                addCellParagraph(tc, desc, false, 0, null, INDENT_LEVEL_1);
            }
        }
    }

    /** 단계별 해결 과정 */
    private void writeStepsContent(CTTc tc, List<ResolutionStep> steps) {
        for (ResolutionStep step : steps) {
            addCellParagraph(tc, step.getStepNumber() + ". " + step.getTitle(), true, 0, null, 0);
            if (step.getSubSteps() != null && !step.getSubSteps().isEmpty()) {
                for (int i = 0; i < step.getSubSteps().size(); i++) {
                    SubStep subStep = step.getSubSteps().get(i);
                    addCellParagraph(tc, (i + 1) + ". " + subStep.getTitle(), true, 0, null, INDENT_LEVEL_1);
                    for (String item : subStep.getItems()) {
                        addIndentedDashParagraph(tc, item, INDENT_LEVEL_2);
                    }
                }
            } else if (step.getDescriptions() != null) {
                for (int i = 0; i < step.getDescriptions().size(); i++) {
                    addCellParagraph(tc, (i + 1) + ". " + step.getDescriptions().get(i), false, 0, null, INDENT_LEVEL_1);
                }
            }
        }
    }

    // ============================================================
    // 저수준 유틸리티
    // ============================================================

    /** paragraph 추가 (들여쓰기 없음) */
    private void addCellParagraph(CTTc tc, String text, boolean bold, int fontSize, ParagraphAlignment alignment) {
        addCellParagraph(tc, text, bold, fontSize, alignment, 0);
    }

    /** paragraph 추가 (들여쓰기 + 줄간격 + 간격 지원) */
    private void addCellParagraph(CTTc tc, String text, boolean bold, int fontSize, ParagraphAlignment alignment, int indentTwip) {
        CTP ctp = tc.addNewP();
        CTPPr pPr = ctp.addNewPPr();
        // 정렬
        if (alignment != null) {
            pPr.addNewJc().setVal(STJc.Enum.forInt(alignment.getValue()));
        }
        // 들여쓰기
        if (indentTwip > 0) {
            pPr.addNewInd().setLeft(BigInteger.valueOf(indentTwip));
        }
        // 줄간격 + paragraph 간격
        setParaSpacing(pPr);
        // 텍스트
        CTR ctr = ctp.addNewR();
        setRunProperties(ctr, bold, fontSize);
        CTText ctText = ctr.addNewT();
        ctText.setStringValue(text);
        ctText.setSpace(SpaceAttribute.Space.PRESERVE);
    }

    /** "- " 접두사 들여쓰기 paragraph */
    private void addIndentedDashParagraph(CTTc tc, String text, int indentTwip) {
        CTP ctp = tc.addNewP();
        CTPPr pPr = ctp.addNewPPr();
        pPr.addNewInd().setLeft(BigInteger.valueOf(indentTwip));
        setParaSpacing(pPr);
        // "- "
        CTR dashR = ctp.addNewR();
        setRunProperties(dashR, false, 0);
        CTText dashText = dashR.addNewT();
        dashText.setStringValue("- ");
        dashText.setSpace(SpaceAttribute.Space.PRESERVE);
        // 내용
        CTR textR = ctp.addNewR();
        setRunProperties(textR, false, 0);
        CTText contentText = textR.addNewT();
        contentText.setStringValue(text);
        contentText.setSpace(SpaceAttribute.Space.PRESERVE);
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
        if (bold) {
            rPr.addNewB();
        }
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
    private void setRowHeight(CTRow ctRow, int heightTwip) {
        CTTrPr trPr = ctRow.addNewTrPr();
        CTHeight trHeight = trPr.addNewTrHeight();
        trHeight.setVal(BigInteger.valueOf(heightTwip));
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
}
