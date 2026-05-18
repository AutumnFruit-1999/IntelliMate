package com.atm.intellimate.memory.retrieval;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Extracts meaningful keywords from a text cue for database-level pre-filtering.
 * Supports Chinese via bigram tokenization and English via whitespace splitting.
 */
public class KeywordExtractor {

    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一", "一个",
            "上", "也", "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好",
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "shall",
            "should", "may", "might", "must", "can", "could", "i", "you", "he",
            "she", "it", "we", "they", "this", "that", "and", "or", "but", "in",
            "on", "at", "to", "for", "of", "with", "from", "by", "as", "into",
            "about", "not", "no", "so", "if", "then", "what", "how", "when"
    );

    private static final Set<Character> CJK_STOP_CHARS = Set.of(
            '的', '了', '在', '是', '我', '有', '和', '就', '不', '人',
            '都', '一', '上', '也', '很', '到', '说', '要', '去', '你',
            '会', '着', '看', '好', '这', '那', '个', '们', '来', '被'
    );

    public List<String> extract(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.toLowerCase();
        List<String> tokens = new ArrayList<>();

        String[] segments = normalized.split("[\\s,./\\-_:;!?()\\[\\]{}\"'，。？！、；：（）【】《》]+");
        for (String seg : segments) {
            if (seg.isBlank()) continue;
            if (containsCJK(seg)) {
                tokens.addAll(extractCJKBigrams(seg));
            } else if (seg.length() >= 2 && !STOP_WORDS.contains(seg)) {
                tokens.add(seg);
            }
        }

        return tokens.stream()
                .distinct()
                .limit(15)
                .collect(Collectors.toList());
    }

    private boolean containsCJK(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (isCJK(text.charAt(i))) return true;
        }
        return false;
    }

    private static boolean isCJK(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    private List<String> extractCJKBigrams(String text) {
        List<String> bigrams = new ArrayList<>();
        StringBuilder cjkRun = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCJK(c) && !CJK_STOP_CHARS.contains(c)) {
                cjkRun.append(c);
            } else {
                flushBigrams(cjkRun, bigrams);
                cjkRun.setLength(0);
            }
        }
        flushBigrams(cjkRun, bigrams);
        return bigrams;
    }

    private void flushBigrams(StringBuilder run, List<String> out) {
        if (run.length() >= 2) {
            for (int i = 0; i <= run.length() - 2; i++) {
                out.add(run.substring(i, i + 2));
            }
        } else if (run.length() == 1) {
            out.add(run.toString());
        }
    }

    /**
     * Compute Jaccard similarity coefficient between two texts.
     */
    public double jaccardSimilarity(String a, String b) {
        Set<String> setA = Set.copyOf(extract(a));
        Set<String> setB = Set.copyOf(extract(b));
        if (setA.isEmpty() && setB.isEmpty()) return 1.0;
        if (setA.isEmpty() || setB.isEmpty()) return 0.0;

        long intersection = setA.stream().filter(setB::contains).count();
        long union = setA.size() + setB.size() - intersection;
        return (double) intersection / union;
    }
}
