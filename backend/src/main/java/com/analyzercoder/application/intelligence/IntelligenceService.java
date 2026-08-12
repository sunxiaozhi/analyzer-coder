package com.analyzercoder.application.intelligence;

import com.analyzercoder.application.llm.LlmSettingsService;
import com.analyzercoder.infrastructure.persistence.mapper.GraphRetrievalMapper;
import com.analyzercoder.infrastructure.persistence.mapper.IntelligenceMapper;
import com.analyzercoder.infrastructure.persistence.model.KnowledgeCardRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 编排代码问答流程：分析问题、混合检索证据、调用模型并校验最终引用。 */
@Service
public class IntelligenceService {
    private static final int DIMENSION = 64;
    private static final int MAX_CANDIDATES_PER_CHANNEL = 40;
    private static final Set<String> CARD_STATUSES =
            Set.of("DRAFT", "PUBLISHED", "NEEDS_REVIEW", "ARCHIVED");

    private final IntelligenceMapper mapper;
    private final GraphRetrievalMapper graphRetrievalMapper;
    private final KnowledgeAttachmentService attachments;
    private final MarkdownRenderingService markdown;
    private final LlmSettingsService llm;
    private final RetrievalQueryAnalyzer queryAnalyzer;
    private final RetrievalRanker ranker;
    private final AnswerCitationValidator citationValidator;
    private final ObjectMapper json;

    public IntelligenceService(
            IntelligenceMapper mapper,
            GraphRetrievalMapper graphRetrievalMapper,
            KnowledgeAttachmentService attachments,
            MarkdownRenderingService markdown,
            LlmSettingsService llm,
            RetrievalQueryAnalyzer queryAnalyzer,
            RetrievalRanker ranker,
            AnswerCitationValidator citationValidator,
            ObjectMapper json) {
        this.mapper = mapper;
        this.graphRetrievalMapper = graphRetrievalMapper;
        this.attachments = attachments;
        this.markdown = markdown;
        this.llm = llm;
        this.queryAnalyzer = queryAnalyzer;
        this.ranker = ranker;
        this.citationValidator = citationValidator;
        this.json = json;
    }

    @Transactional
    public List<SearchHit> hybridSearch(UUID repositoryId, String query, int limit) {
        RetrievalQueryAnalyzer.Query analyzed = queryAnalyzer.analyze(query);
        if (analyzed.normalized().isBlank()) {
            return List.of();
        }
        int resolvedLimit = Math.max(1, Math.min(limit, 100));
        List<RetrievalRanker.RankedCandidate> ranked =
                retrieve(repositoryId, analyzed, false, resolvedLimit);
        return ranked.stream().map(this::searchHit).toList();
    }

    @Transactional
    public List<Evidence> unifiedSearch(UUID repositoryId, String query, int limit) {
        RetrievalQueryAnalyzer.Query analyzed = queryAnalyzer.analyze(query);
        if (analyzed.normalized().isBlank()) {
            return List.of();
        }
        int resolvedLimit = Math.max(1, Math.min(limit, 20));
        return retrieve(repositoryId, analyzed, true, resolvedLimit).stream()
                .map(candidate -> evidence(repositoryId, candidate))
                .toList();
    }

    @Transactional
    public Answer ask(UUID repositoryId, UUID accountId, String question, UUID clientRequestId) {
        if (clientRequestId != null) {
            Map<String, Object> existing =
                    mapper.findConversationByRequest(repositoryId, accountId, clientRequestId);
            if (existing != null) {
                return answerSnapshot(existing);
            }
        }
        return answer(
                repositoryId,
                accountId,
                question,
                clientRequestId,
                unifiedSearch(repositoryId, question, 10));
    }

    private Answer answer(
            UUID repositoryId,
            UUID accountId,
            String question,
            UUID clientRequestId,
            List<Evidence> evidence) {
        UUID conversationId = UUID.randomUUID();
        UUID snapshotId =
                evidence.stream()
                        .map(Evidence::snapshotId)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);

        String answer;
        String provider = "deterministic-local";
        String evidenceStatus;
        String fallbackReason;
        List<IndexedEvidence> cited;

        if (evidence.isEmpty()) {
            answer = "当前仓库的代码索引和有效知识中没有找到达到相关度门槛的证据。" + "请先完成索引、发布相关知识，或使用更具体的模块名、符号名和业务术语。";
            evidenceStatus = "INSUFFICIENT";
            fallbackReason = "NO_EVIDENCE";
            cited = List.of();
        } else {
            Optional<LlmSettingsService.GenerationResult> generated =
                    llm.generate(llmPrompt(question, evidence));
            if (generated.isPresent()) {
                AnswerCitationValidator.Validation validation =
                        citationValidator.validate(generated.get().answer(), evidence.size());
                if (validation.valid()) {
                    answer = generated.get().answer();
                    provider = generated.get().provider();
                    evidenceStatus = "SUPPORTED";
                    fallbackReason = null;
                    cited =
                            validation.citedEvidence().stream()
                                    .map(
                                            index ->
                                                    new IndexedEvidence(
                                                            index, evidence.get(index - 1)))
                                    .toList();
                } else {
                    answer =
                            deterministicAnswer(evidence)
                                    + "\n\n外部模型回答因“"
                                    + validation.reason()
                                    + "”未通过引用校验，已安全降级。";
                    evidenceStatus = "MODEL_OUTPUT_REJECTED";
                    fallbackReason = "CITATION_VALIDATION_FAILED";
                    cited = indexed(evidence, Math.min(5, evidence.size()));
                }
            } else {
                answer = deterministicAnswer(evidence);
                evidenceStatus = "DEGRADED";
                fallbackReason = "MODEL_UNAVAILABLE";
                cited = indexed(evidence, Math.min(5, evidence.size()));
            }
        }

        Instant createdAt = Instant.now();
        List<Citation> citations = citations(cited);
        String title = title(question);
        Answer result =
                new Answer(
                        conversationId,
                        repositoryId,
                        title,
                        question,
                        answer,
                        snapshotId,
                        citations,
                        provider,
                        evidenceStatus,
                        fallbackReason,
                        createdAt);
        mapper.insertConversation(
                conversationId,
                repositoryId,
                accountId,
                clientRequestId,
                title,
                question,
                answer,
                snapshotId,
                provider,
                evidenceStatus,
                fallbackReason,
                writeJson(result));
        persistCitations(conversationId, citations);
        return result;
    }

    public List<HistoryRecord> history(UUID repositoryId, UUID accountId, int limit, int offset) {
        int resolvedLimit = Math.max(1, Math.min(limit, 100));
        int resolvedOffset = Math.max(0, offset);
        return mapper
                .listConversations(repositoryId, accountId, resolvedLimit, resolvedOffset)
                .stream()
                .map(this::historyRecord)
                .toList();
    }

    public Answer historyDetail(UUID repositoryId, UUID accountId, UUID conversationId) {
        Map<String, Object> row = mapper.findConversation(conversationId, repositoryId, accountId);
        if (row == null) {
            throw new IllegalArgumentException("问答记录不存在");
        }
        return answerSnapshot(row);
    }

    @Transactional
    public HistoryRecord renameHistory(
            UUID repositoryId, UUID accountId, UUID conversationId, String title) {
        String cleaned = clean(title, 1, 80, "记录标题");
        if (mapper.renameConversation(conversationId, repositoryId, accountId, cleaned) != 1) {
            throw new IllegalArgumentException("问答记录不存在");
        }
        return historyRecord(mapper.findConversation(conversationId, repositoryId, accountId));
    }

    @Transactional
    public void deleteHistory(UUID repositoryId, UUID accountId, UUID conversationId) {
        if (mapper.deleteConversation(conversationId, repositoryId, accountId) != 1) {
            throw new IllegalArgumentException("问答记录不存在");
        }
    }

    public boolean prepareRepositoryEmbeddings(UUID repositoryId) {
        try {
            rebuildGraph(repositoryId);
            ensureCodeEmbeddings(repositoryId);
            ensureKnowledgeEmbeddings(repositoryId);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private List<RetrievalRanker.RankedCandidate> retrieve(
            UUID repositoryId,
            RetrievalQueryAnalyzer.Query query,
            boolean includeKnowledge,
            int limit) {
        int candidateLimit = Math.min(MAX_CANDIDATES_PER_CHANNEL, Math.max(16, limit * 4));
        int termCount = Math.max(1, query.terms().size());
        List<RetrievalRanker.ChannelResult> channels = new ArrayList<>();

        List<Map<String, Object>> codeKeywordRows =
                mapper.searchCodeKeyword(
                        repositoryId, query.normalized(), query.terms(), termCount, candidateLimit);
        channels.add(channel("CODE_KEYWORD", 1.15, "CODE", codeKeywordRows, true));
        List<String> matchedSymbols =
                codeKeywordRows.stream()
                        .map(row -> string(row, "symbol_name"))
                        .filter(Objects::nonNull)
                        .filter(symbol -> !symbol.isBlank())
                        .distinct()
                        .limit(8)
                        .toList();
        if (!matchedSymbols.isEmpty()) {
            channels.add(
                    channel(
                            "CODE_GRAPH",
                            0.9,
                            "CODE",
                            graphRetrievalMapper.relatedCodeChunks(
                                    repositoryId, matchedSymbols, candidateLimit),
                            true));
        }
        if (includeKnowledge) {
            channels.add(
                    channel(
                            "KNOWLEDGE_KEYWORD",
                            1.2,
                            "KNOWLEDGE",
                            mapper.searchKnowledgeKeyword(
                                    repositoryId,
                                    query.normalized(),
                                    query.terms(),
                                    termCount,
                                    candidateLimit),
                            true));
        }

        try {
            rebuildGraph(repositoryId);
            ensureCodeEmbeddings(repositoryId);
            if (includeKnowledge) {
                ensureKnowledgeEmbeddings(repositoryId);
            }
            LlmSettingsService.VectorEmbedding embedding = llm.vectorize(query.normalized());
            String vector =
                    embedding.vector() == null
                            ? localVector(query.normalized())
                            : embedding.vector();
            String model = embedding.model();
            channels.add(
                    channel(
                            "CODE_SEMANTIC",
                            1.0,
                            "CODE",
                            mapper.searchCodeVector(repositoryId, vector, model, candidateLimit),
                            false));
            if (includeKnowledge) {
                channels.add(
                        channel(
                                "KNOWLEDGE_SEMANTIC",
                                1.05,
                                "KNOWLEDGE",
                                mapper.searchKnowledgeVector(
                                        repositoryId, vector, model, candidateLimit),
                                false));
            }
        } catch (RuntimeException ignored) {
            // Keyword and symbol retrieval remain available when embedding is unavailable.
        }
        return ranker.fuse(channels, limit);
    }

    private RetrievalRanker.ChannelResult channel(
            String name,
            double weight,
            String sourceType,
            List<Map<String, Object>> rows,
            boolean lexical) {
        List<RetrievalRanker.Candidate> candidates =
                rows.stream()
                        .map(
                                row ->
                                        new RetrievalRanker.Candidate(
                                                sourceType + ":" + uuid(row, "id"),
                                                sourceType,
                                                row,
                                                lexical ? decimal(row, "lexical_score") : 0,
                                                lexical ? 0 : decimal(row, "semantic_score")))
                        .toList();
        return new RetrievalRanker.ChannelResult(name, weight, candidates);
    }

    private SearchHit searchHit(RetrievalRanker.RankedCandidate candidate) {
        Map<String, Object> row = candidate.row();
        return new SearchHit(
                uuid(row, "id"),
                uuid(row, "snapshot_id"),
                string(row, "file_path"),
                string(row, "symbol_name"),
                string(row, "symbol_kind"),
                integer(row, "start_line"),
                integer(row, "end_line"),
                string(row, "content"),
                string(row, "content_hash"),
                candidate.score(),
                candidate.lexicalScore(),
                candidate.semanticScore(),
                candidate.channels());
    }

    private Evidence evidence(UUID repositoryId, RetrievalRanker.RankedCandidate candidate) {
        Map<String, Object> row = candidate.row();
        if ("KNOWLEDGE".equals(candidate.sourceType())) {
            UUID cardId = uuid(row, "id");
            int revision = integer(row, "revision");
            return new Evidence(
                    repositoryId,
                    "KNOWLEDGE",
                    null,
                    cardId,
                    null,
                    string(row, "title"),
                    "knowledge://" + cardId,
                    null,
                    string(row, "card_type"),
                    null,
                    null,
                    string(row, "content"),
                    string(row, "content_hash"),
                    candidate.score(),
                    candidate.lexicalScore(),
                    candidate.semanticScore(),
                    candidate.channels(),
                    codeReferences(repositoryId, cardId, revision));
        }
        return new Evidence(
                repositoryId,
                "CODE",
                uuid(row, "id"),
                null,
                uuid(row, "snapshot_id"),
                string(row, "symbol_name") == null
                        ? string(row, "file_path")
                        : string(row, "symbol_name"),
                string(row, "file_path"),
                string(row, "symbol_name"),
                string(row, "symbol_kind"),
                integer(row, "start_line"),
                integer(row, "end_line"),
                string(row, "content"),
                string(row, "content_hash"),
                candidate.score(),
                candidate.lexicalScore(),
                candidate.semanticScore(),
                candidate.channels(),
                List.of());
    }

    private List<Citation> citations(List<IndexedEvidence> cited) {
        List<Citation> citations = new ArrayList<>();
        for (IndexedEvidence indexed : cited) {
            Evidence item = indexed.evidence();
            UUID citationId = UUID.randomUUID();
            citations.add(
                    new Citation(
                            citationId,
                            item.repositoryId(),
                            item.sourceType(),
                            item.chunkId(),
                            item.knowledgeCardId(),
                            item.snapshotId(),
                            item.title(),
                            item.filePath(),
                            item.symbolName(),
                            item.startLine(),
                            item.endLine(),
                            item.content(),
                            indexed.index(),
                            item.score(),
                            item.lexicalScore(),
                            item.semanticScore(),
                            item.channels(),
                            item.codeReferences()));
        }
        return citations;
    }

    private void persistCitations(UUID conversationId, List<Citation> citations) {
        for (Citation citation : citations) {
            mapper.insertCitation(
                    citation.id(),
                    conversationId,
                    citation.repositoryId(),
                    citation.sourceType(),
                    citation.chunkId(),
                    citation.knowledgeCardId(),
                    citation.title(),
                    citation.filePath(),
                    citation.symbolName(),
                    citation.startLine(),
                    citation.endLine(),
                    sha256(citation.content()),
                    citation.rank(),
                    writeJson(citation));
        }
    }

    private static List<IndexedEvidence> indexed(List<Evidence> evidence, int limit) {
        List<IndexedEvidence> result = new ArrayList<>();
        for (int index = 0; index < limit; index++) {
            result.add(new IndexedEvidence(index + 1, evidence.get(index)));
        }
        return result;
    }

    private static String deterministicAnswer(List<Evidence> evidence) {
        StringBuilder answer = new StringBuilder("根据当前有效代码与团队知识，检索到以下高相关证据：");
        for (int index = 0; index < Math.min(5, evidence.size()); index++) {
            Evidence item = evidence.get(index);
            answer.append("\n")
                    .append(index + 1)
                    .append(". [S")
                    .append(index + 1)
                    .append("] [")
                    .append("CODE".equals(item.sourceType()) ? "代码" : "知识")
                    .append("] ")
                    .append(item.title());
            if (item.startLine() != null) {
                answer.append("（第 ").append(item.startLine()).append(" 行附近）");
            }
        }
        return answer.append("。\n\n当前未使用外部模型生成解释，请打开引用核对原文，并使用调用图谱验证结构关系。").toString();
    }

    private static String llmPrompt(String question, List<Evidence> evidence) {
        StringBuilder prompt =
                new StringBuilder("你是仓库知识与代码问答助手。只能依据下面带编号的证据回答；" + "不能从证据推出的内容必须明确说不知道；不要编造调用关系。")
                        .append("\n问题：")
                        .append(question)
                        .append("\n证据：");
        int remaining = 18_000;
        for (int index = 0; index < evidence.size() && remaining > 0; index++) {
            Evidence item = evidence.get(index);
            String header =
                    "\n[S"
                            + (index + 1)
                            + "]["
                            + item.sourceType()
                            + "] "
                            + item.title()
                            + (item.startLine() == null ? "" : ":" + item.startLine())
                            + "\n";
            prompt.append(header);
            remaining -= header.length();
            int length = Math.min(Math.min(item.content().length(), 2_400), Math.max(0, remaining));
            prompt.append(item.content(), 0, length);
            remaining -= length;
            for (CodeReference reference : item.codeReferences()) {
                String link = "\n关联代码：" + reference.filePath() + ":" + reference.startLine();
                if (link.length() > remaining) {
                    break;
                }
                prompt.append(link);
                remaining -= link.length();
            }
        }
        return prompt.append("\n请用中文回答；每个事实句末必须标注一个或多个 [S编号]；" + "区分团队知识和源码事实；冲突时以当前快照源码为准。")
                .toString();
    }

    private void ensureCodeEmbeddings(UUID repositoryId) {
        String model = llm.activeVectorModelName();
        for (Map<String, Object> row : mapper.missingEmbeddings(repositoryId, model)) {
            LlmSettingsService.VectorEmbedding embedding = llm.vectorize(string(row, "content"));
            String vector =
                    embedding.vector() == null
                            ? localVector(string(row, "content"))
                            : embedding.vector();
            mapper.upsertEmbedding(
                    uuid(row, "id"),
                    repositoryId,
                    embedding.model(),
                    vector,
                    string(row, "content_hash"));
        }
    }

    private void ensureKnowledgeEmbeddings(UUID repositoryId) {
        String model = llm.activeVectorModelName();
        for (Map<String, Object> row : mapper.missingKnowledgeEmbeddings(repositoryId, model)) {
            String content = string(row, "content");
            LlmSettingsService.VectorEmbedding embedding = llm.vectorize(content);
            String vector = embedding.vector() == null ? localVector(content) : embedding.vector();
            mapper.upsertKnowledgeEmbedding(
                    uuid(row, "id"),
                    repositoryId,
                    integer(row, "revision"),
                    embedding.model(),
                    vector,
                    sha256(content));
        }
    }

    @Transactional
    public GraphResult graph(UUID repositoryId, String symbol, int depth, String direction) {
        rebuildGraph(repositoryId);
        int maximumDepth = Math.max(1, Math.min(depth, 5));
        List<GraphEdge> all =
                mapper.graphEdges(repositoryId).stream()
                        .map(
                                row ->
                                        new GraphEdge(
                                                string(row, "source_symbol"),
                                                string(row, "target_symbol"),
                                                string(row, "relation")))
                        .toList();
        Map<String, Integer> distances = new LinkedHashMap<>();
        distances.put(symbol, 0);
        List<GraphEdge> edges = new ArrayList<>();
        for (int currentDepth = 0; currentDepth < maximumDepth; currentDepth++) {
            for (GraphEdge edge : all) {
                Integer from = distances.get(edge.source());
                Integer to = distances.get(edge.target());
                if (from != null && from == currentDepth && !"UPSTREAM".equals(direction)) {
                    distances.putIfAbsent(edge.target(), currentDepth + 1);
                    edges.add(edge);
                }
                if (to != null && to == currentDepth && !"DOWNSTREAM".equals(direction)) {
                    distances.putIfAbsent(edge.source(), currentDepth + 1);
                    edges.add(edge);
                }
            }
        }
        List<GraphNode> nodes =
                distances.entrySet().stream()
                        .map(
                                entry ->
                                        new GraphNode(
                                                entry.getKey(),
                                                entry.getValue(),
                                                entry.getKey().equals(symbol)))
                        .toList();
        List<GraphEdge> uniqueEdges = edges.stream().distinct().toList();
        return new GraphResult(
                nodes,
                uniqueEdges,
                uniqueEdges.size() > 20 ? "HIGH" : uniqueEdges.size() > 5 ? "MEDIUM" : "LOW",
                List.of("静态关系不包含运行时反射与动态分派", "结果绑定当前已发布快照"));
    }

    public GraphTarget graphTarget(UUID repositoryId, UUID chunkId) {
        Map<String, Object> row = mapper.findChunk(repositoryId, chunkId);
        if (row == null) {
            throw new IllegalArgumentException("代码片段不存在");
        }
        String symbol = string(row, "symbol_name");
        if (symbol == null || symbol.isBlank()) {
            symbol = inferSymbol(string(row, "content"), string(row, "file_path"));
        }
        return new GraphTarget(symbol, string(row, "file_path"), integer(row, "start_line"));
    }

    public List<KnowledgeCard> cards(UUID repositoryId, boolean includeDraft) {
        return mapper.cards(repositoryId, includeDraft).stream().map(this::card).toList();
    }

    @Transactional
    public KnowledgeCard createCard(UUID repositoryId, UUID actor, CardInput input) {
        CardInput validated = validateCardInput(input);
        UUID id = UUID.randomUUID();
        mapper.insertCard(
                id,
                repositoryId,
                actor,
                validated.title(),
                validated.cardType(),
                validated.content(),
                validated.tags().toArray(String[]::new),
                validated.status());
        KnowledgeCard card = findCard(repositoryId, id);
        attachments.attach(repositoryId, id, card.revision(), validated.attachmentIds());
        attachCodeReferences(repositoryId, id, card.revision(), validated.codeReferences());
        if ("PUBLISHED".equals(validated.status())) {
            prepareRepositoryEmbeddings(repositoryId);
        }
        return findCard(repositoryId, id);
    }

    @Transactional
    public KnowledgeCard updateCard(UUID repositoryId, UUID id, UUID actor, CardInput input) {
        CardInput validated = validateCardInput(input);
        if (mapper.updateCard(
                        id,
                        repositoryId,
                        actor,
                        validated.title(),
                        validated.cardType(),
                        validated.content(),
                        validated.tags().toArray(String[]::new),
                        validated.status())
                == 0) {
            throw new IllegalArgumentException("知识卡片不存在");
        }
        KnowledgeCard card = findCard(repositoryId, id);
        attachments.attach(repositoryId, id, card.revision(), validated.attachmentIds());
        attachCodeReferences(repositoryId, id, card.revision(), validated.codeReferences());
        if ("PUBLISHED".equals(validated.status())) {
            prepareRepositoryEmbeddings(repositoryId);
        }
        return findCard(repositoryId, id);
    }

    public Map<String, String> settings() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map<String, Object> row : mapper.settings()) {
            result.put(string(row, "setting_key"), string(row, "value"));
        }
        return result;
    }

    @Transactional
    public Map<String, String> saveSettings(UUID actor, Map<String, String> values) {
        values.forEach((key, value) -> mapper.upsertSetting(key, value, actor));
        return settings();
    }

    private KnowledgeCard findCard(UUID repositoryId, UUID id) {
        return cards(repositoryId, true).stream()
                .filter(card -> card.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static CardInput validateCardInput(CardInput input) {
        if (input == null) {
            throw new IllegalArgumentException("知识卡片不能为空");
        }
        String title = clean(input.title(), 1, 200, "知识标题");
        String cardType = clean(input.cardType(), 1, 40, "知识类型");
        String content = clean(input.content(), 1, 100_000, "知识正文");
        String status =
                input.status() == null ? "DRAFT" : input.status().trim().toUpperCase(Locale.ROOT);
        if (!CARD_STATUSES.contains(status)) {
            throw new IllegalArgumentException("知识状态无效");
        }
        List<String> tags =
                input.tags().stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .distinct()
                        .limit(20)
                        .toList();
        return new CardInput(
                title,
                cardType,
                content,
                tags,
                status,
                input.attachmentIds(),
                input.codeReferences());
    }

    private static String clean(String value, int minimum, int maximum, String label) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.length() < minimum || cleaned.length() > maximum) {
            throw new IllegalArgumentException(label + "长度无效");
        }
        return cleaned;
    }

    private void attachCodeReferences(
            UUID repositoryId, UUID cardId, int revision, List<CodeReferenceInput> references) {
        List<UUID> ids =
                references.stream()
                        .map(CodeReferenceInput::chunkId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .limit(30)
                        .toList();
        int position = 0;
        for (UUID chunkId : ids) {
            Map<String, Object> row = mapper.findChunk(repositoryId, chunkId);
            if (row == null) {
                throw new IllegalArgumentException("关联代码不存在或不属于当前仓库");
            }
            mapper.insertCodeReference(
                    cardId,
                    revision,
                    position++,
                    repositoryId,
                    uuid(row, "snapshot_id"),
                    chunkId,
                    string(row, "file_path"),
                    string(row, "symbol_name"),
                    integer(row, "start_line"),
                    integer(row, "end_line"),
                    string(row, "content_hash"));
        }
    }

    private List<CodeReference> codeReferences(UUID repositoryId, UUID cardId, int revision) {
        return mapper.codeReferences(repositoryId, cardId, revision).stream()
                .map(
                        row ->
                                new CodeReference(
                                        repositoryId,
                                        uuid(row, "chunk_id"),
                                        uuid(row, "snapshot_id"),
                                        string(row, "file_path"),
                                        string(row, "symbol_name"),
                                        integer(row, "start_line"),
                                        integer(row, "end_line"),
                                        string(row, "content_hash"),
                                        bool(row, "stale")))
                .toList();
    }

    private void rebuildGraph(UUID repositoryId) {
        mapper.deleteGraphEdges(repositoryId);
        List<Map<String, Object>> chunks = mapper.graphChunks(repositoryId);
        Map<String, Map<String, Object>> symbols = new LinkedHashMap<>();
        for (Map<String, Object> chunk : chunks) {
            String symbol = string(chunk, "symbol_name");
            if (symbol != null && !symbol.isBlank()) {
                symbols.putIfAbsent(symbol, chunk);
            }
        }
        for (Map<String, Object> source : chunks) {
            String content = string(source, "content");
            for (Map.Entry<String, Map<String, Object>> target : symbols.entrySet()) {
                UUID sourceId = uuid(source, "id");
                UUID targetId = uuid(target.getValue(), "id");
                if (sourceId.equals(targetId) || !content.contains(target.getKey() + "(")) {
                    continue;
                }
                mapper.insertGraphEdge(
                        UUID.randomUUID(),
                        repositoryId,
                        uuid(source, "snapshot_id"),
                        sourceId,
                        targetId,
                        string(source, "symbol_name"),
                        target.getKey());
            }
        }
    }

    private static String inferSymbol(String content, String filePath) {
        if (content != null) {
            Matcher declaration =
                    Pattern.compile(
                                    "(?m)\\b(?:class|interface|record|enum|function|def|func)\\s+([A-Za-z_$][\\w$]*)")
                            .matcher(content);
            if (declaration.find()) {
                return declaration.group(1);
            }
            Matcher callable =
                    Pattern.compile(
                                    "(?m)\\b(?:public|protected|private|static|final|async|export)\\s+"
                                            + "(?:[\\w<>\\[\\],.?]+\\s+)?([A-Za-z_$][\\w$]*)\\s*\\(")
                            .matcher(content);
            if (callable.find()) {
                return callable.group(1);
            }
        }
        String name = filePath == null ? "unknown" : filePath.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        int extension = name.lastIndexOf('.');
        return extension > 0 ? name.substring(0, extension) : name;
    }

    private KnowledgeCard card(KnowledgeCardRow row) {
        return new KnowledgeCard(
                row.id(),
                row.repositoryId(),
                row.title(),
                row.cardType(),
                row.content(),
                markdown.render(row.repositoryId(), row.content()),
                List.of(row.tags()),
                row.status(),
                row.revision(),
                row.createdAt(),
                row.updatedAt(),
                row.verifiedCommit(),
                row.codeReviewStatus(),
                row.codeReviewedAt(),
                attachments.list(row.repositoryId(), row.id(), row.revision()),
                codeReferences(row.repositoryId(), row.id(), row.revision()));
    }

    private static Object value(Map<String, Object> row, String key) {
        Object result = row.get(key);
        if (result == null) {
            result = row.get(key.toUpperCase(Locale.ROOT));
        }
        return result;
    }

    private static String string(Map<String, Object> row, String key) {
        Object result = value(row, key);
        return result == null ? null : String.valueOf(result);
    }

    private static UUID uuid(Map<String, Object> row, String key) {
        Object result = value(row, key);
        if (result == null) {
            return null;
        }
        return result instanceof UUID id ? id : UUID.fromString(result.toString());
    }

    private static Integer integer(Map<String, Object> row, String key) {
        Object result = value(row, key);
        return result == null ? null : ((Number) result).intValue();
    }

    private static double decimal(Map<String, Object> row, String key) {
        Object result = value(row, key);
        return result == null ? 0 : ((Number) result).doubleValue();
    }

    private static boolean bool(Map<String, Object> row, String key) {
        Object result = value(row, key);
        return result instanceof Boolean booleanValue
                ? booleanValue
                : Boolean.parseBoolean(String.valueOf(result));
    }

    private Answer answerSnapshot(Map<String, Object> row) {
        String payload = string(row, "answer_payload");
        if (payload == null || payload.isBlank()) {
            return new Answer(
                    uuid(row, "id"),
                    uuid(row, "repo_id"),
                    string(row, "title"),
                    string(row, "question"),
                    string(row, "answer"),
                    uuid(row, "snapshot_id"),
                    List.of(),
                    string(row, "provider"),
                    string(row, "evidence_status"),
                    string(row, "fallback_reason"),
                    instant(row, "created_at"));
        }
        try {
            return json.readValue(payload, Answer.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法恢复问答记录", exception);
        }
    }

    private HistoryRecord historyRecord(Map<String, Object> row) {
        return new HistoryRecord(
                uuid(row, "id"),
                uuid(row, "repo_id"),
                string(row, "title"),
                string(row, "question"),
                string(row, "provider"),
                string(row, "evidence_status"),
                string(row, "fallback_reason"),
                integer(row, "citation_count") == null ? 0 : integer(row, "citation_count"),
                instant(row, "created_at"),
                instant(row, "updated_at"));
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存问答记录", exception);
        }
    }

    private static Instant instant(Map<String, Object> row, String key) {
        Object result = value(row, key);
        if (result == null) {
            return null;
        }
        if (result instanceof Instant instant) {
            return instant;
        }
        if (result instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (result instanceof java.time.OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        return Instant.parse(result.toString());
    }

    private static String title(String question) {
        String normalized = question == null ? "" : question.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            return "未命名问题";
        }
        int end =
                normalized.offsetByCodePoints(
                        0, Math.min(30, normalized.codePointCount(0, normalized.length())));
        return normalized.substring(0, end);
    }

    private static String localVector(String text) {
        float[] output = new float[DIMENSION];
        String normalized = text.toLowerCase(Locale.ROOT);
        for (int index = 0; index < normalized.length(); index++) {
            int hash =
                    normalized
                            .substring(index, Math.min(normalized.length(), index + 3))
                            .hashCode();
            output[Math.floorMod(hash, DIMENSION)] += (hash & 1) == 0 ? 1 : -1;
        }
        double norm = 0;
        for (float number : output) {
            norm += number * number;
        }
        norm = Math.sqrt(norm);
        StringBuilder vector = new StringBuilder("[");
        for (int index = 0; index < DIMENSION; index++) {
            if (index > 0) {
                vector.append(',');
            }
            vector.append(norm == 0 ? 0 : output[index] / norm);
        }
        return vector.append(']').toString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record IndexedEvidence(int index, Evidence evidence) {}

    public record SearchHit(
            UUID chunkId,
            UUID snapshotId,
            String filePath,
            String symbolName,
            String symbolKind,
            Integer startLine,
            Integer endLine,
            String content,
            String contentHash,
            double score,
            double lexicalScore,
            double semanticScore,
            List<String> channels) {}

    public record Evidence(
            UUID repositoryId,
            String sourceType,
            UUID chunkId,
            UUID knowledgeCardId,
            UUID snapshotId,
            String title,
            String filePath,
            String symbolName,
            String symbolKind,
            Integer startLine,
            Integer endLine,
            String content,
            String contentHash,
            double score,
            double lexicalScore,
            double semanticScore,
            List<String> channels,
            List<CodeReference> codeReferences) {}

    public record Citation(
            UUID id,
            UUID repositoryId,
            String sourceType,
            UUID chunkId,
            UUID knowledgeCardId,
            UUID snapshotId,
            String title,
            String filePath,
            String symbolName,
            Integer startLine,
            Integer endLine,
            String content,
            int rank,
            double score,
            double lexicalScore,
            double semanticScore,
            List<String> channels,
            List<CodeReference> codeReferences) {}

    public record Answer(
            UUID conversationId,
            UUID repositoryId,
            String title,
            String question,
            String answer,
            UUID snapshotId,
            List<Citation> citations,
            String provider,
            String evidenceStatus,
            String fallbackReason,
            Instant createdAt) {}

    public record HistoryRecord(
            UUID conversationId,
            UUID repositoryId,
            String title,
            String question,
            String provider,
            String evidenceStatus,
            String fallbackReason,
            int citationCount,
            Instant createdAt,
            Instant updatedAt) {}

    public record GraphTarget(String symbol, String filePath, Integer startLine) {}

    public record GraphNode(String symbol, int depth, boolean focus) {}

    public record GraphEdge(String source, String target, String relation) {}

    public record GraphResult(
            List<GraphNode> nodes, List<GraphEdge> edges, String risk, List<String> limitations) {}

    public record CodeReferenceInput(UUID chunkId) {}

    public record CodeReference(
            UUID repositoryId,
            UUID chunkId,
            UUID snapshotId,
            String filePath,
            String symbolName,
            Integer startLine,
            Integer endLine,
            String contentHash,
            boolean stale) {}

    public record CardInput(
            String title,
            String cardType,
            String content,
            List<String> tags,
            String status,
            List<UUID> attachmentIds,
            List<CodeReferenceInput> codeReferences) {
        public CardInput {
            if (tags == null) {
                tags = List.of();
            }
            if (status == null) {
                status = "DRAFT";
            }
            if (attachmentIds == null) {
                attachmentIds = List.of();
            }
            if (codeReferences == null) {
                codeReferences = List.of();
            }
        }
    }

    public record KnowledgeCard(
            UUID id,
            UUID repositoryId,
            String title,
            String cardType,
            String content,
            String renderedContent,
            List<String> tags,
            String status,
            int revision,
            Instant createdAt,
            Instant updatedAt,
            String verifiedCommit,
            String codeReviewStatus,
            Instant codeReviewedAt,
            List<KnowledgeAttachmentService.Attachment> attachments,
            List<CodeReference> codeReferences) {}
}
