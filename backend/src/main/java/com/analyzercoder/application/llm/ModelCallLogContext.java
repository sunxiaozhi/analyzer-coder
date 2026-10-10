package com.analyzercoder.application.llm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

/** Correlates model calls with the question, background task or connectivity check. */
public final class ModelCallLogContext implements AutoCloseable {
    private static final String[] KEYS = {
        "modelTraceId", "modelRepoId", "modelBranchId", "modelTaskId", "modelCheckId", "modelStage"
    };
    private final Map<String, String> previous = new LinkedHashMap<>();

    private ModelCallLogContext(
            String traceId, UUID repoId, UUID branchId, UUID taskId, UUID checkId) {
        for (String key : KEYS) previous.put(key, MDC.get(key));
        if (traceId != null && !traceId.equals(previous.get("modelTraceId"))) {
            for (String key : KEYS) MDC.remove(key);
        }
        put(
                "modelTraceId",
                traceId == null
                        ? MDC.get("modelTraceId") == null
                                ? UUID.randomUUID().toString()
                                : MDC.get("modelTraceId")
                        : traceId);
        put("modelRepoId", repoId == null ? null : repoId.toString());
        put("modelBranchId", branchId == null ? null : branchId.toString());
        put("modelTaskId", taskId == null ? null : taskId.toString());
        put("modelCheckId", checkId == null ? null : checkId.toString());
    }

    public static ModelCallLogContext open(
            String traceId, UUID repoId, UUID branchId, UUID taskId, UUID checkId) {
        return new ModelCallLogContext(traceId, repoId, branchId, taskId, checkId);
    }

    private static void put(String key, String value) {
        if (value != null) MDC.put(key, value);
    }

    public static void stage(String stage) {
        MDC.put("modelStage", stage);
    }

    public static String traceId() {
        return value("modelTraceId");
    }

    private static String value(String key) {
        String value = MDC.get(key);
        return value == null ? "-" : value;
    }

    public static String fields() {
        return "traceId="
                + traceId()
                + ", repoId="
                + value("modelRepoId")
                + ", branchId="
                + value("modelBranchId")
                + ", taskId="
                + value("modelTaskId")
                + ", checkId="
                + value("modelCheckId")
                + ", stage="
                + value("modelStage");
    }

    @Override
    public void close() {
        for (String key : KEYS) {
            String value = previous.get(key);
            if (value == null) MDC.remove(key);
            else MDC.put(key, value);
        }
    }
}
