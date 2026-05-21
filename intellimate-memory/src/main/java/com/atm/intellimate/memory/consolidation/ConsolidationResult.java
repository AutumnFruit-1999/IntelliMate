package com.atm.intellimate.memory.consolidation;

import com.atm.intellimate.memory.model.ExtractedFact;
import com.atm.intellimate.memory.model.MemoryChunk;

import java.util.List;

/**
 * Result of a consolidation operation: a compressed summary chunk + extracted facts.
 *
 * @param sourceChunkCount    number of working-memory chunks merged into the summary
 * @param tokensBefore        estimated total tokens before replace (or -1 if unknown)
 * @param tokensAfter         estimated total tokens after replace (or -1 if unknown)
 * @param sourceChunkPreviews preview info of chunks that were consolidated
 * @param factsStoredToLongTerm whether extracted facts were stored to long-term memory
 */
public record ConsolidationResult(
        MemoryChunk summaryChunk,
        List<ExtractedFact> facts,
        int sourceChunkCount,
        int tokensBefore,
        int tokensAfter,
        List<SourceChunkPreview> sourceChunkPreviews,
        boolean factsStoredToLongTerm
) {
    public record SourceChunkPreview(String type, int tokens, float importance, String preview) {}

    public ConsolidationResult(MemoryChunk summaryChunk, List<ExtractedFact> facts,
                               int sourceChunkCount, int tokensBefore, int tokensAfter) {
        this(summaryChunk, facts, sourceChunkCount, tokensBefore, tokensAfter, List.of(), false);
    }
}
