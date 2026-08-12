package com.analyzercoder.application.llm;

import com.analyzercoder.infrastructure.persistence.mapper.LlmSettingsMapper;
import com.analyzercoder.security.ApiSecurityException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理大模型供应商配置、密钥加密、连通性测试与当前生效配置切换。 */
@Service
public class LlmSettingsService {
    private static final TypeReference<List<StageView>> STAGE_LIST = new TypeReference<>() {};
    private static final List<Pattern> SENSITIVE_PATTERNS =
            List.of(
                    Pattern.compile(
                            "-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----",
                            Pattern.CASE_INSENSITIVE),
                    Pattern.compile("AKIA[0-9A-Z]{16}"),
                    Pattern.compile(
                            "(?i)(?:api[_-]?key|secret|password|token)\\s*[:=]\\s*['\\\"]?[^\\s'\\\"]{8,}"));
    private final LlmSettingsMapper mapper;
    private final LlmSecretCipher secretCipher;
    private final LlmEndpointPolicy endpointPolicy;
    private final OpenAiCompatibleClient client;
    private final ObjectMapper json;
    private final int connectivityTimeoutSeconds;
    private final int breakerFailureThreshold;
    private final ExecutorService executor =
            Executors.newFixedThreadPool(
                    2,
                    runnable -> {
                        Thread thread = new Thread(runnable, "llm-connectivity-check");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final ConcurrentMap<String, UUID> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, CheckControl> controls = new ConcurrentHashMap<>();

    public LlmSettingsService(
            LlmSettingsMapper mapper,
            LlmSecretCipher secretCipher,
            LlmEndpointPolicy endpointPolicy,
            OpenAiCompatibleClient client,
            ObjectMapper json,
            @Value("${app.llm.connectivity-timeout-seconds:15}") int connectivityTimeoutSeconds,
            @Value("${app.llm.breaker-failure-threshold:3}") int breakerFailureThreshold) {
        this.mapper = mapper;
        this.secretCipher = secretCipher;
        this.endpointPolicy = endpointPolicy;
        this.client = client;
        this.json = json;
        this.connectivityTimeoutSeconds = Math.max(5, Math.min(connectivityTimeoutSeconds, 30));
        this.breakerFailureThreshold = Math.max(1, Math.min(breakerFailureThreshold, 10));
    }

    @PostConstruct
    void recoverInterruptedChecks() {
        mapper.failInterruptedChecks();
    }

    @PreDestroy
    void shutdown() {
        controls.values().forEach(CheckControl::cancel);
        executor.shutdownNow();
    }

    public ProviderView latest() {
        Map<String, Object> row = mapper.latestConfig();
        if (row == null) {
            return emptyProviderView();
        }
        return providerView(row);
    }

    public List<ProviderView> versions() {
        return mapper.configVersions().stream().map(this::providerView).toList();
    }

    public List<ProviderView> providers() {
        return versions();
    }

    @Transactional
    public ProviderView save(UUID actorId, ProviderInput input) {
        ValidatedInput validated = validate(input);
        SecretMaterial secret = resolveSecretForSave(actorId, input, null);
        long version = mapper.nextConfigVersion();
        UUID configId = UUID.randomUUID();
        String fingerprint = fingerprint(validated, secret.digest());
        mapper.insertConfig(
                configId,
                version,
                validated.name(),
                validated.providerType(),
                validated.baseUrl(),
                validated.model(),
                validated.connectTimeoutMs(),
                validated.requestTimeoutMs(),
                validated.maxOutputTokens(),
                validated.temperature(),
                validated.streamingEnabled(),
                secret.secretVersionId(),
                fingerprint,
                actorId);
        mapper.insertRuntimeState(configId);
        return providerView(requireConfig(configId));
    }

    @Transactional
    public ProviderView update(UUID actorId, UUID configId, ProviderInput input) {
        Map<String, Object> current = requireConfig(configId);
        if (Objects.equals(configId, uuid(current, "active_config_id"))) {
            throw new ApiSecurityException(409, "LLM_ACTIVE_CONFIG_IMMUTABLE", "请先切换或停用当前模型再编辑");
        }
        ValidatedInput validated = validate(input);
        SecretMaterial secret = resolveSecretForSave(actorId, input, current);
        String fingerprint = fingerprint(validated, secret.digest());
        mapper.updateConfig(
                configId,
                validated.name(),
                validated.providerType(),
                validated.baseUrl(),
                validated.model(),
                validated.connectTimeoutMs(),
                validated.requestTimeoutMs(),
                validated.maxOutputTokens(),
                validated.temperature(),
                validated.streamingEnabled(),
                secret.secretVersionId(),
                fingerprint,
                actorId);
        mapper.resetRuntimeState(configId);
        return providerView(requireConfig(configId));
    }

    public List<VectorModelView> vectorModels() {
        return mapper.vectorModels().stream().map(this::vectorModelView).toList();
    }

    @Transactional
    public VectorModelView saveVectorModel(UUID actorId, VectorModelInput input) {
        VectorModelInput value = validateVectorModel(input);
        UUID id = UUID.randomUUID();
        UUID secretId = resolveVectorSecret(actorId, value, null);
        mapper.insertVectorModel(
                id,
                value.name(),
                value.providerType(),
                value.baseUrl(),
                value.model(),
                value.dimension(),
                value.requestTimeoutMs(),
                secretId,
                actorId);
        return vectorModelView(mapper.vectorModel(id));
    }

    @Transactional
    public VectorModelView updateVectorModel(UUID actorId, UUID id, VectorModelInput input) {
        Map<String, Object> current = mapper.vectorModel(id);
        if (current == null) {
            throw new ApiSecurityException(404, "VECTOR_MODEL_NOT_FOUND", "向量模型不存在");
        }
        if (Objects.equals(id, uuid(current, "active_config_id"))) {
            throw new ApiSecurityException(409, "VECTOR_MODEL_ACTIVE", "请先切换当前向量模型再编辑");
        }
        VectorModelInput value = validateVectorModel(input);
        UUID secretId = resolveVectorSecret(actorId, value, current);
        mapper.updateVectorModel(
                id,
                value.name(),
                value.providerType(),
                value.baseUrl(),
                value.model(),
                value.dimension(),
                value.requestTimeoutMs(),
                secretId,
                actorId);
        return vectorModelView(mapper.vectorModel(id));
    }

    @Transactional
    public VectorModelView activateVectorModel(UUID actorId, UUID id, long expectedVersion) {
        Map<String, Object> candidate = mapper.vectorModel(id);
        if (candidate == null) {
            throw new ApiSecurityException(404, "VECTOR_MODEL_NOT_FOUND", "向量模型不存在");
        }
        if ("OPENAI_COMPATIBLE".equals(string(candidate, "provider_type"))) {
            client.embed(
                    string(candidate, "base_url"),
                    string(candidate, "model"),
                    readSecret(uuid(candidate, "secret_version_id")),
                    "connection probe",
                    integer(candidate, "dimension", 64),
                    integer(candidate, "request_timeout_ms", 30000));
        }
        if (mapper.activateVectorModel(id, actorId, expectedVersion) == 0) {
            throw new ApiSecurityException(
                    409, "VECTOR_MODEL_ACTIVATION_CONFLICT", "向量模型启用状态已变化，请刷新后重试");
        }
        return vectorModelView(mapper.vectorModel(id));
    }

    public String activeVectorModelName() {
        Map<String, Object> row = mapper.activeVectorModel();
        return row == null ? "local-hash-64" : string(row, "model");
    }

    public VectorEmbedding vectorize(String input) {
        Map<String, Object> row = mapper.activeVectorModel();
        if (row == null || "LOCAL_HASH".equals(string(row, "provider_type"))) {
            return new VectorEmbedding(activeVectorModelName(), null);
        }
        String vector =
                client.embed(
                        string(row, "base_url"),
                        string(row, "model"),
                        readSecret(uuid(row, "secret_version_id")),
                        input,
                        integer(row, "dimension", 64),
                        integer(row, "request_timeout_ms", 30000));
        return new VectorEmbedding(string(row, "model"), vector);
    }

    public CheckView startCheck(UUID actorId, ConnectivityCheckRequest request) {
        ProbeCandidate candidate =
                request.configId() == null
                        ? candidateFromInput(request.candidate())
                        : candidateFromSaved(request.configId());
        String flightKey = actorId + ":" + candidate.spec().fingerprint();
        UUID existing = inFlight.get(flightKey);
        if (existing != null) {
            Map<String, Object> existingRow = mapper.check(existing);
            if (existingRow != null && isNonTerminal(string(existingRow, "status"))) {
                return checkView(existingRow);
            }
            inFlight.remove(flightKey, existing);
        }

        UUID checkId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        URI endpoint = endpointPolicy.normalize(candidate.spec().baseUrl());
        mapper.insertCheck(
                checkId,
                actorId,
                candidate.spec().id(),
                candidate.spec().fingerprint(),
                endpoint.getHost(),
                candidate.spec().model(),
                requestId);
        UUID raced = inFlight.putIfAbsent(flightKey, checkId);
        if (raced != null) {
            candidate.clearSecret();
            mapper.cancelCheck(
                    checkId,
                    stringJson(
                            List.of(
                                    new StageView(
                                            "SINGLE_FLIGHT_REUSED",
                                            "CANCELED",
                                            0,
                                            "LLM_CHECK_REUSED"))));
            return checkView(mapper.check(raced));
        }

        CheckControl control = new CheckControl();
        controls.put(checkId, control);
        Future<?> future = executor.submit(() -> runCheck(checkId, candidate, flightKey, control));
        control.future = future;
        return checkView(mapper.check(checkId));
    }

    public CheckView check(UUID actorId, UUID checkId) {
        Map<String, Object> row = requireCheck(checkId);
        UUID owner = uuid(row, "actor_id");
        if (owner != null && !owner.equals(actorId)) {
            throw new ApiSecurityException(404, "LLM_CHECK_NOT_FOUND", "连接检测不存在");
        }
        return checkView(row);
    }

    public CheckView cancelCheck(UUID actorId, UUID checkId) {
        Map<String, Object> row = requireCheck(checkId);
        UUID owner = uuid(row, "actor_id");
        if (owner != null && !owner.equals(actorId)) {
            throw new ApiSecurityException(404, "LLM_CHECK_NOT_FOUND", "连接检测不存在");
        }
        CheckControl control = controls.get(checkId);
        if (control != null) {
            control.cancel();
        }
        mapper.cancelCheck(
                checkId,
                stringJson(
                        List.of(new StageView("CANCELED", "CANCELED", 0, "LLM_CHECK_CANCELED"))));
        return checkView(requireCheck(checkId));
    }

    @Transactional
    public ProviderView activate(UUID actorId, UUID configId, ActivationRequest request) {
        Map<String, Object> config = requireConfig(configId);
        String availability = string(config, "availability");
        if (!"AVAILABLE".equals(availability)) {
            throw new ApiSecurityException(409, "LLM_CHECK_REQUIRED", "仅通过连接检测的配置可以启用");
        }
        UUID checkId = request.latestCheckId();
        if (checkId == null
                || mapper.recentAvailableCheck(
                                configId,
                                string(config, "fingerprint"),
                                checkId,
                                Instant.now().minus(Duration.ofMinutes(10)))
                        == 0) {
            throw new ApiSecurityException(409, "LLM_CHECK_EXPIRED", "连接检测已过期或配置已变化，请重新检测");
        }
        if (!Objects.equals(request.fingerprint(), string(config, "fingerprint"))) {
            throw new ApiSecurityException(409, "LLM_FINGERPRINT_MISMATCH", "配置已变化，请重新检测");
        }
        if (mapper.activate(configId, actorId, request.expectedActivationVersion()) == 0) {
            throw new ApiSecurityException(409, "LLM_ACTIVATION_CONFLICT", "模型启用状态已变化，请刷新后重试");
        }
        return providerView(requireConfig(configId));
    }

    @Transactional
    public ProviderView deactivate(UUID actorId, long expectedActivationVersion) {
        if (mapper.deactivate(actorId, expectedActivationVersion) == 0) {
            throw new ApiSecurityException(409, "LLM_ACTIVATION_CONFLICT", "模型启用状态已变化，请刷新后重试");
        }
        return latest();
    }

    public Optional<GenerationResult> generate(String prompt) {
        if (!mapper.externalModelEnabled()) {
            return Optional.empty();
        }
        if (prompt == null || prompt.length() > 24000 || containsSensitiveContent(prompt)) {
            return Optional.empty();
        }
        Map<String, Object> row = mapper.activeConfig();
        if (row == null
                || !"AVAILABLE".equals(string(row, "availability"))
                || !"CLOSED".equals(string(row, "breaker_state"))) {
            return Optional.empty();
        }
        LlmProviderSpec spec = spec(row);
        String key = readSecret(spec.secretVersionId());
        try {
            String answer = client.generate(spec, key, prompt);
            mapper.recordRuntimeSuccess(spec.id());
            return Optional.of(new GenerationResult(answer, spec.name() + "/" + spec.model()));
        } catch (LlmConnectionException exception) {
            mapper.recordRuntimeFailure(spec.id(), exception.code(), breakerFailureThreshold);
            return Optional.empty();
        }
    }

    private void runCheck(
            UUID checkId, ProbeCandidate candidate, String flightKey, CheckControl control) {
        long started = System.nanoTime();
        List<StageView> stages = new ArrayList<>();
        try {
            mapper.markCheckRunning(checkId, "VALIDATE_CONFIG", "[]");
            long deadline = started + Duration.ofSeconds(connectivityTimeoutSeconds).toNanos();
            OpenAiCompatibleClient.ProbeResult result =
                    client.probe(
                            candidate.spec(),
                            candidate.apiKey(),
                            deadline,
                            control.canceled,
                            event -> {
                                stages.add(
                                        new StageView(
                                                event.stage(),
                                                event.status(),
                                                event.durationMs(),
                                                event.errorCode()));
                                mapper.updateCheckProgress(
                                        checkId, event.stage(), stringJson(stages));
                            });
            stages.add(new StageView("CLASSIFY_AND_PERSIST", "SUCCEEDED", 0, null));
            int completed =
                    mapper.completeCheck(
                            checkId,
                            "SUCCEEDED",
                            result.availability(),
                            "CLASSIFY_AND_PERSIST",
                            stringJson(stages),
                            result.errorCode(),
                            result.errorSummary(),
                            elapsed(started),
                            result.connectDurationMs(),
                            result.firstTokenDurationMs());
            if (completed == 1 && candidate.spec().id() != null) {
                mapper.applyCheckToRuntime(
                        candidate.spec().id(), checkId, result.availability(), result.errorCode());
            }
        } catch (LlmConnectionException exception) {
            if ("LLM_CHECK_CANCELED".equals(exception.code()) || control.canceled.get()) {
                mapper.cancelCheck(checkId, stringJson(stages));
            } else {
                int completed =
                        mapper.completeCheck(
                                checkId,
                                "FAILED",
                                "UNAVAILABLE",
                                stages.isEmpty()
                                        ? "VALIDATE_CONFIG"
                                        : stages.get(stages.size() - 1).stage(),
                                stringJson(stages),
                                exception.code(),
                                safeSummary(exception.getMessage()),
                                elapsed(started),
                                null,
                                null);
                if (completed == 1 && candidate.spec().id() != null) {
                    mapper.applyCheckToRuntime(
                            candidate.spec().id(), checkId, "UNAVAILABLE", exception.code());
                }
            }
        } catch (RuntimeException exception) {
            int completed =
                    mapper.completeCheck(
                            checkId,
                            "FAILED",
                            "UNAVAILABLE",
                            "CLASSIFY_AND_PERSIST",
                            stringJson(stages),
                            "DEPENDENCY_UNEXPECTED",
                            "连接检测发生未预期错误",
                            elapsed(started),
                            null,
                            null);
            if (completed == 1 && candidate.spec().id() != null) {
                mapper.applyCheckToRuntime(
                        candidate.spec().id(), checkId, "UNAVAILABLE", "DEPENDENCY_UNEXPECTED");
            }
        } finally {
            candidate.clearSecret();
            controls.remove(checkId);
            inFlight.remove(flightKey, checkId);
        }
    }

    private ProbeCandidate candidateFromSaved(UUID configId) {
        Map<String, Object> row = requireConfig(configId);
        LlmProviderSpec spec = spec(row);
        return new ProbeCandidate(spec, readSecret(spec.secretVersionId()).toCharArray());
    }

    private ProbeCandidate candidateFromInput(ProviderInput input) {
        if (input == null) {
            throw new ApiSecurityException(400, "LLM_CONFIG_INVALID", "需要 configId 或候选配置");
        }
        ValidatedInput validated = validate(input);
        String key = input.apiKey() == null ? "" : input.apiKey();
        if ("KEEP".equalsIgnoreCase(input.secretAction())) {
            throw new ApiSecurityException(400, "LLM_CONFIG_INVALID", "未保存候选不能保留已有密钥");
        }
        String digest = key.isBlank() ? "none" : secretCipher.digest(key);
        String fingerprint = fingerprint(validated, digest);
        LlmProviderSpec spec =
                new LlmProviderSpec(
                        null,
                        0,
                        validated.name(),
                        validated.providerType(),
                        validated.baseUrl(),
                        validated.model(),
                        validated.connectTimeoutMs(),
                        validated.requestTimeoutMs(),
                        validated.maxOutputTokens(),
                        validated.temperature(),
                        validated.streamingEnabled(),
                        null,
                        fingerprint);
        return new ProbeCandidate(spec, key.toCharArray());
    }

    private SecretMaterial resolveSecretForSave(
            UUID actorId, ProviderInput input, Map<String, Object> latest) {
        String action =
                input.secretAction() == null
                        ? "KEEP"
                        : input.secretAction().trim().toUpperCase(Locale.ROOT);
        if ("KEEP".equals(action)) {
            if (latest == null || uuid(latest, "secret_version_id") == null) {
                throw new ApiSecurityException(400, "LLM_CONFIG_INVALID", "首次保存需要提供接口密钥，或明确选择清除密钥");
            }
            UUID id = uuid(latest, "secret_version_id");
            Map<String, Object> secret = mapper.secret(id);
            return new SecretMaterial(id, string(secret, "secret_digest"));
        }
        if ("CLEAR".equals(action)) {
            return new SecretMaterial(null, "none");
        }
        if (!"REPLACE".equals(action)) {
            throw new ApiSecurityException(
                    400, "LLM_CONFIG_INVALID", "secretAction 必须为 KEEP、REPLACE 或 CLEAR");
        }
        String apiKey = input.apiKey() == null ? "" : input.apiKey().trim();
        if (apiKey.isEmpty() || apiKey.length() > 5000) {
            throw new ApiSecurityException(400, "LLM_CONFIG_INVALID", "接口密钥不能为空且不得超过 5000 个字符");
        }
        LlmSecretCipher.EncryptedSecret encrypted = secretCipher.encrypt(apiKey);
        UUID secretId = UUID.randomUUID();
        mapper.insertSecret(
                secretId,
                encrypted.cipherText(),
                encrypted.iv(),
                encrypted.digest(),
                encrypted.algorithm(),
                actorId);
        return new SecretMaterial(secretId, encrypted.digest());
    }

    private ValidatedInput validate(ProviderInput input) {
        if (input == null) {
            throw new ApiSecurityException(400, "LLM_CONFIG_INVALID", "模型配置不能为空");
        }
        String name = clean(input.name(), 1, 100, "配置名称");
        String providerType =
                input.providerType() == null
                        ? "OPENAI_COMPATIBLE"
                        : input.providerType().trim().toUpperCase(Locale.ROOT);
        if (!"OPENAI_COMPATIBLE".equals(providerType)) {
            throw new ApiSecurityException(400, "LLM_CONFIG_INVALID", "当前仅支持兼容 OpenAI 协议的模型服务");
        }
        String model = clean(input.model(), 1, 200, "模型标识");
        String baseUrl;
        try {
            baseUrl = endpointPolicy.normalize(input.baseUrl()).toString();
        } catch (LlmConnectionException exception) {
            throw new ApiSecurityException(400, exception.code(), exception.getMessage());
        }
        int connectTimeout = value(input.connectTimeoutMs(), 5000, 1000, 10000, "连接超时");
        int requestTimeout = value(input.requestTimeoutMs(), 60000, 3000, 120000, "请求超时");
        int maxTokens = value(input.maxOutputTokens(), 2048, 1, 32768, "最大输出 Token");
        double temperature = input.temperature() == null ? 0.2 : input.temperature();
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2) {
            throw new ApiSecurityException(400, "LLM_CONFIG_INVALID", "生成温度必须在 0 到 2 之间");
        }
        return new ValidatedInput(
                name,
                providerType,
                baseUrl,
                model,
                connectTimeout,
                requestTimeout,
                maxTokens,
                temperature,
                input.streamingEnabled() == null || input.streamingEnabled());
    }

    private ProviderView providerView(Map<String, Object> row) {
        UUID id = uuid(row, "id");
        UUID activeId = uuid(row, "active_config_id");
        return new ProviderView(
                id,
                number(row, "config_version", 0),
                string(row, "name"),
                string(row, "provider_type"),
                string(row, "base_url"),
                string(row, "model"),
                integer(row, "connect_timeout_ms", 5000),
                integer(row, "request_timeout_ms", 60000),
                integer(row, "max_output_tokens", 2048),
                decimal(row, "temperature", 0.2),
                bool(row, "streaming_enabled"),
                uuid(row, "secret_version_id") != null,
                string(row, "fingerprint"),
                string(row, "availability"),
                id != null && id.equals(activeId),
                activeId,
                number(row, "activation_version", 0),
                uuid(row, "latest_check_id"),
                instant(row, "last_success_at"),
                instant(row, "last_failure_at"),
                string(row, "last_error_code"),
                string(row, "breaker_state"),
                instant(row, "created_at"),
                instant(row, "activated_at"));
    }

    private ProviderView emptyProviderView() {
        Map<String, Object> activation = mapper.activation();
        return new ProviderView(
                null,
                0,
                "",
                "OPENAI_COMPATIBLE",
                "",
                "",
                5000,
                60000,
                2048,
                0.2,
                true,
                false,
                null,
                "UNCONFIGURED",
                false,
                uuid(activation, "active_config_id"),
                number(activation, "activation_version", 0),
                null,
                null,
                null,
                null,
                "CLOSED",
                null,
                null);
    }

    private VectorModelInput validateVectorModel(VectorModelInput input) {
        if (input == null) {
            throw new ApiSecurityException(400, "VECTOR_MODEL_INVALID", "向量模型不能为空");
        }
        String name = clean(input.name(), 1, 100, "配置名称");
        String providerType =
                input.providerType() == null
                        ? "LOCAL_HASH"
                        : input.providerType().trim().toUpperCase(Locale.ROOT);
        if (!List.of("LOCAL_HASH", "OPENAI_COMPATIBLE").contains(providerType)) {
            throw new ApiSecurityException(400, "VECTOR_MODEL_INVALID", "不支持的向量模型协议");
        }
        String model = clean(input.model(), 1, 200, "模型标识");
        int dimension = input.dimension() == null ? 64 : input.dimension();
        if (dimension != 64) {
            throw new ApiSecurityException(
                    400, "VECTOR_DIMENSION_INCOMPATIBLE", "当前索引只兼容 64 维向量模型");
        }
        String baseUrl = null;
        if ("OPENAI_COMPATIBLE".equals(providerType)) {
            try {
                baseUrl = endpointPolicy.normalize(input.baseUrl()).toString();
            } catch (LlmConnectionException exception) {
                throw new ApiSecurityException(400, exception.code(), exception.getMessage());
            }
        }
        int timeout = value(input.requestTimeoutMs(), 30000, 3000, 120000, "请求超时");
        return new VectorModelInput(
                name,
                providerType,
                baseUrl,
                model,
                dimension,
                timeout,
                input.secretAction(),
                input.apiKey());
    }

    private UUID resolveVectorSecret(
            UUID actorId, VectorModelInput input, Map<String, Object> current) {
        if ("LOCAL_HASH".equals(input.providerType())) {
            return null;
        }
        String action =
                input.secretAction() == null
                        ? "KEEP"
                        : input.secretAction().toUpperCase(Locale.ROOT);
        if ("KEEP".equals(action)) {
            UUID existing = current == null ? null : uuid(current, "secret_version_id");
            if (existing == null) {
                throw new ApiSecurityException(400, "VECTOR_MODEL_INVALID", "外部向量模型需要 API Key");
            }
            return existing;
        }
        if (!"REPLACE".equals(action) || input.apiKey() == null || input.apiKey().isBlank()) {
            throw new ApiSecurityException(400, "VECTOR_MODEL_INVALID", "外部向量模型需要 API Key");
        }
        LlmSecretCipher.EncryptedSecret encrypted = secretCipher.encrypt(input.apiKey().trim());
        UUID secretId = UUID.randomUUID();
        mapper.insertSecret(
                secretId,
                encrypted.cipherText(),
                encrypted.iv(),
                encrypted.digest(),
                encrypted.algorithm(),
                actorId);
        return secretId;
    }

    private VectorModelView vectorModelView(Map<String, Object> row) {
        UUID id = uuid(row, "id");
        UUID activeId = uuid(row, "active_config_id");
        return new VectorModelView(
                id,
                string(row, "name"),
                string(row, "provider_type"),
                string(row, "base_url"),
                string(row, "model"),
                integer(row, "dimension", 64),
                integer(row, "request_timeout_ms", 30000),
                uuid(row, "secret_version_id") != null,
                Objects.equals(id, activeId),
                number(row, "activation_version", 0),
                instant(row, "created_at"),
                instant(row, "activated_at"));
    }

    private CheckView checkView(Map<String, Object> row) {
        return new CheckView(
                uuid(row, "id"),
                uuid(row, "config_id"),
                string(row, "fingerprint"),
                string(row, "endpoint_host"),
                string(row, "model"),
                string(row, "status"),
                string(row, "availability"),
                string(row, "current_stage"),
                stages(string(row, "stage_results")),
                string(row, "error_code"),
                string(row, "error_summary"),
                nullableLong(row, "total_duration_ms"),
                nullableLong(row, "connect_duration_ms"),
                nullableLong(row, "first_token_duration_ms"),
                uuid(row, "request_id"),
                instant(row, "started_at"),
                instant(row, "finished_at"),
                instant(row, "created_at"));
    }

    private LlmProviderSpec spec(Map<String, Object> row) {
        return new LlmProviderSpec(
                uuid(row, "id"),
                number(row, "config_version", 0),
                string(row, "name"),
                string(row, "provider_type"),
                string(row, "base_url"),
                string(row, "model"),
                integer(row, "connect_timeout_ms", 5000),
                integer(row, "request_timeout_ms", 60000),
                integer(row, "max_output_tokens", 2048),
                decimal(row, "temperature", 0.2),
                bool(row, "streaming_enabled"),
                uuid(row, "secret_version_id"),
                string(row, "fingerprint"));
    }

    private String readSecret(UUID secretId) {
        if (secretId == null) {
            return "";
        }
        Map<String, Object> secret = mapper.secret(secretId);
        if (secret == null) {
            throw new ApiSecurityException(409, "LLM_SECRET_MISSING", "模型密钥版本不存在");
        }
        return secretCipher.decrypt(string(secret, "cipher_text"), string(secret, "iv"));
    }

    private Map<String, Object> requireConfig(UUID id) {
        Map<String, Object> row = mapper.config(id);
        if (row == null) {
            throw new ApiSecurityException(404, "LLM_CONFIG_NOT_FOUND", "模型配置不存在");
        }
        return row;
    }

    private Map<String, Object> requireCheck(UUID id) {
        Map<String, Object> row = mapper.check(id);
        if (row == null) {
            throw new ApiSecurityException(404, "LLM_CHECK_NOT_FOUND", "连接检测不存在");
        }
        return row;
    }

    private String fingerprint(ValidatedInput input, String secretDigest) {
        String canonical =
                String.join(
                        "\n",
                        input.name(),
                        input.providerType(),
                        input.baseUrl(),
                        input.model(),
                        String.valueOf(input.connectTimeoutMs()),
                        String.valueOf(input.requestTimeoutMs()),
                        String.valueOf(input.maxOutputTokens()),
                        String.valueOf(input.temperature()),
                        String.valueOf(input.streamingEnabled()),
                        secretDigest);
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private List<StageView> stages(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(value, STAGE_LIST);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private String stringJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("无法保存连接检测阶段", exception);
        }
    }

    private static String clean(String value, int min, int max, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < min || normalized.length() > max) {
            throw new ApiSecurityException(400, "LLM_CONFIG_INVALID", label + "长度无效");
        }
        return normalized;
    }

    private static int value(Integer value, int defaultValue, int min, int max, String label) {
        int resolved = value == null ? defaultValue : value;
        if (resolved < min || resolved > max) {
            throw new ApiSecurityException(400, "LLM_CONFIG_INVALID", label + "超出允许范围");
        }
        return resolved;
    }

    private static String safeSummary(String value) {
        if (value == null || value.isBlank()) {
            return "连接检测失败";
        }
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private static boolean containsSensitiveContent(String value) {
        return SENSITIVE_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(value).find());
    }

    private static boolean isNonTerminal(String status) {
        return "QUEUED".equals(status) || "RUNNING".equals(status);
    }

    private static long elapsed(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private static Object value(Map<String, Object> row, String key) {
        if (row == null) {
            return null;
        }
        Object found = row.get(key);
        return found == null ? row.get(key.toUpperCase(Locale.ROOT)) : found;
    }

    private static String string(Map<String, Object> row, String key) {
        Object value = value(row, key);
        return value == null ? null : String.valueOf(value);
    }

    private static UUID uuid(Map<String, Object> row, String key) {
        Object value = value(row, key);
        if (value == null) {
            return null;
        }
        return value instanceof UUID id ? id : UUID.fromString(value.toString());
    }

    private static int integer(Map<String, Object> row, String key, int defaultValue) {
        Object value = value(row, key);
        return value == null ? defaultValue : ((Number) value).intValue();
    }

    private static long number(Map<String, Object> row, String key, long defaultValue) {
        Object value = value(row, key);
        return value == null ? defaultValue : ((Number) value).longValue();
    }

    private static Long nullableLong(Map<String, Object> row, String key) {
        Object value = value(row, key);
        return value == null ? null : ((Number) value).longValue();
    }

    private static double decimal(Map<String, Object> row, String key, double defaultValue) {
        Object value = value(row, key);
        return value == null ? defaultValue : ((Number) value).doubleValue();
    }

    private static boolean bool(Map<String, Object> row, String key) {
        Object value = value(row, key);
        return value instanceof Boolean flag
                ? flag
                : value != null && Boolean.parseBoolean(value.toString());
    }

    private static Instant instant(Map<String, Object> row, String key) {
        Object value = value(row, key);
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime offset) {
            return offset.toInstant();
        }
        return Instant.parse(value.toString());
    }

    private final class CheckControl {
        private final AtomicBoolean canceled = new AtomicBoolean(false);
        private volatile Future<?> future;

        void cancel() {
            canceled.set(true);
            Future<?> current = future;
            if (current != null) {
                current.cancel(true);
            }
        }
    }

    private record SecretMaterial(UUID secretVersionId, String digest) {}

    private record ValidatedInput(
            String name,
            String providerType,
            String baseUrl,
            String model,
            int connectTimeoutMs,
            int requestTimeoutMs,
            int maxOutputTokens,
            double temperature,
            boolean streamingEnabled) {}

    private record ProbeCandidate(LlmProviderSpec spec, char[] secret) {
        String apiKey() {
            return new String(secret);
        }

        void clearSecret() {
            java.util.Arrays.fill(secret, '\0');
        }
    }

    public record ProviderInput(
            String name,
            String providerType,
            String baseUrl,
            String model,
            Integer connectTimeoutMs,
            Integer requestTimeoutMs,
            Integer maxOutputTokens,
            Double temperature,
            Boolean streamingEnabled,
            String secretAction,
            String apiKey) {}

    public record VectorModelInput(
            String name,
            String providerType,
            String baseUrl,
            String model,
            Integer dimension,
            Integer requestTimeoutMs,
            String secretAction,
            String apiKey) {}

    public record VectorModelView(
            UUID id,
            String name,
            String providerType,
            String baseUrl,
            String model,
            int dimension,
            int requestTimeoutMs,
            boolean secretConfigured,
            boolean active,
            long activationVersion,
            Instant createdAt,
            Instant activatedAt) {}

    public record VectorEmbedding(String model, String vector) {}

    public record ConnectivityCheckRequest(UUID configId, ProviderInput candidate) {}

    public record ActivationRequest(
            UUID latestCheckId, String fingerprint, long expectedActivationVersion) {}

    public record ProviderView(
            UUID id,
            long version,
            String name,
            String providerType,
            String baseUrl,
            String model,
            int connectTimeoutMs,
            int requestTimeoutMs,
            int maxOutputTokens,
            double temperature,
            boolean streamingEnabled,
            boolean secretConfigured,
            String fingerprint,
            String availability,
            boolean active,
            UUID activeConfigId,
            long activationVersion,
            UUID latestCheckId,
            Instant lastSuccessAt,
            Instant lastFailureAt,
            String lastErrorCode,
            String breakerState,
            Instant createdAt,
            Instant activatedAt) {}

    public record StageView(String stage, String status, long durationMs, String errorCode) {}

    public record CheckView(
            UUID id,
            UUID configId,
            String fingerprint,
            String endpointHost,
            String model,
            String status,
            String availability,
            String currentStage,
            List<StageView> stages,
            String errorCode,
            String errorSummary,
            Long totalDurationMs,
            Long connectDurationMs,
            Long firstTokenDurationMs,
            UUID requestId,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt) {}

    public record GenerationResult(String answer, String provider) {}
}
