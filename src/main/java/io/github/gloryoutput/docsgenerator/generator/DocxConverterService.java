package io.github.gloryoutput.docsgenerator.generator;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.stereotype.Service;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 보고서를 .docx 파일로 변환하는 서비스
 *
 * <p>Apache POI를 사용하여 Markdown의 제목, 테이블, 목록, 볼드 텍스트를
 * Word 문서 형식으로 변환합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@Slf4j
public class DocxConverterService {
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final String FONT_NAME = "맑은 고딕";

    /**
     * Markdown 텍스트를 .docx 바이트 배열로 변환합니다.
     *
     * @param markdown Markdown 형식의 보고서 텍스트
     * @return .docx 파일의 바이트 배열
     */
    public byte[] convertToDocx(String markdown) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            String[] lines = markdown.split("\n");
            int i = 0;
            while (i < lines.length) {
                String line = lines[i].trim();
                if (line.isEmpty()) {
                    i++;
                    continue;
                }
                // 테이블 감지 (| 로 시작하는 연속 라인)
                if (line.startsWith("|")) {
                    i = processTable(document, lines, i);
                    continue;
                }
                // 제목 처리
                if (line.startsWith("###")) {
                    addHeading(document, line.substring(3).trim(), 3);
                } else if (line.startsWith("##")) {
                    addHeading(document, line.substring(2).trim(), 2);
                } else if (line.startsWith("#")) {
                    addHeading(document, line.substring(1).trim(), 1);
                }
                // 목록 항목 처리
                else if (line.startsWith("- ")) {
                    addListItem(document, line.substring(2).trim());
                }
                // 일반 텍스트
                else {
                    addParagraph(document, line);
                }
                i++;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            log.info("Markdown → docx 변환 완료 ({}bytes)", out.size());
            return out.toByteArray();
        }
    }

    /**
     * 제목을 추가합니다.
     */
    private void addHeading(XWPFDocument document, String text, int level) {
        XWPFParagraph paragraph = document.createParagraph();
        String styleId = "Heading" + level;
        paragraph.setStyle(styleId);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontFamily(FONT_NAME);
        switch (level) {
            case 1 -> run.setFontSize(20);
            case 2 -> run.setFontSize(16);
            case 3 -> run.setFontSize(13);
        }
    }

    /**
     * 볼드 패턴을 처리하여 일반 텍스트 단락을 추가합니다.
     */
    private void addParagraph(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        addFormattedText(paragraph, text);
    }

    /**
     * 목록 항목을 추가합니다 (bullet 스타일).
     */
    private void addListItem(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        // 들여쓰기 설정
        CTPPr ppr = paragraph.getCTP().addNewPPr();
        CTInd ind = ppr.addNewInd();
        ind.setLeft(BigInteger.valueOf(360));
        ind.setHanging(BigInteger.valueOf(360));
        // bullet 문자 추가
        XWPFRun bulletRun = paragraph.createRun();
        bulletRun.setText("• ");
        bulletRun.setFontFamily(FONT_NAME);
        bulletRun.setFontSize(10);
        addFormattedText(paragraph, text);
    }

    /**
     * 볼드(**text**) 패턴을 처리하여 텍스트를 추가합니다.
     */
    private void addFormattedText(XWPFParagraph paragraph, String text) {
        Matcher matcher = BOLD_PATTERN.matcher(text);
        int lastEnd = 0;
        while (matcher.find()) {
            // 볼드 앞의 일반 텍스트
            if (matcher.start() > lastEnd) {
                XWPFRun run = paragraph.createRun();
                run.setText(text.substring(lastEnd, matcher.start()));
                run.setFontFamily(FONT_NAME);
                run.setFontSize(10);
            }
            // 볼드 텍스트
            XWPFRun boldRun = paragraph.createRun();
            boldRun.setText(matcher.group(1));
            boldRun.setBold(true);
            boldRun.setFontFamily(FONT_NAME);
            boldRun.setFontSize(10);
            lastEnd = matcher.end();
        }
        // 나머지 일반 텍스트
        if (lastEnd < text.length()) {
            XWPFRun run = paragraph.createRun();
            run.setText(text.substring(lastEnd));
            run.setFontFamily(FONT_NAME);
            run.setFontSize(10);
        }
    }

    /**
     * Markdown 테이블을 Word 테이블로 변환합니다.
     *
     * @return 테이블 이후의 라인 인덱스
     */
    private int processTable(XWPFDocument document, String[] lines, int startIndex) {
        // 테이블 라인 수집
        int i = startIndex;
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        while (i < lines.length && lines[i].trim().startsWith("|")) {
            String line = lines[i].trim();
            // 구분선(|---|---|) 건너뛰기
            if (line.matches("\\|[\\s\\-:|]+\\|")) {
                i++;
                continue;
            }
            String[] cells = parseCells(line);
            if (cells.length > 0) {
                rows.add(cells);
            }
            i++;
        }
        if (rows.isEmpty()) return i;
        int colCount = rows.stream().mapToInt(r -> r.length).max().orElse(1);
        XWPFTable table = document.createTable(rows.size(), colCount);
        // 테이블 너비 설정 (페이지 너비)
        CTTblPr tblPr = table.getCTTbl().getTblPr();
        CTTblWidth tblWidth = tblPr.addNewTblW();
        tblWidth.setType(STTblWidth.DXA);
        tblWidth.setW(BigInteger.valueOf(9000));
        for (int rowIdx = 0; rowIdx < rows.size(); rowIdx++) {
            String[] cells = rows.get(rowIdx);
            XWPFTableRow row = table.getRow(rowIdx);
            for (int colIdx = 0; colIdx < colCount; colIdx++) {
                XWPFTableCell cell = row.getCell(colIdx);
                String cellText = colIdx < cells.length ? cells[colIdx].trim() : "";
                // 기존 단락 사용
                XWPFParagraph cellPara = cell.getParagraphArray(0);
                if (cellPara == null) {
                    cellPara = cell.addParagraph();
                }
                cellPara.setSpacingAfter(0);
                cellPara.setSpacingBefore(0);
                XWPFRun cellRun = cellPara.createRun();
                cellRun.setText(cellText);
                cellRun.setFontFamily(FONT_NAME);
                cellRun.setFontSize(9);
                // 헤더 행 볼드 처리
                if (rowIdx == 0) {
                    cellRun.setBold(true);
                    // 헤더 배경색
                    CTShd shd = cell.getCTTc().addNewTcPr().addNewShd();
                    shd.setVal(STShd.CLEAR);
                    shd.setFill("D9E2F3");
                }
            }
        }
        // 테이블 후 빈 줄 추가
        document.createParagraph();
        return i;
    }

    /**
     * 테이블 라인에서 셀 값을 파싱합니다.
     */
    private String[] parseCells(String line) {
        // 앞뒤 | 제거
        if (line.startsWith("|")) line = line.substring(1);
        if (line.endsWith("|")) line = line.substring(0, line.length() - 1);
        return line.split("\\|");
    }
}
