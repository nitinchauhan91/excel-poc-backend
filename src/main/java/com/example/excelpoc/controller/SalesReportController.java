//package com.example.excelpoc;
//
//import com.example.excelpoc.util.ExcelBridgeUtil;
//import org.apache.poi.ss.usermodel.Cell;
//import org.apache.poi.ss.usermodel.Row;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//
//import java.util.*;
//import java.util.concurrent.*;
//
//@RestController
//@RequestMapping("/api/reports")
//@CrossOrigin(origins = "*")
//public class SalesReportController {
//
//    private static final int DEFAULT_ROW_COUNT = 600_000;
//    private static final int BATCH_SIZE = 50_000;
//
//    // Restrict max parallel workbook generations in memory simultaneously to 4.
//    // Excess requests wait safely in queue for a few seconds instead of crashing JVM Heap.
//    private final Semaphore CONCURRENT_PERMITS = new Semaphore(4, true);
//
//    @PostMapping("/sales")
//    public ResponseEntity<Map<String, String>> getSalesReport(@RequestBody(required = false) Map<String, Object> payload)
//            throws Exception {
//
//        int totalRows = DEFAULT_ROW_COUNT;
//        if (payload != null && payload.containsKey("rowCount")) {
//            totalRows = Integer.parseInt(payload.get("rowCount").toString());
//        }
//
//        // Acquire permit (Wait max 30s if server is busy under heavy concurrency)
//        boolean acquired = CONCURRENT_PERMITS.tryAcquire(30, TimeUnit.SECONDS);
//        if (!acquired) {
//            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
//                    .body(Map.of("error", "Server is busy. Please try again in a moment."));
//        }
//
//        long startTime = System.currentTimeMillis();
//        System.out.printf("[Backend Log] Starting streaming generation of %d rows...\n", totalRows);
//
//        ExecutorService executor = Executors.newFixedThreadPool(4);
//
//        try {
//            String sourceSheetName = "Sales_Data";
//            String[] headers = {"ID", "Product Name", "Quantity", "Unit Price", "Total Price"};
//
//            // Producer-Consumer Queue: Capped at 2 batches so RAM never holds more than 100k rows at once
//            BlockingQueue<List<Object[]>> batchQueue = new ArrayBlockingQueue<>(2);
//
//            final int finalTotalRows = totalRows;
//
//            // Background Producer Thread: Generates data in batches and pushes to queue
//            executor.submit(() -> {
//                try {
//                    for (int i = 0; i < finalTotalRows; i += BATCH_SIZE) {
//                        int startId = i + 1;
//                        int endId = Math.min(i + BATCH_SIZE, finalTotalRows);
//
//                        List<Object[]> batch = new ArrayList<>(endId - startId + 1);
//                        for (int id = startId; id <= endId; id++) {
//                            int quantity = (id % 10) + 1;
//                            double unitPrice = 25.50;
//                            batch.add(new Object[]{ id, "Product_" + id, quantity, unitPrice });
//                        }
//                        // Blocks if queue reaches capacity (2 batches), waiting for POI to consume
//                        batchQueue.put(batch);
//                    }
//                } catch (InterruptedException e) {
//                    Thread.currentThread().interrupt();
//                }
//            });
//
//            // Consumer & Workbook Encoder via ExcelBridgeUtil
//            Map<String, String> responsePayload = ExcelBridgeUtil.createBase64Workbook(
//                    sourceSheetName, headers, sheet -> {
//                        int currentExcelRow = 1;
//                        int processedRows = 0;
//
//                        while (processedRows < finalTotalRows) {
//                            try {
//                                // Take batch from queue as soon as it's ready
//                                List<Object[]> batchData = batchQueue.poll(30, TimeUnit.SECONDS);
//                                if (batchData == null) break;
//
//                                for (Object[] record : batchData) {
//                                    Row row = sheet.createRow(currentExcelRow);
//
//                                    int id = (Integer) record[0];
//                                    String name = (String) record[1];
//                                    int quantity = (Integer) record[2];
//                                    double unitPrice = (Double) record[3];
//                                    double calculatedTotal = quantity * unitPrice;
//
//                                    row.createCell(0).setCellValue(id);
//                                    row.createCell(1).setCellValue(name);
//                                    row.createCell(2).setCellValue(quantity);
//                                    row.createCell(3).setCellValue(unitPrice);
//
//                                    // Dual write: Cached calculated value + dynamic formula
//                                    Cell totalCell = row.createCell(4);
//                                    totalCell.setCellValue(calculatedTotal);
//
//                                    int excelRowNum = currentExcelRow + 1;
//                                    totalCell.setCellFormula("C" + excelRowNum + "*D" + excelRowNum);
//
//                                    currentExcelRow++;
//                                }
//
//                                processedRows += batchData.size();
//                                // Garbage collector can now instantly reclaim processed batchData memory!
//
//                            } catch (InterruptedException e) {
//                                Thread.currentThread().interrupt();
//                                break;
//                            }
//                        }
//                    }
//            );
//
//            long elapsedTimeMs = System.currentTimeMillis() - startTime;
//            System.out.printf("[Backend Log] SUCCESS: Generated & Encoded %d rows in-memory in %.2f seconds.\n",
//                    totalRows, elapsedTimeMs / 1000.0);
//
//            return ResponseEntity.ok(responsePayload);
//
//        } finally {
//            executor.shutdown();
//            CONCURRENT_PERMITS.release(); // Release semaphore permit for next user request
//        }
//    }
//}