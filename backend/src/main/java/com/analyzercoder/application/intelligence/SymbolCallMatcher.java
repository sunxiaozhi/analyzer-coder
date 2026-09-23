package com.analyzercoder.application.intelligence;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Find every configured symbol immediately before an opening parenthesis. */
final class SymbolCallMatcher {
    private final Node root = new Node();

    SymbolCallMatcher(Set<String> symbols) {
        for (String symbol : symbols) {
            Node node = root;
            for (int index = symbol.length() - 1; index >= 0; index--) {
                node = node.children.computeIfAbsent(symbol.charAt(index), ignored -> new Node());
            }
            node.symbol = symbol;
        }
    }

    Set<String> find(String content) {
        Set<String> matches = new HashSet<>();
        for (int opening = content.indexOf('(');
                opening >= 0;
                opening = content.indexOf('(', opening + 1)) {
            Node node = root;
            for (int index = opening - 1; index >= 0; index--) {
                node = node.children.get(content.charAt(index));
                if (node == null) {
                    break;
                }
                if (node.symbol != null) {
                    matches.add(node.symbol);
                }
            }
        }
        return matches;
    }

    private static final class Node {
        private final Map<Character, Node> children = new HashMap<>();
        private String symbol;
    }
}
