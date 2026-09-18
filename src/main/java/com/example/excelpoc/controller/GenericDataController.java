package com.example.excelpoc.controller;

import com.example.excelpoc.dto.DataDownloadRequest;
import com.example.excelpoc.service.GenericDataService;
import com.example.excelpoc.util.ExcelBridgeUtil;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/generic")
@CrossOrigin(origins = "*")
public class GenericDataController {

    private static final Logger log = LoggerFactory.getLogger(GenericDataController.class);
    private final GenericDataService dataService;

    public GenericDataController(GenericDataService dataService) {
        this.dataService = dataService;
    }

    @PostMapping("/download")
    public ResponseEntity<Map<String, String>> downloadEntityData(@RequestBody DataDownloadRequest request) throws Exception {
        long startTime = System.currentTimeMillis();

        List<String> headers = new ArrayList<>();
        Map<String, List<String>> dropdowns = new HashMap<>();
        List<Map<String, Object>> bufferedRows = new ArrayList<>();

        // 1. Fetch DB Rows (Custom transformer computes total_price in Java during stream)
        dataService.streamEntityData(
                request.entityName(),
                request.whereClause(),
                request.customTransformerBean(),
                request.customParams(),
                row -> {
                    if (headers.isEmpty()) {
                        headers.addAll(row.keySet());
                    }
                    bufferedRows.add(row);
                }
        );

        dropdowns.put("status", List.of("In Stock", "Out of Stock", "Discontinued"));
        String sanitizedSheetName = request.entityName().replaceAll("[^a-zA-Z0-9_]", "_");

        // 2. Write Java-calculated values directly to POI cells
        Map<String, String> result = ExcelBridgeUtil.createBase64Workbook(
                sanitizedSheetName,
                headers,
                dropdowns,
                sheet -> {
                    Workbook workbook = sheet.getWorkbook();
                    DataFormat format = workbook.createDataFormat();
                    CellStyle currencyStyle = workbook.createCellStyle();
                    currencyStyle.setDataFormat(format.getFormat("$#,##0.00"));

                    AtomicInteger rowIdx = new AtomicInteger(1);

                    for (Map<String, Object> dataRow : bufferedRows) {
                        int r = rowIdx.getAndIncrement();
                        Row excelRow = sheet.createRow(r);

                        for (int c = 0; c < headers.size(); c++) {
                            String header = headers.get(c);
                            Object val = dataRow.get(header);

                            if (val != null) {
                                Cell cell = excelRow.createCell(c);
                                if (val instanceof Number n) {
                                    cell.setCellValue(n.doubleValue());
                                    if (header.contains("price")) {
                                        cell.setCellStyle(currencyStyle);
                                    }
                                } else {
                                    cell.setCellValue(val.toString());
                                }
                            }
                        }
                    }

                    // Status Dropdown Validation
                    int statusCol = headers.indexOf("status");
                    if (statusCol != -1) {
                        DataValidationHelper validationHelper = sheet.getDataValidationHelper();
                        DataValidationConstraint constraint = validationHelper.createExplicitListConstraint(
                                new String[]{"In Stock", "Out of Stock", "Discontinued"}
                        );
                        CellRangeAddressList addressList = new CellRangeAddressList(1, 10000, statusCol, statusCol);
                        DataValidation validation = validationHelper.createValidation(constraint, addressList);
                        validation.setShowErrorBox(true);
                        sheet.addValidationData(validation);
                    }
                }
        );

        log.info("[Perf Log] Excel generation complete in {} ms", System.currentTimeMillis() - startTime);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadEntityData(
            @RequestParam String entityName,
            @RequestParam(required = false) String customTransformerBean,
            @RequestBody List<Map<String, Object>> changedRows
    ) {
        dataService.saveBatchChanges(entityName, customTransformerBean, changedRows);
        return ResponseEntity.ok(Map.of("success", true, "updatedCount", changedRows.size()));
    }
}