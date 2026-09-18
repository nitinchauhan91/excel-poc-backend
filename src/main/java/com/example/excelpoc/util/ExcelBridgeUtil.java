package com.example.excelpoc.util;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

public class ExcelBridgeUtil {

    private static final int BUFFER_SIZE = 32 * 1024 * 1024;

    public static Map<String, String> createBase64Workbook(
            String sheetName,
            List<String> headers,
            Map<String, List<String>> dropdowns,
            Consumer<Sheet> rowWriter
    ) throws IOException {

        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            Sheet sheet = workbook.createSheet(sheetName);

            // 1. Headers
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers.get(i));
            }

            // 2. Data Rows
            rowWriter.accept(sheet);

            // 3. Apply Data Validation Dropdowns
            DataValidationHelper helper = sheet.getDataValidationHelper();
            dropdowns.forEach((fieldName, options) -> {
                int colIdx = headers.indexOf(fieldName.toLowerCase());
                if (colIdx >= 0 && !options.isEmpty()) {
                    CellRangeAddressList range = new CellRangeAddressList(1, 100000, colIdx, colIdx);
                    DataValidationConstraint constraint = helper.createExplicitListConstraint(options.toArray(new String[0]));
                    DataValidation validation = helper.createValidation(constraint, range);
                    sheet.addValidationData(validation);
                }
            });

            // 4. Output Stream
            ByteArrayOutputStream bos = new ByteArrayOutputStream(BUFFER_SIZE);
            workbook.write(bos);
            workbook.dispose();

            return Map.of(
                    "base64", Base64.getEncoder().encodeToString(bos.toByteArray()),
                    "sourceSheetName", sheetName
            );
        }
    }
}