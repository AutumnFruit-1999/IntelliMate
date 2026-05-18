package com.atm.intellimate.memory.perception;

import com.atm.intellimate.memory.model.ContentCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ImportanceAssessor")
class ImportanceAssessorTest {

    private final ImportanceAssessor assessor = new ImportanceAssessor();

    @Test
    @DisplayName("Error output returns high importance 0.9")
    void assess_errorOutput_returnsHighImportance() {
        float score = assessor.assess(
                "java.lang.NullPointerException at com.example.Service.doStuff(Service.java:42)",
                ContentCategory.COMMAND_OUTPUT, Map.of());
        assertEquals(0.9f, score);
    }

    @Test
    @DisplayName("Non-zero exit code returns high importance")
    void assess_exitCodeNonZero_returnsHighImportance() {
        float score = assessor.assess("Build completed", ContentCategory.COMMAND_OUTPUT,
                Map.of("exitCode", "1"));
        assertEquals(0.9f, score);
    }

    @Test
    @DisplayName("Normal output returns medium importance 0.5")
    void assess_normalOutput_returnsMediumImportance() {
        float score = assessor.assess(
                "public class UserService { private final UserRepository repo; }",
                ContentCategory.CODE, Map.of());
        assertEquals(0.5f, score);
    }

    @Test
    @DisplayName("Empty output returns low importance 0.2")
    void assess_emptyOutput_returnsLowImportance() {
        assertEquals(0.2f, assessor.assess("", ContentCategory.TEXT, Map.of()));
        assertEquals(0.2f, assessor.assess(null, ContentCategory.TEXT, Map.of()));
        assertEquals(0.2f, assessor.assess("  ", ContentCategory.TEXT, Map.of()));
    }

    @Test
    @DisplayName("Confirmation message returns low importance")
    void assess_confirmationMessage_returnsLow() {
        float score = assessor.assess("文件已保存", ContentCategory.TEXT, Map.of());
        assertEquals(0.2f, score);
    }

    @Test
    @DisplayName("Search result returns 0.7")
    void assess_searchResult_returnsDirectResult() {
        float score = assessor.assess("Found 5 results for 'auth'",
                ContentCategory.SEARCH_RESULT, Map.of());
        assertEquals(0.7f, score);
    }

    @Test
    @DisplayName("Plan mode: current step content gets boost")
    void assess_planMode_currentStep_boost() {
        float score = assessor.assessWithPlanContext(
                "修改 AuthService.java 中的认证逻辑",
                ContentCategory.CODE, Map.of(),
                "修改 AuthService 认证模块",
                List.of(), List.of());
        assertTrue(score > 0.5f, "Current step alignment should boost importance");
    }

    @Test
    @DisplayName("Plan mode: completed step content gets reduced")
    void assess_planMode_completedStep_reduced() {
        float score = assessor.assessWithPlanContext(
                "database migration script executed successfully",
                ContentCategory.TEXT, Map.of(),
                "implement frontend page",
                List.of("execute database migration script"), List.of());
        assertEquals(0.3f, score);
    }

    @Test
    @DisplayName("Plan mode: unrelated content returns 0.2")
    void assess_planMode_unrelated() {
        float score = assessor.assessWithPlanContext(
                "天气真好",
                ContentCategory.TEXT, Map.of(),
                "修改数据库连接配置",
                List.of(), List.of());
        assertEquals(0.2f, score);
    }
}
