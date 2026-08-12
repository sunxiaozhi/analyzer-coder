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

/** 集中声明JDBC JSON 映射相关组件及运行参数，保持装配逻辑与业务代码分离。 */
@Configuration
public class JdbcJsonConfiguration {
    @Bean
    Module jdbcArrayJsonModule() {
        SimpleModule module = new SimpleModule("jdbc-array-json");
        module.addSerializer(
                Array.class,
                new JsonSerializer<>() {
                    @Override
                    public void serialize(
                            Array value, JsonGenerator generator, SerializerProvider serializers)
                            throws IOException {
                        try {
                            generator.writeObject(value.getArray());
                        } catch (SQLException exception) {
                            throw new IOException("Cannot serialize JDBC array", exception);
                        }
                    }
                });
        return module;
    }
}
