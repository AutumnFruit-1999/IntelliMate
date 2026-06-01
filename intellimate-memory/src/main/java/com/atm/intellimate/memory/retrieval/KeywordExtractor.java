package com.atm.intellimate.memory.retrieval;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Extracts meaningful keywords from a text cue for database-level pre-filtering.
 * Supports Chinese via jieba segmentation and English via the same tokenizer.
 */
public class KeywordExtractor {

    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一", "一个",
            "上", "也", "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好",
            "这", "那", "个", "们", "来", "被", "把", "让", "给", "从",
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "shall",
            "should", "may", "might", "must", "can", "could", "i", "you", "he",
            "she", "it", "we", "they", "this", "that", "and", "or", "but", "in",
            "on", "at", "to", "for", "of", "with", "from", "by", "as", "into",
            "about", "not", "no", "so", "if", "then", "what", "how", "when"
    );

    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    public List<String> extract(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.toLowerCase();
        LinkedHashSet<String> tokens = new LinkedHashSet<>();

        List<SegToken> searchTokens = segmenter.process(normalized, JiebaSegmenter.SegMode.SEARCH);
        for (SegToken token : searchTokens) {
            addToken(tokens, token.word.trim());
        }

        List<SegToken> indexTokens = segmenter.process(normalized, JiebaSegmenter.SegMode.INDEX);
        for (SegToken token : indexTokens) {
            String word = token.word.trim();
            if (word.length() < 2 || STOP_WORDS.contains(word)) {
                continue;
            }
            if (word.length() == 2 && isOffsetBigram(token, indexTokens)) {
                continue;
            }
            tokens.add(word);
        }

        return tokens.stream()
                .limit(15)
                .collect(Collectors.toList());
    }

    private static boolean isOffsetBigram(SegToken candidate, List<SegToken> indexTokens) {
        for (SegToken other : indexTokens) {
            String otherWord = other.word.trim();
            if (otherWord.length() < 3 || otherWord.equals(candidate.word.trim())) {
                continue;
            }
            if (candidate.startOffset > other.startOffset
                    && candidate.endOffset <= other.endOffset) {
                return true;
            }
        }
        return false;
    }

    private static void addToken(Set<String> tokens, String word) {
        if (word.length() >= 2 && !STOP_WORDS.contains(word)) {
            tokens.add(word);
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
