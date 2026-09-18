package com.example.excelpoc.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;



@Component("productTransformer")
public class ProductCustomTransformer {
    private static final Logger log = LoggerFactory.getLogger(ProductCustomTransformer.class);


    public void transformForExport(Map<String, Object> row) {
        log.debug("Row before export transform: {}", row);
        computeTotalPrice(row);
        log.debug("Row after export transform: {}", row);
    }

    public void transformForImport(Map<String, Object> row) {
        log.debug("Row before import transform: {}", row);
        computeTotalPrice(row);
        log.debug("Row after import transform: {}", row);
    }

    private void computeTotalPrice(Map<String, Object> row) {
        Object qtyObj = getValueIgnoreCase(row, "quantity");

        // Match unit_price first, fallback to price
        Object priceObj = getValueIgnoreCase(row, "unit_price");
        if (priceObj == null) {
            priceObj = getValueIgnoreCase(row, "price");
        }

        if (qtyObj != null && priceObj != null) {
            try {
                double qty = parseDoubleSafely(qtyObj);
                double price = parseDoubleSafely(priceObj);
                double total = qty * price;

                row.put("total_price", total);
            } catch (Exception e) {
                log.error("Failed to compute total price for row {}: {}", row, e.getMessage());
                row.put("total_price", 0.0);
            }
        } else {
            log.trace("Skipping calculation - missing quantity or unit_price in row: {}", row);
            row.put("total_price", 0.0);
        }
    }

    private Object getValueIgnoreCase(Map<String, Object> map, String targetKey) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(targetKey) ||
                    entry.getKey().replace("_", "").equalsIgnoreCase(targetKey.replace("_", ""))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private double parseDoubleSafely(Object val) {
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        String strVal = val.toString().replaceAll("[^0-9.-]", "");
        return Double.parseDouble(strVal);
    }
}