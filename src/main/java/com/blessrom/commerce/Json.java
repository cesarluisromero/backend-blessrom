package com.blessrom.commerce;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
@ApplicationScoped public class Json {
    @Inject ObjectMapper mapper;
    public String write(Object value) {
        try { return mapper.writeValueAsString(value); } catch(JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    public <T> T read(Object value,Class<T> type) {
        try { return mapper.readValue(value.toString(),type); } catch(JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}

