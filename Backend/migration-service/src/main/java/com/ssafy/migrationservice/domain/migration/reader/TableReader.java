package com.ssafy.migrationservice.domain.migration.reader;

import com.ssafy.migrationservice.domain.migration.reader.TableAssembler.RawRow;
import com.ssafy.migrationservice.global.exception.BusinessException;
import com.ssafy.migrationservice.global.exception.ErrorCode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * xlsx·csv 파일을 ParsedTable로 읽는다. xlsx는 모든 시트를 읽는다 (빈 시트·헤더 없는 시트는 뺌).
 * 수식 셀은 저장된 계산값을 값으로, 수식 문자열과 오류값(#N/A 등)은 따로 남긴다.
 */
@Component
public class TableReader {

    private static final Charset CP949 = Charset.forName("MS949");
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** 첫 번째로 읽히는 시트 (CSV는 그 자체) */
    public ParsedTable read(String fileName, byte[] content) {
        return readAll(fileName, content).get(0);
    }

    /** 시트마다 하나씩. 시트 순서대로 */
    public List<ParsedTable> readAll(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw new BusinessException(ErrorCode.FILE_EMPTY);
        }
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".xlsx")) {
                return readXlsx(content);
            }
            if (lower.endsWith(".csv")) {
                return List.of(TableAssembler.assemble(readCsv(content)));
            }
        } catch (IOException | RuntimeException e) {
            // 확장자만 바꾼 파일, 깨진 xlsx는 POI가 unchecked 예외를 던진다
            if (e instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(ErrorCode.FILE_READ_FAILED);
        }
        throw new BusinessException(ErrorCode.FILE_TYPE_NOT_SUPPORTED);
    }

    // ---------- xlsx ----------

    private List<ParsedTable> readXlsx(byte[] content) throws IOException {
        try (InputStream in = new ByteArrayInputStream(content); Workbook wb = new XSSFWorkbook(in)) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<ParsedTable> tables = new ArrayList<>();
            BusinessException noHeader = null;
            for (Sheet sheet : wb) {
                List<RawRow> raw = readSheet(sheet, formatter);
                if (raw.stream().allMatch(RawRow::isBlank)) {
                    continue;
                }
                try {
                    tables.add(TableAssembler.assemble(raw, sheet.getSheetName()));
                } catch (BusinessException e) {
                    noHeader = e;   // 표가 아닌 시트(메모 등)는 건너뛰고, 전부 그렇다면 실패
                }
            }
            if (tables.isEmpty()) {
                throw noHeader != null ? noHeader : new BusinessException(ErrorCode.FILE_NO_HEADER);
            }
            return tables;
        }
    }

    /**
     * 병합 셀: 세로로 병합된 칸은 아래 칸에도 같은 값을 채운다 (2줄 헤더의 '사번' 같은 칸).
     * 가로로만 병합된 칸은 채우지 않고 묶음 머리글로 따로 넘긴다 (채우면 묶음 머리글 행이 헤더처럼 보인다).
     */
    private List<RawRow> readSheet(Sheet sheet, DataFormatter formatter) {
        Map<Integer, Map<Integer, String>> fill = new HashMap<>();
        Map<Integer, Map<Integer, String>> groups = new HashMap<>();
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            Row top = sheet.getRow(region.getFirstRow());
            String v = top == null ? null : cellText(top.getCell(region.getFirstColumn()), formatter);
            if (v == null) {
                continue;
            }
            for (int r = region.getFirstRow(); r <= region.getLastRow(); r++) {
                for (int c = region.getFirstColumn(); c <= region.getLastColumn(); c++) {
                    if (region.getLastRow() > region.getFirstRow()) {
                        fill.computeIfAbsent(r, k -> new HashMap<>()).put(c, v);
                    } else {
                        groups.computeIfAbsent(r, k -> new HashMap<>()).put(c, v);
                    }
                }
            }
        }
        List<RawRow> rows = new ArrayList<>();
        for (Row row : sheet) {
            int r = row.getRowNum();
            Map<Integer, String> rowFill = fill.getOrDefault(r, Map.of());
            int width = Math.max(row.getLastCellNum(), rowFill.keySet().stream().mapToInt(c -> c + 1).max().orElse(0));
            List<String> cells = new ArrayList<>();
            List<String> formulas = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            for (int c = 0; c < width; c++) {
                Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String v = cellText(cell, formatter);
                cells.add(v == null ? rowFill.get(c) : v);
                formulas.add(formulaOf(cell));
                errors.add(errorOf(cell));
            }
            rows.add(new RawRow(r + 1, cells, formulas, errors, groups.getOrDefault(r, Map.of())));
        }
        return rows;
    }

    private static String formulaOf(Cell cell) {
        if (cell == null || cell.getCellType() != CellType.FORMULA) {
            return null;
        }
        try {
            return cell.getCellFormula();
        } catch (RuntimeException e) {
            return null;    // POI가 해석하지 못하는 수식: 값만 쓴다
        }
    }

    /** 저장된 값이 오류(#N/A, #REF! …)이면 그 표기 */
    private static String errorOf(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type != CellType.ERROR) {
            return null;
        }
        try {
            return FormulaError.forInt(cell.getErrorCellValue()).getString();
        } catch (RuntimeException e) {
            return "#ERROR";
        }
    }

    /** 날짜는 ISO 형식으로, 숫자는 지수·".0" 없이, 수식은 저장된 계산값으로 읽는다 */
    private String cellText(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    LocalDateTime dt = cell.getLocalDateTimeCellValue();
                    yield dt.toLocalTime().equals(LocalTime.MIDNIGHT)
                            ? dt.toLocalDate().toString() : dt.toString();
                }
                yield BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            }
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case BLANK, ERROR, _NONE -> null;
            default -> formatter.formatCellValue(cell);
        };
    }

    // ---------- csv ----------

    private List<RawRow> readCsv(byte[] content) throws IOException {
        String text = decode(content);
        CSVFormat format = CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(false).build();
        List<RawRow> rows = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            for (CSVRecord record : parser) {
                rows.add(new RawRow((int) record.getRecordNumber(), record.toList()));
            }
        }
        return rows;
    }

    /** UTF-8 BOM → UTF-8, BOM 없으면 UTF-8로 엄격하게 시도하고 실패하면 CP949 (한글 엑셀에서 저장한 CSV) */
    static String decode(byte[] content) {
        if (content.length >= 3 && Arrays.equals(Arrays.copyOf(content, 3), UTF8_BOM)) {
            return new String(content, 3, content.length - 3, StandardCharsets.UTF_8);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(content, CP949);
        }
    }
}
