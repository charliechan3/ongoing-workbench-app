package com.ongoing.workbench.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Collections;
import java.util.Map;

@Converter
public class JsonMapConverter implements AttributeConverter<Map<String, Object>, String> {
    private static final ObjectMapper OM = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(Map<String, Object> attr) {
        if (attr == null || attr.isEmpty()) return null;
        try { return OM.writeValueAsString(attr); } catch (Exception e) { return null; }
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return Collections.emptyMap();
        try { return OM.readValue(dbData, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { return Collections.emptyMap(); }
    }
}
