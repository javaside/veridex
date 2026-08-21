package io.veridex.generation;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.generation.application.CitationValidator;
import io.veridex.retrieval.api.EvidencePiece;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CitationValidatorTest {

    private final CitationValidator validator = new CitationValidator();

    private static java.util.Map<java.util.UUID, java.util.UUID> docIds(
            io.veridex.retrieval.api.EvidencePiece... evidence) {
        var out = new java.util.LinkedHashMap<java.util.UUID, java.util.UUID>();
        for (var e : evidence) {
            out.put(e.documentVersionId(), java.util.UUID.randomUUID());
        }
        return out;
    }

    @Test
    void validatesCitationIndexWithinEvidenceRange() {
        var evidence = List.of(
                new EvidencePiece(1, UUID.randomUUID(), UUID.randomUUID(), 0, "t1", "1", "a"),
                new EvidencePiece(2, UUID.randomUUID(), UUID.randomUUID(), 1, "t2", "1.1", "b"));
        var citations = validator.validate("根据资料[1]和[2]回答", evidence, docIds(evidence.toArray(new EvidencePiece[0])));
        assertThat(citations).hasSize(2);
        assertThat(citations).allMatch(c -> c.validationStatus().equals("VALID"));
        assertThat(citations).allMatch(c -> c.documentId() != null);
    }

    @Test
    void rejectsCitationOutsideEvidenceRange() {
        var evidence = List.of(new EvidencePiece(1, UUID.randomUUID(), UUID.randomUUID(), 0, "t1", "1", "a"));
        var citations = validator.validate("根据资料[9]回答", evidence, docIds(evidence.toArray(new EvidencePiece[0])));
        assertThat(citations).hasSize(1);
        assertThat(citations.get(0).validationStatus()).isEqualTo("INVALID");
    }

    @Test
    void ignoresNonCitationBrackets() {
        var evidence = List.of(new EvidencePiece(1, UUID.randomUUID(), UUID.randomUUID(), 0, "t1", "1", "a"));
        var citations = validator.validate("回答[1]说明", evidence, docIds(evidence.toArray(new EvidencePiece[0])));
        assertThat(citations).hasSize(1);
        assertThat(citations.get(0).citationIndex()).isEqualTo(1);
    }

    @Test
    void deduplicatesRepeatedCitationIndex() {
        var evidence = List.of(new EvidencePiece(1, UUID.randomUUID(), UUID.randomUUID(), 0, "t1", "1", "a"));
        // [1] 出现两次，但引用列表应只保留一条
        var citations = validator.validate("见[1]以及[1]", evidence, docIds(evidence.toArray(new EvidencePiece[0])));
        assertThat(citations).hasSize(1);
        assertThat(citations.get(0).citationIndex()).isEqualTo(1);
        assertThat(citations.get(0).validationStatus()).isEqualTo("VALID");
    }
}
