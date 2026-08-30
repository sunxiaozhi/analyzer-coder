package com.analyzercoder.application.code;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import org.junit.jupiter.api.Test;

class CodeSymbolExtractorTest {
    private final CodeSymbolExtractor extractor = new CodeSymbolExtractor();

    @Test
    void extractsOnlyDeclarationsThatExistInJavaSource() {
        String source =
                """
                public class AccountService {
                    public String findAccount(String id) {
                        if (id == null) {
                            return "missing";
                        }
                        return id;
                    }
                }
                """;

        CodeSymbolExtractor.Extraction result =
                extractor.extract(source, "src/AccountService.java", null);

        assertThat(result.language()).isEqualTo("java");
        assertThat(result.symbols())
                .extracting(
                        CodeSymbolExtractor.SymbolDeclaration::name,
                        CodeSymbolExtractor.SymbolDeclaration::kind)
                .containsExactly(tuple("AccountService", "CLASS"), tuple("findAccount", "METHOD"));
        assertThat(result.symbols())
                .noneMatch(symbol -> symbol.name().equals("if") || symbol.name().equals("missing"));
        assertThat(extractor.symbolForChunk(result, 3, 6).name()).isEqualTo("findAccount");
    }

    @Test
    void supportsCommonFunctionAndConfigurationDeclarations() {
        assertThat(
                        extractor
                                .extract(
                                        "export const loadUser = async (id) => id;",
                                        "user.ts",
                                        null)
                                .symbols())
                .extracting(CodeSymbolExtractor.SymbolDeclaration::name)
                .containsExactly("loadUser");
        assertThat(
                        extractor
                                .extract(
                                        "class User:\n    def load(self):\n        return 1\n",
                                        "user.py",
                                        null)
                                .symbols())
                .extracting(CodeSymbolExtractor.SymbolDeclaration::name)
                .containsExactly("User", "load");
        assertThat(
                        extractor
                                .extract(
                                        "func LoadUser(id string) string { return id }",
                                        "user.go",
                                        null)
                                .symbols())
                .extracting(CodeSymbolExtractor.SymbolDeclaration::name)
                .containsExactly("LoadUser");
        assertThat(extractor.extract("server:\n  port: 8080\n", "application.yml", null).symbols())
                .extracting(CodeSymbolExtractor.SymbolDeclaration::name)
                .containsExactly("server", "port");
        assertThat(
                        extractor
                                .extract("{\n  \"name\": \"demo\"\n}\n", "package.json", null)
                                .symbols())
                .extracting(CodeSymbolExtractor.SymbolDeclaration::name)
                .containsExactly("name");
    }

    @Test
    void leavesUnsupportedTextEmptyAndMarksGeneratedFiles() {
        assertThat(
                        extractor
                                .extract("ordinary prose without declarations", "notes.txt", null)
                                .symbols())
                .isEmpty();
        assertThat(extractor.generatedCode("src/generated/ApiClient.java", "class ApiClient {}"))
                .isTrue();
        assertThat(
                        extractor.generatedCode(
                                "src/ApiClient.java",
                                "// @generated do not edit\nclass ApiClient {}"))
                .isTrue();
        assertThat(extractor.generatedCode("src/ApiClient.java", "class ApiClient {}")).isFalse();
    }
}
