package com.analyzercoder.application.code;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 用轻量源码声明规则提取真实出现的符号，不推断调用关系或不存在的 AST 节点。 */
@Component
public class CodeSymbolExtractor {
    private static final int MAX_SYMBOLS_PER_FILE = 500;
    private static final Set<String> CONTROL_WORDS =
            Set.of("if", "for", "while", "switch", "catch", "return", "new", "throw");
    private static final Pattern JAVA_TYPE =
            Pattern.compile(
                    "^\\s*(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed)\\s+)*(class|interface|record|enum)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern JAVA_CALLABLE =
            Pattern.compile(
                    "^\\s*(?:(?:public|protected|private|static|final|abstract|synchronized|native|default|override|virtual|sealed|internal|async)\\s+)+(?:<[^>]+>\\s*)?(?:[\\w.$<>\\[\\],?]+\\s+)?([A-Za-z_$][\\w$]*)\\s*\\([^;]*\\)");
    private static final Pattern JAVA_PACKAGE_CALLABLE =
            Pattern.compile(
                    "^\\s*(?:<[^>]+>\\s*)?[\\w.$<>\\[\\],?]+\\s+([A-Za-z_$][\\w$]*)\\s*\\([^;]*\\)\\s*(?:throws\\s+[^\\{]+)?\\{?");
    private static final Pattern KOTLIN_TYPE =
            Pattern.compile(
                    "^\\s*(?:(?:public|private|protected|internal|open|abstract|sealed|data)\\s+)*(?:(enum)\\s+)?(class|interface|object)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern KOTLIN_FUNCTION =
            Pattern.compile(
                    "^\\s*(?:(?:public|private|protected|internal|open|override|suspend)\\s+)*fun\\s+(?:[\\w<>?.]+\\.)?([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Pattern SCRIPT_TYPE =
            Pattern.compile(
                    "^\\s*(?:(?:export|default|abstract|public|private|protected)\\s+)*(class|interface|enum|type)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern SCRIPT_FUNCTION =
            Pattern.compile(
                    "^\\s*(?:(?:export|default|async)\\s+)*function\\s+([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Pattern SCRIPT_ARROW =
            Pattern.compile(
                    "^\\s*(?:(?:export|default)\\s+)?(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|[A-Za-z_$][\\w$]*)\\s*=>");
    private static final Pattern SCRIPT_METHOD =
            Pattern.compile(
                    "^\\s*(?:(?:public|private|protected|static|async|override|get|set)\\s+)*([A-Za-z_$][\\w$]*)\\s*\\([^)]*\\)\\s*\\{");
    private static final Pattern PYTHON_DECLARATION =
            Pattern.compile("^(\\s*)(?:(async)\\s+)?(class|def)\\s+([A-Za-z_][\\w]*)");
    private static final Pattern GO_FUNCTION =
            Pattern.compile("^\\s*func\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_][\\w]*)\\s*\\(");
    private static final Pattern GO_TYPE =
            Pattern.compile("^\\s*type\\s+([A-Za-z_][\\w]*)\\s+(struct|interface)\\b");
    private static final Pattern RUST_DECLARATION =
            Pattern.compile(
                    "^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:async\\s+)?(fn|struct|enum|trait|mod)\\s+([A-Za-z_][\\w]*)");
    private static final Pattern PHP_DECLARATION =
            Pattern.compile(
                    "^\\s*(?:(?:public|protected|private|static|abstract|final)\\s+)*(class|interface|trait|function)\\s+([A-Za-z_][\\w]*)");
    private static final Pattern SWIFT_DECLARATION =
            Pattern.compile(
                    "^\\s*(?:(?:public|private|internal|open|final|static|class)\\s+)*(class|struct|protocol|enum|func)\\s+([A-Za-z_][\\w]*)");
    private static final Pattern RUBY_DECLARATION =
            Pattern.compile("^(\\s*)(class|module|def)\\s+(?:self\\.)?([A-Za-z_][\\w!?=]*)");
    private static final Pattern SHELL_FUNCTION =
            Pattern.compile("^\\s*(?:function\\s+)?([A-Za-z_][\\w]*)\\s*(?:\\(\\s*\\))?\\s*\\{");
    private static final Pattern SQL_DECLARATION =
            Pattern.compile(
                    "(?i)^\\s*CREATE\\s+(?:OR\\s+REPLACE\\s+)?(TABLE|VIEW|FUNCTION|PROCEDURE|TRIGGER)\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([\\w.\"`]+)");
    private static final Pattern GRAPHQL_DECLARATION =
            Pattern.compile("^\\s*(type|input|interface|enum|scalar|union)\\s+([A-Za-z_][\\w]*)");
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern JSON_KEY = Pattern.compile("^\\s*\"([^\"]+)\"\\s*:");
    private static final Pattern CONFIG_KEY =
            Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*(?:=|:)\\s*.*$");
    private static final Pattern TOML_SECTION = Pattern.compile("^\\s*\\[([^]]+)]\\s*$");

    public Extraction extract(String content, String filePath, String requestedLanguage) {
        String source = content == null ? "" : content;
        String language = normalizeLanguage(requestedLanguage, filePath);
        String[] lines = source.split("\\R", -1);
        List<Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            collect(candidates, language, filePath, lines[index], index + 1);
        }

        Map<String, SymbolDeclaration> unique = new LinkedHashMap<>();
        boolean truncated = false;
        for (Candidate candidate : candidates) {
            int endLine = endLine(candidate, candidates, lines);
            SymbolDeclaration declaration =
                    new SymbolDeclaration(
                            candidate.name(),
                            candidate.kind(),
                            candidate.startLine(),
                            Math.max(candidate.startLine(), endLine),
                            language);
            String key =
                    declaration.kind()
                            + "\u0000"
                            + declaration.name()
                            + "\u0000"
                            + declaration.startLine();
            unique.putIfAbsent(key, declaration);
            if (unique.size() >= MAX_SYMBOLS_PER_FILE) {
                truncated = candidates.size() > unique.size();
                break;
            }
        }
        return new Extraction(
                language,
                List.copyOf(unique.values()),
                truncated,
                truncated ? "SYMBOL_COUNT_LIMIT_EXCEEDED" : null);
    }

    public SymbolDeclaration symbolForChunk(
            Extraction extraction, int chunkStartLine, int chunkEndLine) {
        return extraction.symbols().stream()
                .filter(
                        symbol ->
                                symbol.startLine() <= chunkStartLine
                                        && symbol.endLine() >= chunkStartLine)
                .min(Comparator.comparingInt(SymbolDeclaration::lineSpan))
                .orElseGet(
                        () ->
                                extraction.symbols().stream()
                                        .filter(
                                                symbol ->
                                                        symbol.startLine() >= chunkStartLine
                                                                && symbol.startLine()
                                                                        <= chunkEndLine)
                                        .min(Comparator.comparingInt(SymbolDeclaration::startLine))
                                        .orElse(null));
    }

    public boolean generatedCode(String filePath, String content) {
        String path = filePath == null ? "" : filePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (path.matches("(^|.*/)(generated|generated-sources|gen)(/.*|$)")
                || path.endsWith(".g.dart")
                || path.endsWith(".designer.cs")) {
            return true;
        }
        String prefix =
                content == null ? "" : content.substring(0, Math.min(2_000, content.length()));
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return normalized.contains("@generated")
                || normalized.contains("code generated")
                || normalized.contains("generated file") && normalized.contains("do not edit");
    }

    private static void collect(
            List<Candidate> candidates,
            String language,
            String filePath,
            String line,
            int lineNumber) {
        switch (language) {
            case "java", "csharp" -> collectJava(candidates, line, lineNumber);
            case "kotlin" -> collectKotlin(candidates, line, lineNumber);
            case "javascript", "typescript" -> collectScript(candidates, line, lineNumber);
            case "python" -> collectPython(candidates, line, lineNumber);
            case "go" -> collectGo(candidates, line, lineNumber);
            case "rust" ->
                    collectPair(candidates, RUST_DECLARATION, line, lineNumber, RangeStyle.BRACE);
            case "php" ->
                    collectPair(candidates, PHP_DECLARATION, line, lineNumber, RangeStyle.BRACE);
            case "swift" ->
                    collectPair(candidates, SWIFT_DECLARATION, line, lineNumber, RangeStyle.BRACE);
            case "ruby" -> collectRuby(candidates, line, lineNumber);
            case "shell" ->
                    collectNamed(
                            candidates,
                            SHELL_FUNCTION,
                            "FUNCTION",
                            line,
                            lineNumber,
                            RangeStyle.BRACE);
            case "sql" ->
                    collectPair(
                            candidates, SQL_DECLARATION, line, lineNumber, RangeStyle.SINGLE_LINE);
            case "graphql" ->
                    collectPair(
                            candidates, GRAPHQL_DECLARATION, line, lineNumber, RangeStyle.BRACE);
            case "markdown" -> collectMarkdown(candidates, line, lineNumber);
            case "json", "yaml", "properties", "toml", "env" ->
                    collectConfig(candidates, language, line, lineNumber);
            default -> {
                if (isConfigPath(filePath)) {
                    collectConfig(candidates, language, line, lineNumber);
                }
            }
        }
    }

    private static void collectJava(List<Candidate> target, String line, int lineNumber) {
        Matcher type = JAVA_TYPE.matcher(line);
        if (type.find()) {
            add(
                    target,
                    type.group(2),
                    type.group(1).toUpperCase(Locale.ROOT),
                    line,
                    lineNumber,
                    RangeStyle.BRACE);
            return;
        }
        Matcher callable = JAVA_CALLABLE.matcher(line);
        if (!callable.find()) {
            callable = JAVA_PACKAGE_CALLABLE.matcher(line);
        }
        if (callable.find(0)) {
            String name = callable.group(1);
            if (!CONTROL_WORDS.contains(name)) {
                add(target, name, "METHOD", line, lineNumber, RangeStyle.BRACE);
            }
        }
    }

    private static void collectKotlin(List<Candidate> target, String line, int lineNumber) {
        Matcher type = KOTLIN_TYPE.matcher(line);
        if (type.find()) {
            String kind = type.group(1) == null ? type.group(2) : "enum";
            add(
                    target,
                    type.group(3),
                    kind.toUpperCase(Locale.ROOT),
                    line,
                    lineNumber,
                    RangeStyle.BRACE);
            return;
        }
        collectNamed(target, KOTLIN_FUNCTION, "FUNCTION", line, lineNumber, RangeStyle.BRACE);
    }

    private static void collectScript(List<Candidate> target, String line, int lineNumber) {
        Matcher type = SCRIPT_TYPE.matcher(line);
        if (type.find()) {
            add(
                    target,
                    type.group(2),
                    type.group(1).toUpperCase(Locale.ROOT),
                    line,
                    lineNumber,
                    RangeStyle.BRACE);
            return;
        }
        if (collectNamed(target, SCRIPT_FUNCTION, "FUNCTION", line, lineNumber, RangeStyle.BRACE)
                || collectNamed(
                        target, SCRIPT_ARROW, "FUNCTION", line, lineNumber, RangeStyle.BRACE)) {
            return;
        }
        Matcher method = SCRIPT_METHOD.matcher(line);
        if (method.find() && !CONTROL_WORDS.contains(method.group(1))) {
            add(target, method.group(1), "METHOD", line, lineNumber, RangeStyle.BRACE);
        }
    }

    private static void collectPython(List<Candidate> target, String line, int lineNumber) {
        Matcher matcher = PYTHON_DECLARATION.matcher(line);
        if (matcher.find()) {
            String kind = "class".equals(matcher.group(3)) ? "CLASS" : "FUNCTION";
            add(target, matcher.group(4), kind, line, lineNumber, RangeStyle.INDENT);
        }
    }

    private static void collectGo(List<Candidate> target, String line, int lineNumber) {
        if (collectNamed(target, GO_FUNCTION, "FUNCTION", line, lineNumber, RangeStyle.BRACE)) {
            return;
        }
        Matcher type = GO_TYPE.matcher(line);
        if (type.find()) {
            add(
                    target,
                    type.group(1),
                    type.group(2).toUpperCase(Locale.ROOT),
                    line,
                    lineNumber,
                    RangeStyle.BRACE);
        }
    }

    private static void collectRuby(List<Candidate> target, String line, int lineNumber) {
        Matcher matcher = RUBY_DECLARATION.matcher(line);
        if (matcher.find()) {
            String kind =
                    "def".equals(matcher.group(2))
                            ? "METHOD"
                            : matcher.group(2).toUpperCase(Locale.ROOT);
            add(target, matcher.group(3), kind, line, lineNumber, RangeStyle.INDENT);
        }
    }

    private static void collectPair(
            List<Candidate> target,
            Pattern pattern,
            String line,
            int lineNumber,
            RangeStyle style) {
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            String kind = matcher.group(1).toUpperCase(Locale.ROOT);
            if ("FN".equals(kind)) {
                kind = "FUNCTION";
            } else if ("FUNC".equals(kind)) {
                kind = "FUNCTION";
            }
            add(target, stripQuotes(matcher.group(2)), kind, line, lineNumber, style);
        }
    }

    private static void collectMarkdown(List<Candidate> target, String line, int lineNumber) {
        Matcher matcher = MARKDOWN_HEADING.matcher(line);
        if (matcher.find()) {
            add(
                    target,
                    matcher.group(2).trim(),
                    "DOC_SECTION",
                    line,
                    lineNumber,
                    RangeStyle.MARKDOWN);
        }
    }

    private static void collectConfig(
            List<Candidate> target, String language, String line, int lineNumber) {
        if ("toml".equals(language)) {
            Matcher section = TOML_SECTION.matcher(line);
            if (section.find()) {
                add(
                        target,
                        section.group(1).trim(),
                        "CONFIG_SECTION",
                        line,
                        lineNumber,
                        RangeStyle.SINGLE_LINE);
                return;
            }
        }
        Matcher matcher =
                "json".equals(language) ? JSON_KEY.matcher(line) : CONFIG_KEY.matcher(line);
        if (matcher.find() && !line.stripLeading().startsWith("#")) {
            add(target, matcher.group(1), "CONFIG_KEY", line, lineNumber, RangeStyle.SINGLE_LINE);
        }
    }

    private static boolean collectNamed(
            List<Candidate> target,
            Pattern pattern,
            String kind,
            String line,
            int lineNumber,
            RangeStyle style) {
        Matcher matcher = pattern.matcher(line);
        if (!matcher.find()) {
            return false;
        }
        add(target, matcher.group(1), kind, line, lineNumber, style);
        return true;
    }

    private static void add(
            List<Candidate> target,
            String name,
            String kind,
            String line,
            int lineNumber,
            RangeStyle style) {
        if (name == null || name.isBlank()) {
            return;
        }
        target.add(new Candidate(name.trim(), kind, lineNumber, indentation(line), style));
    }

    private static int endLine(Candidate candidate, List<Candidate> candidates, String[] lines) {
        return switch (candidate.style()) {
            case SINGLE_LINE -> candidate.startLine();
            case BRACE -> braceEnd(candidate, candidates, lines);
            case INDENT -> indentationEnd(candidate, lines);
            case MARKDOWN -> markdownEnd(candidate, candidates, lines.length);
        };
    }

    private static int braceEnd(Candidate candidate, List<Candidate> candidates, String[] lines) {
        int depth = 0;
        boolean opened = false;
        int searchEnd = Math.min(lines.length, candidate.startLine() + 6);
        for (int lineIndex = candidate.startLine() - 1; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            for (int characterIndex = 0; characterIndex < line.length(); characterIndex++) {
                char value = line.charAt(characterIndex);
                if (value == '{') {
                    depth++;
                    opened = true;
                } else if (value == '}' && opened) {
                    depth--;
                    if (depth <= 0) {
                        return lineIndex + 1;
                    }
                }
            }
            if (!opened && lineIndex + 1 >= searchEnd) {
                break;
            }
        }
        return nextPeer(candidate, candidates, lines.length);
    }

    private static int indentationEnd(Candidate candidate, String[] lines) {
        for (int index = candidate.startLine(); index < lines.length; index++) {
            String line = lines[index];
            if (!line.isBlank()
                    && !line.stripLeading().startsWith("#")
                    && indentation(line) <= candidate.indent()) {
                return index;
            }
        }
        return lines.length;
    }

    private static int markdownEnd(Candidate candidate, List<Candidate> candidates, int lineCount) {
        int level = candidate.indent();
        return candidates.stream()
                        .filter(item -> item.style() == RangeStyle.MARKDOWN)
                        .filter(item -> item.startLine() > candidate.startLine())
                        .filter(item -> item.indent() <= level)
                        .mapToInt(Candidate::startLine)
                        .min()
                        .orElse(lineCount + 1)
                - 1;
    }

    private static int nextPeer(Candidate candidate, List<Candidate> candidates, int lineCount) {
        return candidates.stream()
                        .filter(item -> item.startLine() > candidate.startLine())
                        .filter(item -> item.indent() <= candidate.indent())
                        .mapToInt(Candidate::startLine)
                        .min()
                        .orElse(lineCount + 1)
                - 1;
    }

    private static int indentation(String line) {
        int value = 0;
        while (value < line.length() && Character.isWhitespace(line.charAt(value))) {
            value++;
        }
        Matcher heading = MARKDOWN_HEADING.matcher(line);
        return heading.find() ? heading.group(1).length() : value;
    }

    private static String normalizeLanguage(String requested, String filePath) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim().toLowerCase(Locale.ROOT);
        }
        String fileName = filePath == null ? "" : Path.of(filePath).getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.equals("dockerfile")) {
            return "dockerfile";
        }
        int separator = lower.lastIndexOf('.');
        String extension = separator < 0 ? "" : lower.substring(separator + 1);
        return switch (extension) {
            case "java" -> "java";
            case "kt", "kts" -> "kotlin";
            case "cs" -> "csharp";
            case "js", "jsx", "mjs", "cjs" -> "javascript";
            case "ts", "tsx" -> "typescript";
            case "py" -> "python";
            case "go" -> "go";
            case "rs" -> "rust";
            case "php" -> "php";
            case "swift" -> "swift";
            case "rb" -> "ruby";
            case "sh", "bash", "zsh" -> "shell";
            case "sql" -> "sql";
            case "graphql", "gql" -> "graphql";
            case "md", "mdx" -> "markdown";
            case "json", "jsonc" -> "json";
            case "yaml", "yml" -> "yaml";
            case "properties" -> "properties";
            case "toml" -> "toml";
            case "env" -> "env";
            default -> extension;
        };
    }

    private static boolean isConfigPath(String filePath) {
        String path = filePath == null ? "" : filePath.toLowerCase(Locale.ROOT);
        return path.endsWith(".conf")
                || path.endsWith(".config")
                || path.endsWith(".ini")
                || path.endsWith(".cfg");
    }

    private static String stripQuotes(String value) {
        return value == null ? "" : value.replace("\"", "").replace("`", "");
    }

    public record Extraction(
            String language,
            List<SymbolDeclaration> symbols,
            boolean truncated,
            String limitationCode) {
        public Extraction {
            symbols = symbols == null ? List.of() : List.copyOf(symbols);
        }
    }

    public record SymbolDeclaration(
            String name, String kind, int startLine, int endLine, String language) {
        public int lineSpan() {
            return endLine - startLine;
        }
    }

    private record Candidate(
            String name, String kind, int startLine, int indent, RangeStyle style) {}

    private enum RangeStyle {
        BRACE,
        INDENT,
        MARKDOWN,
        SINGLE_LINE
    }
}
