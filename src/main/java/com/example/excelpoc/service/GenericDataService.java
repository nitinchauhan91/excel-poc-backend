package com.example.excelpoc.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class GenericDataService {

    private static final Logger log = LoggerFactory.getLogger(GenericDataService.class);
    private final JdbcTemplate jdbcTemplate;
    private final ApplicationContext applicationContext;

    public GenericDataService(JdbcTemplate jdbcTemplate, ApplicationContext applicationContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.applicationContext = applicationContext;
    }

    public void streamEntityData(
            String entityName,
            String whereClause,
            String customTransformerBean,
            Map<String, Object> customParams,
            Consumer<Map<String, Object>> rowConsumer
    ) {
        String sql = "SELECT * FROM " + entityName;
        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql += " WHERE " + whereClause;
        }

        jdbcTemplate.query(sql, rs -> {
            var metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    String columnName = metaData.getColumnName(i).toLowerCase();
                    row.put(columnName, rs.getObject(i));
                }

                // 1. EXECUTE CUSTOM TRANSFORMER (COMPUTE TOTAL IN JAVA ON DOWNLOAD)
                applyCustomTransformer(customTransformerBean, row, true);

                rowConsumer.accept(row);
            }
            return null;
        });
    }

    @Transactional
    public void saveBatchChanges(String entityName, String customTransformerBean, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return;

        Set<String> validColumns = getTableColumns(entityName);
        String primaryKeyColumn = "id";

        int insertedCount = 0;
        int updatedCount = 0;

        for (Map<String, Object> row : rows) {
            // 2. EXECUTE CUSTOM TRANSFORMER (COMPUTE TOTAL IN JAVA ON UPLOAD)
            applyCustomTransformer(customTransformerBean, row, false);

            // Filter out fields not existing in database table schema (or keep total_price if column exists)
            row.keySet().removeIf(key -> !validColumns.contains(key.toLowerCase()));

            Object pkVal = row.get(primaryKeyColumn);

            if (isNewRow(pkVal)) {
                Map<String, Object> insertData = new HashMap<>(row);
                insertData.remove(primaryKeyColumn);

                insertSingleRow(entityName, insertData);
                insertedCount++;
            } else {
                updateSingleRow(entityName, primaryKeyColumn, pkVal, row);
                updatedCount++;
            }
        }

        log.info("[DB Batch Save] Entity '{}': {} inserted, {} updated.", entityName, insertedCount, updatedCount);
    }

    private void applyCustomTransformer(String beanName, Map<String, Object> row, boolean isExport) {
        if (beanName != null && !beanName.trim().isEmpty()) {
            try {
                Object transformer = applicationContext.getBean(beanName);
                if (transformer instanceof ProductCustomTransformer productTransformer) {
                    if (isExport) {
                        productTransformer.transformForExport(row);
                    } else {
                        productTransformer.transformForImport(row);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to apply custom transformer bean '{}': {}", beanName, e.getMessage());
            }
        }
    }

    private boolean isNewRow(Object pkVal) {
        if (pkVal == null) return true;
        String str = pkVal.toString().trim();
        return str.isEmpty() || str.equalsIgnoreCase("null") || str.equals("0");
    }

    private void insertSingleRow(String tableName, Map<String, Object> data) {
        if (data.isEmpty()) return;
        String columns = String.join(", ", data.keySet());
        String placeholders = data.keySet().stream().map(k -> "?").collect(Collectors.joining(", "));
        String sql = String.format("INSERT INTO %s (%s) VALUES (%s)", tableName, columns, placeholders);
        jdbcTemplate.update(sql, data.values().toArray());
    }

    private void updateSingleRow(String tableName, String pkColumn, Object pkValue, Map<String, Object> data) {
        Map<String, Object> updateFields = new HashMap<>(data);
        updateFields.remove(pkColumn);
        if (updateFields.isEmpty()) return;

        String setClause = updateFields.keySet().stream()
                .map(col -> col + " = ?")
                .collect(Collectors.joining(", "));

        List<Object> params = new ArrayList<>(updateFields.values());
        params.add(pkValue);

        String sql = String.format("UPDATE %s SET %s WHERE %s = ?", tableName, setClause, pkColumn);
        jdbcTemplate.update(sql, params.toArray());
    }

    private Set<String> getTableColumns(String tableName) {
        String sql = "SELECT column_name FROM information_schema.columns WHERE table_name = ?";
        List<String> cols = jdbcTemplate.queryForList(sql, String.class, tableName.toLowerCase());
        return cols.stream().map(String::toLowerCase).collect(Collectors.toSet());
    }
}