package dev.stevecreate.agent.core.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.BlockChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.InjectedResourceChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.IrreversibleProcessingChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.JournalData;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.RollbackReport;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.RollbackWarning;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.RollbackWarningCode;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.WorldBlockSnapshot;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorldChangeJournalTest {
    private static final ResourceId SESSION = id("test:session/c03");
    private static final ResourceId BUILD_STEP = id("test:step/build");
    private static final ResourceId PROCESS_STEP = id("test:step/process");
    private static final BlockPos3i C03_POSITION = new BlockPos3i(2, 4, 6);
    private static final BlockPos3i C04_POSITION = new BlockPos3i(8, 4, 6);

    @Test
    void recordsC03AndC04ChangesWithSessionBeforeAfterAndBlockEntityData() {
        JournalData shaftData = new JournalData(
                id("minecraft:snbt"),
                "{id:\"create:shaft\",x:8,y:4,z:6}");
        BlockChange c03 = blockChange(
                "c03_stone", C03_POSITION, air(), block("minecraft:stone"), 10);
        BlockChange c04 = blockChange(
                "c04_shaft",
                C04_POSITION,
                air(),
                new WorldBlockSnapshot(
                        id("create:shaft"),
                        Map.of("axis", "z"),
                        Optional.of(shaftData)),
                11);

        WorldChangeJournal journal = WorldChangeJournal.empty(SESSION)
                .append(c03)
                .append(c04);

        assertThat(journal.entries()).containsExactly(c03, c04);
        assertThat(journal.modifiedPositions()).containsExactly(C03_POSITION, C04_POSITION);
        assertThat(journal.references()).extracting(value -> value.changeId())
                .containsExactly(c03.changeId(), c04.changeId());
        assertThat(journal.references()).allMatch(value -> value.potentiallyReversible());
        assertThat(((BlockChange) journal.entries().get(1)).after().blockEntityData())
                .contains(shaftData);
        assertThatThrownBy(() -> journal.entries().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> journal.append(new BlockChange(
                        id("test:change/wrong_session"),
                        id("test:session/other"),
                        BUILD_STEP,
                        12,
                        new BlockPos3i(9, 4, 6),
                        air(),
                        block("minecraft:stone"))))
                .withMessage("World change belongs to test:session/other instead of test:session/c03");
    }

    @Test
    void plansReverseBoundedRollbackOnlyForUnchangedSessionOwnedBlocks() {
        BlockChange first = blockChange(
                "first", C03_POSITION, air(), block("minecraft:stone"), 10);
        BlockChange second = blockChange(
                "second", C04_POSITION, air(), block("create:shaft", Map.of("axis", "z")), 11);
        WorldChangeJournal journal = WorldChangeJournal.empty(SESSION)
                .append(first)
                .append(second);

        var plan = journal.planRollback(Map.of(
                C03_POSITION, first.after(),
                C04_POSITION, second.after()));

        assertThat(plan.operations()).extracting(value -> value.changeId())
                .containsExactly(second.changeId(), first.changeId());
        assertThat(plan.operations()).allMatch(value -> value.restoreState().equals(air()));
        assertThat(plan.warnings()).isEmpty();

        var conflict = journal.planRollback(Map.of(
                C03_POSITION, block("minecraft:diamond_block"),
                C04_POSITION, second.after()));
        assertThat(conflict.operations()).extracting(value -> value.changeId())
                .containsExactly(second.changeId());
        assertThat(conflict.warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.code()).isEqualTo(RollbackWarningCode.CURRENT_STATE_CHANGED);
            assertThat(warning.position()).contains(C03_POSITION);
        });
    }

    @Test
    void stabilizesOnlyLatestBlockEntityAfterStateWithoutHidingBlockDrift() {
        JournalData shaftData = new JournalData(id("minecraft:snbt"), "{phase:\"shaft\"}");
        JournalData beltBeforeSave = new JournalData(id("minecraft:snbt"), "{phase:\"connecting\"}");
        JournalData beltAfterSave = new JournalData(id("minecraft:snbt"), "{phase:\"stable\"}");
        WorldBlockSnapshot shaft = new WorldBlockSnapshot(
                id("create:shaft"), Map.of("axis", "z"), Optional.of(shaftData));
        WorldBlockSnapshot connectingBelt = new WorldBlockSnapshot(
                id("create:belt"), Map.of("part", "start"), Optional.of(beltBeforeSave));
        WorldBlockSnapshot stableBelt = new WorldBlockSnapshot(
                id("create:belt"), Map.of("part", "start"), Optional.of(beltAfterSave));
        BlockChange placeShaft = blockChange(
                "place_shaft", C04_POSITION, air(), shaft, 10);
        BlockChange connectBelt = blockChange(
                "connect_belt", C04_POSITION, shaft, connectingBelt, 11);
        WorldChangeJournal original = WorldChangeJournal.empty(SESSION)
                .append(placeShaft)
                .append(connectBelt);

        WorldChangeJournal stabilized = original.stabilizeBlockEntityAfterStates(
                Map.of(C04_POSITION, stableBelt));

        assertThat(stabilized.entries().get(0)).isEqualTo(placeShaft);
        assertThat(((BlockChange) stabilized.entries().get(1)).after()).isEqualTo(stableBelt);
        assertThat(stabilized.references()).isEqualTo(original.references());
        assertThat(stabilized.modifiedPositions()).containsExactly(C04_POSITION);
        assertThat(stabilized.planRollback(Map.of(C04_POSITION, stableBelt)).operations())
                .extracting(value -> value.restoreState())
                .containsExactly(shaft, air());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> original.stabilizeBlockEntityAfterStates(Map.of(
                        C04_POSITION, block("minecraft:dirt"))))
                .withMessage("Recovery stabilization found block-state drift at " + C04_POSITION);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> original.stabilizeBlockEntityAfterStates(Map.of()))
                .withMessage("Recovery stabilization requires every modified block position exactly once");
    }

    @Test
    void injectedInputAndIrreversibleProcessingYieldWarningsWithoutCompensation() {
        ProcessResource cobblestone = resource("minecraft:cobblestone", 1);
        ProcessResource gravel = resource("minecraft:gravel", 1);
        BlockChange machine = blockChange(
                "millstone", C03_POSITION, air(), block("create:millstone"), 10);
        InjectedResourceChange input = new InjectedResourceChange(
                id("test:change/input"),
                SESSION,
                id("test:step/feed"),
                20,
                C03_POSITION,
                cobblestone);
        IrreversibleProcessingChange processing = new IrreversibleProcessingChange(
                id("test:change/process"),
                SESSION,
                PROCESS_STEP,
                30,
                id("create:milling/cobblestone"),
                List.of(cobblestone),
                List.of(gravel),
                List.of(C03_POSITION));
        WorldChangeJournal journal = WorldChangeJournal.empty(SESSION)
                .append(machine)
                .append(input)
                .append(processing);

        var rollback = journal.planRollback(Map.of(C03_POSITION, machine.after()));

        assertThat(rollback.operations()).isEmpty();
        assertThat(rollback.warnings()).extracting(RollbackWarning::code)
                .containsExactly(
                        RollbackWarningCode.INJECTED_RESOURCE_NOT_RECREATED,
                        RollbackWarningCode.IRREVERSIBLE_PROCESSING_NOT_REVERSED);
        assertThat(rollback.warnings()).allMatch(value ->
                value.detail().contains("No item or resource compensation was created"));
        assertThat(journal.modifiedPositions()).containsExactly(C03_POSITION);
    }

    @Test
    void enforcesUniqueOrderedBoundedEntriesAndSnapshotData() {
        BlockChange first = blockChange(
                "first", C03_POSITION, air(), block("minecraft:stone"), 10);
        WorldChangeJournal journal = WorldChangeJournal.empty(SESSION).append(first);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> journal.append(first))
                .withMessage("Duplicate world change id: test:change/first");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> journal.append(blockChange(
                        "earlier", C04_POSITION, air(), block("minecraft:stone"), 9)))
                .withMessage("World change ticks must be monotonic");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WorldBlockSnapshot(
                        id("minecraft:stone"),
                        Map.of("bad key", "value"),
                        Optional.empty()))
                .withMessage("Invalid block-state property name: bad key");
    }

    @Test
    void rollbackReportSeparatesRestoredPositionsFromTypedWarnings() {
        RollbackWarning warning = new RollbackWarning(
                id("test:change/input"),
                RollbackWarningCode.INJECTED_RESOURCE_NOT_RECREATED,
                Optional.of(C03_POSITION),
                "No item or resource compensation was created");
        RollbackReport report = new RollbackReport(
                SESSION,
                3,
                1,
                List.of(C04_POSITION),
                List.of(warning));

        assertThat(report.restoredPositions()).containsExactly(C04_POSITION);
        assertThat(report.warnings()).containsExactly(warning);
        assertThat(report.fullyRestored()).isFalse();
    }

    private static BlockChange blockChange(
            String name,
            BlockPos3i position,
            WorldBlockSnapshot before,
            WorldBlockSnapshot after,
            long tick) {
        return new BlockChange(
                id("test:change/" + name),
                SESSION,
                BUILD_STEP,
                tick,
                position,
                before,
                after);
    }

    private static WorldBlockSnapshot air() {
        return block("minecraft:air");
    }

    private static WorldBlockSnapshot block(String id) {
        return block(id, Map.of());
    }

    private static WorldBlockSnapshot block(String id, Map<String, String> properties) {
        return new WorldBlockSnapshot(id(id), properties, Optional.empty());
    }

    private static ProcessResource resource(String id, long amount) {
        return new ProcessResource(id(id), GenericResourceType.ITEM, amount);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
