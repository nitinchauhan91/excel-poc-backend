package com.example.excelpoc.controller;

import com.example.excelpoc.dto.DataDownloadRequest;
import com.example.excelpoc.service.GenericDataService;
import com.example.excelpoc.service.ProductCustomTransformer;
import com.example.excelpoc.util.ExcelBridgeUtil;
import org.apache.poi.ss.usermodel.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/generic")
@CrossOrigin(
        origins = {"https://localhost:4200", "http://localhost:4200"},
        allowedHeaders = "*",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS},
        allowCredentials = "true"
)
public class GenericDataController {

    private static final Logger log = LoggerFactory.getLogger(GenericDataController.class);

    private final GenericDataService dataService;
    private final ApplicationContext applicationContext;

    @Autowired
    public GenericDataController(GenericDataService dataService, ApplicationContext applicationContext) {
        this.dataService = dataService;
        this.applicationContext = applicationContext;
    }

    @PostMapping("/download")
    public ResponseEntity<Map<String, String>> downloadEntityData(@RequestBody DataDownloadRequest request) throws Exception {
        log.info("Received download request for entity '{}'", request.entityName());

        List<String> headers = new ArrayList<>();
        Map<String, List<String>> dropdowns = new HashMap<>();
        List<Map<String, Object>> bufferedRows = new ArrayList<>();

        // Fetch dataset from DB & populate dynamic headers/dropdowns
        dataService.streamEntityData(
                request.entityName(),
                request.whereClause(),
                request.customTransformerBean(),
                request.customParams(),
                row -> {
                    if (headers.isEmpty()) {
                        headers.addAll(row.keySet());
                        headers.forEach(h -> {
                            List<String> options = dataService.getDropdownOptions(request.entityName(), h);
                            if (!options.isEmpty()) dropdowns.put(h, options);
                        });
                    }
                    bufferedRows.add(row);
                }
        );

        String sanitizedSheetName = request.entityName().replaceAll("[^a-zA-Z0-9_]", "_");

        // Build Base64 payload in RAM
        Map<String, String> result = ExcelBridgeUtil.createBase64Workbook(
                sanitizedSheetName,
                headers,
                dropdowns,
                sheet -> {
                    AtomicInteger rowIdx = new AtomicInteger(1);
                    for (Map<String, Object> dataRow : bufferedRows) {
                        Row excelRow = sheet.createRow(rowIdx.getAndIncrement());
                        for (int c = 0; c < headers.size(); c++) {
                            Object val = dataRow.get(headers.get(c));
                            if (val != null) {
                                if (val instanceof Number n) {
                                    excelRow.createCell(c).setCellValue(n.doubleValue());
                                } else {
                                    excelRow.createCell(c).setCellValue(val.toString());
                                }
                            }
                        }
                    }
                }
        );

        return ResponseEntity.ok(result);
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadEntityData(
            @RequestParam String entityName,
            @RequestParam(required = false) String transformerBean,
            @RequestBody List<Map<String, Object>> changedRows
    ) {
        log.info("Standard upload triggered for entity '{}' with {} rows", entityName, changedRows.size());
        dataService.saveBatchChanges(entityName, transformerBean, changedRows);
        return ResponseEntity.ok(Map.of("success", true, "updatedCount", changedRows.size()));
    }

    @PostMapping("/upload-stream")
    public ResponseEntity<List<Map<String, Object>>> uploadStream(
            @RequestParam String entityName,
            @RequestParam(required = false) String transformerBean,
            @RequestBody List<Map<String, Object>> chunk) {

        log.info("Processing upload batch stream for entity '{}'. Payload transformer: '{}'",
                entityName, (transformerBean != null ? transformerBean : "NONE"));

        // Optional Payload-Driven Transformer Execution
        ProductCustomTransformer transformer = null;
        if (StringUtils.hasText(transformerBean)) {
            try {
                transformer = applicationContext.getBean(transformerBean, ProductCustomTransformer.class);
            } catch (Exception e) {
                log.warn("Transformer bean '{}' requested in payload was not found. Skipping transformation.", transformerBean);
            }
        }

        List<Map<String, Object>> processedChunk = new ArrayList<>(chunk.size());

        for (Map<String, Object> row : chunk) {
            if (transformer != null) {
                transformer.transformForImport(row);
            }

            dataService.saveSingleRow(entityName, row);
            processedChunk.add(row);
        }

        return ResponseEntity.ok(processedChunk);
    }
}