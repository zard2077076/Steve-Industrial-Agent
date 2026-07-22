package dev.stevecreate.agent.core.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.stevecreate.agent.core.model.ResourceId;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FormalSurveyCommandParserTest {
    private static final String WORLD = "world:" + "a".repeat(64);
    private final FormalSurveyCommandParser parser = new FormalSurveyCommandParser(Set.of(WORLD),
            Set.of(ResourceId.parse("minecraft:gravel"), ResourceId.parse("create:iron_sheet")));

    @Test
    void parsesOnlyFiveRegisteredReadOnlyCommandsIntoIgnoredRelativeReportKeys() {
        assertThat(parser.parse(List.of("survey", "formal", "list-worlds")).command())
                .isEqualTo(FormalSurveyCommand.LIST_WORLDS);
        for (String command : List.of("inspect", "infrastructure", "candidates")) {
            FormalSurveyCommandPlan plan = parser.parse(List.of("survey", "formal", command, WORLD));
            assertThat(plan.worldIdentity()).contains(WORLD);
            assertThat(plan.reportRelativePath()).startsWith("formal-survey/" + "a".repeat(64) + "/");
            assertThat(plan.formalReadRequested()).isTrue();
        }
        FormalSurveyCommandPlan preview = parser.parse(
                List.of("survey", "formal", "preview", WORLD, "minecraft:gravel", "3"));
        assertThat(preview.target()).contains(ResourceId.parse("minecraft:gravel"));
        assertThat(preview.quantity()).isEqualTo(3);
    }

    @Test
    void rejectsPathsExtraArgumentsAndUnregisteredWorldsWithTypedFailures() {
        assertFailure(List.of("survey", "formal", "inspect", "D:\\PCL2\\world"),
                FormalSurveyFailureCode.FORMAL_WORLD_PATH_OUTSIDE_ALLOWED_ROOT);
        assertFailure(List.of("survey", "formal", "inspect", "../world"),
                FormalSurveyFailureCode.FORMAL_WORLD_PATH_OUTSIDE_ALLOWED_ROOT);
        assertFailure(List.of("survey", "formal", "inspect", "world:" + "b".repeat(64)),
                FormalSurveyFailureCode.FORMAL_WORLD_NOT_SELECTED);
        assertFailure(List.of("survey", "formal", "list-worlds", "extra"),
                FormalSurveyFailureCode.FORMAL_WORLD_NOT_SELECTED);
    }

    @Test
    void previewAcceptsOnlyRegisteredTargetsAndBoundedNumericQuantity() {
        assertFailure(List.of("survey", "formal", "preview", WORLD, "minecraft:diamond", "1"),
                FormalSurveyFailureCode.CANDIDATE_ZONE_NOT_FOUND);
        assertFailure(List.of("survey", "formal", "preview", WORLD, "minecraft:gravel", "0"),
                FormalSurveyFailureCode.FORMAL_WORLD_DRY_RUN_ONLY);
        assertFailure(List.of("survey", "formal", "preview", WORLD, "minecraft:gravel", "1000001"),
                FormalSurveyFailureCode.FORMAL_WORLD_DRY_RUN_ONLY);
    }

    @Test
    void plansStructurallyCarryNoWriteProcessSessionInventoryApprovalOrExecutionAuthority() {
        FormalSurveyCommandPlan plan = parser.parse(List.of("survey", "formal", "inspect", WORLD));
        assertThat(plan.arbitraryPathAccepted()).isFalse();
        assertThat(plan.processStarted()).isFalse();
        assertThat(plan.sessionCreated()).isFalse();
        assertThat(plan.formalWorldWrite()).isFalse();
        assertThat(plan.inventoryContentsRead()).isFalse();
        assertThat(plan.approvalCreated()).isFalse();
        assertThat(plan.executionAllowed()).isFalse();
        assertThat(Arrays.stream(new Class<?>[] {FormalSurveyCommandParser.class, FormalSurveyCommandPlan.class})
                .flatMap(type -> Arrays.stream(type.getDeclaredFields())).map(Field::getType).map(Class::getName))
                .noneMatch(name -> name.startsWith("java.nio.file") || name.contains("Process")
                        || name.contains("GenericExecutionSession") || name.startsWith("net.minecraft"));
    }

    @Test
    void registriesAreBoundedAndInvalidIdentitiesFailBeforeParsing() {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new FormalSurveyCommandParser(Set.of("not-a-world"), Set.of()));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new FormalSurveyCommandParser(Set.copyOf(java.util.stream.IntStream.range(0, 257)
                        .mapToObj(value -> "world:" + String.format("%064x", value)).toList()), Set.of()));
    }

    private void assertFailure(List<String> command, FormalSurveyFailureCode code) {
        assertThatExceptionOfType(FormalSurveyCommandException.class).isThrownBy(() -> parser.parse(command))
                .satisfies(exception -> assertThat(exception.code()).isEqualTo(code));
    }
}
