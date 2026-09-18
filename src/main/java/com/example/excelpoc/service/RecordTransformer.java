package com.example.excelpoc.service;

import java.util.Map;

@FunctionalInterface
public interface RecordTransformer {
    Map<String, Object> transform(Map<String, Object> originalRecord, Map<String, Object> params);
}