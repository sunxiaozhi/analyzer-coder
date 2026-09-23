package com.analyzercoder.application.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

class SymbolCallMatcherTest {
    @Test
    void matchesOriginalSubstringRuleIncludingSuffixesAndDeduplicatesCalls() {
        SymbolCallMatcher matcher = new SymbolCallMatcher(Set.of("foo", "oo", "bar"));

        assertEquals(Set.of("foo", "oo", "bar"), matcher.find("foo( foo( bar("));
        assertEquals(Set.of(), matcher.find("foo ( bar ("));
        assertEquals(Set.of("oo"), matcher.find("prefixoo("));
    }
}
