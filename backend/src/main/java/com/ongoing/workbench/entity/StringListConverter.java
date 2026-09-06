package com.ongoing.workbench.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Collections;
import java.util.List;

@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {
    private static final ObjectMapper OM = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<String> attr) {
        if (attr == null || attr.isEmpty()) return null;
        try { return OM.writeValueAsString(attr); } catch (Exception e) { return null; }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return Collections.emptyList();
        try { return OM.readValue(dbData, new TypeReference<List<String>>() {}); }
        catch (Exception e) { return Collections.emptyList(); }
    }
}
