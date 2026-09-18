package com.example.excelpoc.dto;

import java.util.Map;

public record DataDownloadRequest(
        String entityName,
        String whereClause,
        String customTransformerBean,
        Map<String, Object> customParams
) {}