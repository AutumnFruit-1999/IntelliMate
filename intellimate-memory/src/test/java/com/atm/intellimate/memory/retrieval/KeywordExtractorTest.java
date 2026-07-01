package com.atm.intellimate.memory.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeywordExtractorTest {

    @Test
    void extract_chineseText_usesJiebaSegmentation() {
        KeywordExtractor extractor = new KeywordExtractor();
        List<String> tokens = extractor.extract("数据库连接超时问题");
        assertTrue(tokens.contains("数据库"), "Should contain '数据库' as a word, not bigrams");
        assertTrue(tokens.contains("连接") || tokens.contains("超时"), "Should contain meaningful words");
        assertFalse(tokens.contains("库连"), "Should NOT contain meaningless bigram '库连'");
    }

    @Test
    void jaccardSimilarity_withJieba_improvedAccuracy() {
        KeywordExtractor extractor = new KeywordExtractor();
        double sim = extractor.jaccardSimilarity("数据库连接问题", "MySQL 数据库的连接池配置");
        assertTrue(sim > 0.2, "Jieba-based similarity should be higher for related content");
    }
}
