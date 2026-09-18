package com.example.excelpoc.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class GenericDataService {

    private static final Logger log = LoggerFactory.getLogger(GenericDataService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ApplicationContext applicationContext;

    @Autowired
    public GenericDataService(JdbcTemplate jdbcTemplate, ApplicationContext applicationContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.applicationContext = applicationContext;
    }

    /**
     * Streams REAL records directly from PostgreSQL / DB table.
     */
    public void streamEntityData(
            String entityName,
            String whereClause,
            String customTransformerBean,
            Map<String, Object> customParams,
            Consumer<Map<String, Object>> rowConsumer) {

        log.info("Fetching real DB data for entity table '{}'", entityName);

        // Optional Payload Transformer
        ProductCustomTransformer transformer = null;
        if (StringUtils.hasText(customTransformerBean)) {
            try {
                transformer = applicationContext.getBean(customTransformerBean, ProductCustomTransformer.class);
            } catch (Exception e) {
                log.warn("Transformer bean '{}' requested in payload was not found. Skipping transformer.", customTransformerBean);
            }
        }

        // Build dynamic SELECT query from actual table
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(entityName);
        if (StringUtils.hasText(whereClause)) {
            sql.append(" WHERE ").append(whereClause);
        }

        final ProductCustomTransformer finalTransformer = transformer;

        // Stream real DB result set row by row
        jdbcTemplate.query(sql.toString(), rs -> {
            var metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                String colName = metaData.getColumnLabel(i).toLowerCase();
                row.put(colName, rs.getObject(i));
            }

            // Apply optional export transformation if requested
            if (finalTransformer != null) {
                finalTransformer.transformForExport(row);
            }

            rowConsumer.accept(row);
        });
    }

    /**
     * Fetches real distinct dropdown values from foreign key or lookup tables.
     */
    public List<String> getDropdownOptions(String entityName, String columnName) {
        // POC Restriction: Only generate dropdown validations for the 'status' column
        if (!"status".equalsIgnoreCase(columnName)) {
            return Collections.emptyList();
        }

        try {
            String sql = String.format(
                    "SELECT DISTINCT %s FROM %s WHERE %s IS NOT NULL LIMIT 50",
                    columnName, entityName, columnName
            );
            return jdbcTemplate.queryForList(sql, String.class);
        } catch (Exception e) {
            log.debug("Failed to fetch dropdown options for status column: {}", e.getMessage());
            // Fallback hardcoded POC values if status column doesn't exist yet in DB
            return List.of("PENDING", "IN_PROGRESS", "APPROVED", "REJECTED");
        }
    }
    /**
     * Batch save changes back to database.
     */
    @Transactional
    public void saveBatchChanges(String entityName, String transformerBean, List<Map<String, Object>> changedRows) {
        if (changedRows == null || changedRows.isEmpty()) return;

        ProductCustomTransformer transformer = null;
        if (StringUtils.hasText(transformerBean)) {
            try {
                transformer = applicationContext.getBean(transformerBean, ProductCustomTransformer.class);
            } catch (Exception e) {
                log.warn("Transformer bean '{}' not found during batch save", transformerBean);
            }
        }

        for (Map<String, Object> row : changedRows) {
            if (transformer != null) {
                transformer.transformForImport(row);
            }
            saveSingleRow(entityName, row);
        }
    }

    /**
     * Single DB Upsert based on Table Column Metadata & Primary Key handling.
     */
    @Transactional
    public void saveSingleRow(String entityName, Map<String, Object> row) {
        if (row == null || row.isEmpty()) return;

        Set<String> validColumns = getTableColumns(entityName);
        String primaryKeyColumn = "id";

        // Filter out non-DB columns
        Map<String, Object> dbRow = new HashMap<>(row);
        dbRow.keySet().removeIf(key -> !validColumns.contains(key.toLowerCase()));

        Object pkVal = dbRow.get(primaryKeyColumn);

        if (isNewRow(pkVal)) {
            // INSERT: Exclude ID so DB sequence generates key automatically
            dbRow.remove(primaryKeyColumn);
            insertSingleRow(entityName, dbRow);
        } else {
            // UPDATE: Modify existing row
            updateSingleRow(entityName, primaryKeyColumn, pkVal, dbRow);
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