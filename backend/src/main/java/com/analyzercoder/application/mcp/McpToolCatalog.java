package com.analyzercoder.application.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** 校验本项目固定工具 schema 使用的类型、长度、枚举及集合边界。 */
@Component
public class McpToolCatalog {
    private final JsonNode tools;

    public McpToolCatalog(ObjectMapper json) throws IOException {
        try (var input = new ClassPathResource("mcp-tools.json").getInputStream()) {
            tools = json.readTree(input);
        }
    }

    public JsonNode tools() {
        return tools.deepCopy();
    }

    public void validate(String name, JsonNode arguments) {
        for (JsonNode tool : tools) {
            if (tool.path("name").asText().equals(name)) {
                validateValue(tool.path("inputSchema"), arguments, "arguments");
                return;
            }
        }
        throw new IllegalArgumentException("未知 MCP 工具");
    }

    public boolean contains(String name) {
        for (JsonNode tool : tools) if (tool.path("name").asText().equals(name)) return true;
        return false;
    }

    private static void validateValue(JsonNode schema, JsonNode value, String path) {
        if (schema.has("anyOf")) {
            for (JsonNode alternative : schema.get("anyOf")) {
                try {
                    validateValue(alternative, value, path);
                    return;
                } catch (IllegalArgumentException ignored) {
                }
            }
            throw invalid(path);
        }
        boolean correct =
                switch (schema.path("type").asText()) {
                    case "object" -> value.isObject();
                    case "array" -> value.isArray();
                    case "string" -> value.isTextual();
                    case "integer" -> value.isIntegralNumber();
                    case "number" -> value.isNumber();
                    case "boolean" -> value.isBoolean();
                    case "null" -> value.isNull();
                    default -> false;
                };
        if (!correct) throw invalid(path);
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode option : schema.get("enum")) if (option.equals(value)) found = true;
            if (!found) throw invalid(path);
        }
        if (value.isObject()) {
            for (JsonNode required : schema.path("required"))
                if (!value.has(required.asText())) throw invalid(path + "." + required.asText());
            var fields = schema.path("properties").fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (value.has(field.getKey()))
                    validateValue(
                            field.getValue(),
                            value.get(field.getKey()),
                            path + "." + field.getKey());
            }
        } else if (value.isArray()) {
            if (value.size() > schema.path("maxItems").asInt(Integer.MAX_VALUE))
                throw invalid(path);
            for (JsonNode item : value) validateValue(schema.path("items"), item, path + "[]");
        } else if (value.isTextual()) {
            String text = value.asText();
            if (text.length() < schema.path("minLength").asInt(0)
                    || text.length() > schema.path("maxLength").asInt(Integer.MAX_VALUE))
                throw invalid(path);
            if (schema.has("pattern")
                    && !java.util.regex.Pattern.compile(schema.get("pattern").asText())
                            .matcher(text)
                            .find()) throw invalid(path);
            if (schema.path("format").asText().equals("uri")) {
                try {
                    if (!URI.create(text).isAbsolute()) throw invalid(path);
                } catch (IllegalArgumentException error) {
                    throw invalid(path);
                }
            }
        } else if (value.isNumber()) {
            if (value.asDouble() < schema.path("minimum").asDouble(-Double.MAX_VALUE)
                    || value.asDouble() > schema.path("maximum").asDouble(Double.MAX_VALUE))
                throw invalid(path);
        }
    }

    private static IllegalArgumentException invalid(String path) {
        return new IllegalArgumentException(path + " 参数不符合工具约束");
    }
}
