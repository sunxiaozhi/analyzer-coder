package com.analyzercoder.infrastructure.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.sql.Array;
import java.sql.SQLException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JdbcJsonConfiguration {
    @Bean
    Module jdbcArrayJsonModule() {
        SimpleModule module = new SimpleModule("jdbc-array-json");
        module.addSerializer(Array.class, new JsonSerializer<>() {
            @Override
            public void serialize(Array value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
                try { generator.writeObject(value.getArray()); }
                catch (SQLException exception) { throw new IOException("Cannot serialize JDBC array", exception); }
            }
        });
        return module;
    }
}
