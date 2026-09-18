package com.example.excelpoc;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ExcelPocController {

    private static final Logger log = LoggerFactory.getLogger(ExcelPocController.class);
    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir");
    private static final int TOTAL_ROWS = 600_000;
    private static final int BATCH_SIZE = 60_000; // 10 parallel threads x 60k rows

    @PostMapping("/generate-file")
    public ResponseEntity<Map<String, Object>> generateFile() throws Exception {
        long startTime = System.currentTimeMillis();
        String fileId = UUID.randomUUID().toString();
        File tempFile = new File(TEMP_DIR, "export_" + fileId + ".xlsx");

        log.info("Starting Excel generation for {} rows. Temporary file target: {}", TOTAL_ROWS, tempFile.getAbsolutePath());

        // 1. Parallel Data Construction (10 worker threads)
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Future<List<Object[]>>> futures = new ArrayList<>();

        for (int i = 0; i < TOTAL_ROWS; i += BATCH_SIZE) {
            final int startId = i + 1;
            final int endId = Math.min(i + BATCH_SIZE, TOTAL_ROWS);

            futures.add(executor.submit(() -> {
                List<Object[]> batch = new ArrayList<>(endId - startId + 1);
                for (int id = startId; id <= endId; id++) {
                    // ID, Product Name, Quantity, Unit Price (Column E/Total left empty for frontend formula injection)
                    batch.add(new Object[]{ id, "Product_" + id, id * 2, 25.50 });
                }
                return batch;
            }));
        }

        // 2. Write Raw Values to SXSSFWorkbook (Low Memory - 100 row RAM window)
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100);
             FileOutputStream fos = new FileOutputStream(tempFile)) {

            Sheet sheet = workbook.createSheet("ProductData");

            // Header Row
            Row headerRow = sheet.createRow(0);
            String[] headers = {"ID", "Product Name", "Quantity", "Price", "Total"};
            for (int h = 0; h < headers.length; h++) {
                headerRow.createCell(h).setCellValue(headers[h]);
            }

            int currentExcelRow = 1;

            // Stream thread batches sequentially into POI disk buffer
            for (Future<List<Object[]>> future : futures) {
                List<Object[]> batchData = future.get();
                for (Object[] record : batchData) {
                    Row row = sheet.createRow(currentExcelRow);
                    row.createCell(0).setCellValue((Integer) record[0]);
                    row.createCell(1).setCellValue((String) record[1]);
                    row.createCell(2).setCellValue((Integer) record[2]);
                    row.createCell(3).setCellValue((Double) record[3]);
                    // Column index 4 (E) intentionally left empty
                    currentExcelRow++;
                }
            }

            workbook.write(fos);
            workbook.dispose(); // Delete temporary XML fragments
        } finally {
            executor.shutdown();
        }

        long elapsedTimeMs = System.currentTimeMillis() - startTime;
        log.info("SUCCESS: 600k raw rows generated in {} seconds. File size: {} MB. Stored at: {}",
                String.format("%.2f", elapsedTimeMs / 1000.0),
                String.format("%.2f", tempFile.length() / (1024.0 * 1024.0)),
                tempFile.getAbsolutePath());

        // 3. Define Column Formulas Metadata (Java controls the business logic formulas)
        Map<String, String> columnFormulas = new HashMap<>();
        columnFormulas.put("E", "=C2*D2"); // Column E gets formula patterns evaluated starting from row 2

        Map<String, Object> response = new HashMap<>();
        response.put("fileId", fileId);
        response.put("totalRows", TOTAL_ROWS);
        response.put("columnFormulas", columnFormulas);
        response.put("fileSizeBytes", tempFile.length());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/download-file/{fileId}")
    public ResponseEntity<StreamingResponseBody> downloadFile(@PathVariable String fileId) {
        File tempFile = new File(TEMP_DIR, "export_" + fileId + ".xlsx");
        if (!tempFile.exists()) {
            log.error("Download failed. File not found at path: {}", tempFile.getAbsolutePath());
            return ResponseEntity.notFound().build();
        }

        // Streams raw binary stream directly over TCP sockets in 128KB chunks
        StreamingResponseBody stream = outputStream -> {
            long downloadStart = System.currentTimeMillis();
            log.info("Streaming binary file payload to client from path: {}", tempFile.getAbsolutePath());

            try (InputStream inputStream = new FileInputStream(tempFile)) {
                byte[] buffer = new byte[131072]; // 128KB buffer
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                outputStream.flush();
            } finally {
                long downloadEnd = System.currentTimeMillis() - downloadStart;
                log.info("Download completed in {} seconds. Safely deleting temp file: {}",
                        String.format("%.2f", downloadEnd / 1000.0),
                        tempFile.getAbsolutePath());
                Files.deleteIfExists(tempFile.toPath());
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report.xlsx")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(tempFile.length())
                .body(stream);
    }
}