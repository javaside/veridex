package io.veridex.knowledge.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.veridex.knowledge.infrastructure.security.ArchiveBudget;
import io.veridex.knowledge.infrastructure.security.UploadSecurityProperties;
import org.junit.jupiter.api.Test;

class ArchiveBudgetTest {

    private ArchiveBudget budget() {
        return new ArchiveBudget(new UploadSecurityProperties(1024 * 1024, 1000, 10, 8));
    }

    @Test
    void rejectsPathTraversal() {
        assertThatThrownBy(() -> budget().checkEntry("../../outside.txt", 10, 0))
                .hasMessage("unsafe_path");
    }

    @Test
    void rejectsAbsolutePath() {
        assertThatThrownBy(() -> budget().checkEntry("/etc/passwd", 10, 0))
                .hasMessage("unsafe_path");
    }

    @Test
    void rejectsNullByteInName() {
        assertThatThrownBy(() -> budget().checkEntry("evil\u0000.txt", 10, 0))
                .hasMessage("unsafe_path");
    }

    @Test
    void rejectsExpandedBudgetExceeded() {
        ArchiveBudget budget = new ArchiveBudget(new UploadSecurityProperties(1024 * 1024, 1000, 10, 8));
        budget.checkEntry("first.txt", 600, 0);
        assertThatThrownBy(() -> budget.checkEntry("second.txt", 401, 0))
                .hasMessage("archive_budget_exceeded");
    }

    @Test
    void rejectsTooManyEntries() {
        ArchiveBudget budget = new ArchiveBudget(new UploadSecurityProperties(1024 * 1024, 100_000, 2, 8));
        budget.checkEntry("a.txt", 1, 0);
        budget.checkEntry("b.txt", 1, 0);
        assertThatThrownBy(() -> budget.checkEntry("c.txt", 1, 0))
                .hasMessage("archive_budget_exceeded");
    }

    @Test
    void rejectsNestingBeyondDepth() {
        ArchiveBudget budget = new ArchiveBudget(new UploadSecurityProperties(1024 * 1024, 100_000, 100, 2));
        assertThatThrownBy(() -> budget.checkEntry("a/b/c.txt", 10, 3))
                .hasMessage("archive_budget_exceeded");
    }

    @Test
    void acceptsSafeEntryWithinBudget() {
        budget().checkEntry("docs/section.txt", 500, 1);
    }
}
