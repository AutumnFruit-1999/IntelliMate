package com.atm.javaclaw.memory.consolidation;

import com.atm.javaclaw.memory.model.ExtractedFact;
import com.atm.javaclaw.memory.model.MemoryChunk;

import java.util.List;

/**
 * Result of a consolidation operation: a compressed summary chunk + extracted facts.
 *
 * @param sourceChunkCount number of working-memory chunks merged into the summary
 * @param tokensBefore     estimated total tokens before replace (or -1 if unknown)
 * @param tokensAfter      estimated total tokens after replace (or -1 if unknown)
 */
public record ConsolidationResult(
        MemoryChunk summaryChunk,
        List<ExtractedFact> facts,
        int sourceChunkCount,
        int tokensBefore,
        int tokensAfter
) {
}
