package dev.stevecreate.agent.forge1201.player.net;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.player.LayoutVariant;
import dev.stevecreate.agent.core.player.PlayerExecutionMode;
import dev.stevecreate.agent.forge1201.command.PlayerPreviewService.PreviewCategory;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.GoalWire;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.GoalSearchC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.GoalListS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.OpenTerminalS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.PreviewCellWire;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.PreviewSnapshotS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.RelocationCandidateWire;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.RelocationResultS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ConfirmationSummaryS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ApprovalResultS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ProjectWire;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ClearingStatusS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.BindMaterialSourceC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.CompletionReportWire;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.MaterialAction;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.MaterialControlC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.MaterialLineWire;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.MaterialSnapshotS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.MetalPressAction;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.MetalPressControlC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.MetalPressStatusS2C;
import dev.stevecreate.agent.forge1201.player.net.CompositeOrderPackets.BufferWire;
import dev.stevecreate.agent.forge1201.player.net.CompositeOrderPackets.CompositeAction;
import dev.stevecreate.agent.forge1201.player.net.CompositeOrderPackets.CompositeControlC2S;
import dev.stevecreate.agent.forge1201.player.net.CompositeOrderPackets.CompositeCompletionS2C;
import dev.stevecreate.agent.forge1201.player.net.CompositeOrderPackets.CompletionRowWire;
import dev.stevecreate.agent.forge1201.player.net.CompositeOrderPackets.CompositeStatusS2C;
import dev.stevecreate.agent.forge1201.player.net.CompositeOrderPackets.NodeWire;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class PlayerWorkflowPacketsTest {
    @Test
    void terminalAndPreviewPacketsRoundTripWithinTheirExplicitBounds() {
        OpenTerminalS2C terminal = new OpenTerminalS2C(41, true, "OK", List.of(
                new GoalWire("create:cogwheel", "create:deploying/cogwheel",
                        "create:deploying", "goal.steve_create_agent.create.cogwheel",
                        "create:shaft x1, minecraft:oak_planks x1", 1, 1, true, "READY")), null);
        FriendlyByteBuf terminalBuffer = new FriendlyByteBuf(Unpooled.buffer());
        OpenTerminalS2C.encode(terminal, terminalBuffer);
        assertThat(OpenTerminalS2C.decode(terminalBuffer)).isEqualTo(terminal);

        GoalSearchC2S search = new GoalSearchC2S(41, "concrete");
        FriendlyByteBuf searchBuffer = new FriendlyByteBuf(Unpooled.buffer());
        GoalSearchC2S.encode(search, searchBuffer);
        assertThat(GoalSearchC2S.decode(searchBuffer)).isEqualTo(search);

        GoalListS2C results = new GoalListS2C(41, "concrete", terminal.goals());
        FriendlyByteBuf resultsBuffer = new FriendlyByteBuf(Unpooled.buffer());
        GoalListS2C.encode(results, resultsBuffer);
        assertThat(GoalListS2C.decode(resultsBuffer)).isEqualTo(results);

        PreviewSnapshotS2C preview = new PreviewSnapshotS2C(
                true, "OK", "b9cc2514-6db4-4a25-81dc-2eb11ec8db25", 77,
                10, 70, 12, QuarterTurn.CLOCKWISE_180, LayoutVariant.EXPANDABLE,
                List.of(new PreviewCellWire(10, 70, 12, "create:depot", "input_depot",
                        PreviewCategory.PLACE, "REPLACEABLE")),
                10, 70, 12, 10, 70, 12, "a".repeat(64), "b".repeat(64),
                1, 0, 0, 0, 0, 0, 0, true, true);
        FriendlyByteBuf previewBuffer = new FriendlyByteBuf(Unpooled.buffer());
        PreviewSnapshotS2C.encode(preview, previewBuffer);
        assertThat(PreviewSnapshotS2C.decode(previewBuffer)).isEqualTo(preview);

        RelocationResultS2C relocation = new RelocationResultS2C(true, "OK",
                "b9cc2514-6db4-4a25-81dc-2eb11ec8db25", 77, "candidate-001",
                List.of(new RelocationCandidateWire("candidate-001", 12, 71, 14,
                        QuarterTurn.CLOCKWISE_270, LayoutVariant.COMPACT, true, false,
                        120, 0, 0, 0, 0, 1, 8, 0, 20,
                        List.of("hard_conflicts=0", "demolition=1"))));
        FriendlyByteBuf relocationBuffer = new FriendlyByteBuf(Unpooled.buffer());
        RelocationResultS2C.encode(relocation, relocationBuffer);
        assertThat(RelocationResultS2C.decode(relocationBuffer)).isEqualTo(relocation);

        ConfirmationSummaryS2C summary = new ConfirmationSummaryS2C(true, "OK",
                "b9cc2514-6db4-4a25-81dc-2eb11ec8db25", 77, "create:cogwheel", 4,
                10, 70, 12, QuarterTurn.CLOCKWISE_180, LayoutVariant.EXPANDABLE,
                PlayerExecutionMode.BOTS, "a".repeat(64), "b".repeat(64),
                10, 70, 12, 25, 76, 28, 12, 2, 1, 0, 0, 0, 0,
                "create:shaft x4, minecraft:oak_planks x4", 2, "MEDIUM", "MEDIUM",
                true, "light-natural-only/v1");
        FriendlyByteBuf summaryBuffer = new FriendlyByteBuf(Unpooled.buffer());
        ConfirmationSummaryS2C.encode(summary, summaryBuffer);
        assertThat(ConfirmationSummaryS2C.decode(summaryBuffer)).isEqualTo(summary);

        ApprovalResultS2C approval = new ApprovalResultS2C(true, "OK",
                "demolition-approval:" + "c".repeat(64), 1_785_463_320_000L,
                new ProjectWire("b9cc2514-6db4-4a25-81dc-2eb11ec8db25",
                        "create:cogwheel", 4, "AWAITING_APPROVAL", "APPROVAL_ACTIVE", 99,
                        true, 10, 70, 12, QuarterTurn.CLOCKWISE_180,
                        LayoutVariant.EXPANDABLE, PlayerExecutionMode.BOTS));
        FriendlyByteBuf approvalBuffer = new FriendlyByteBuf(Unpooled.buffer());
        ApprovalResultS2C.encode(approval, approvalBuffer);
        assertThat(ApprovalResultS2C.decode(approvalBuffer)).isEqualTo(approval);

        ClearingStatusS2C clearing = new ClearingStatusS2C(true, "RUNNING",
                approval.project(), true, false, false, "CLEAR", 2, 4,
                2, 3, 1, 2, false);
        FriendlyByteBuf clearingBuffer = new FriendlyByteBuf(Unpooled.buffer());
        ClearingStatusS2C.encode(clearing, clearingBuffer);
        assertThat(ClearingStatusS2C.decode(clearingBuffer)).isEqualTo(clearing);

        BindMaterialSourceC2S source = new BindMaterialSourceC2S(
                approval.project().projectId(), 99, 14, 71, 16, Direction.NORTH);
        FriendlyByteBuf sourceBuffer = new FriendlyByteBuf(Unpooled.buffer());
        BindMaterialSourceC2S.encode(source, sourceBuffer);
        assertThat(BindMaterialSourceC2S.decode(sourceBuffer)).isEqualTo(source);

        MaterialControlC2S control = new MaterialControlC2S(
                approval.project().projectId(), 99, MaterialAction.CONFIRM, true);
        FriendlyByteBuf controlBuffer = new FriendlyByteBuf(Unpooled.buffer());
        MaterialControlC2S.encode(control, controlBuffer);
        assertThat(MaterialControlC2S.decode(controlBuffer)).isEqualTo(control);

        MaterialSnapshotS2C materials = new MaterialSnapshotS2C(true,
                "MATERIAL_LEDGER_BALANCED", approval.project(), 2, 16, true, true,
                List.of(new MaterialLineWire("create:shaft", 12, 12),
                        new MaterialLineWire("create:andesite_alloy", 4, 8)),
                new CompletionReportWire(16, 16, 15, 1, 4, 4, 4,
                        0, 0, 0, 0, true), "material snapshot is authoritative");
        FriendlyByteBuf materialBuffer = new FriendlyByteBuf(Unpooled.buffer());
        MaterialSnapshotS2C.encode(materials, materialBuffer);
        assertThat(MaterialSnapshotS2C.decode(materialBuffer)).isEqualTo(materials);

        MaterialSnapshotS2C materialFailure = MaterialSnapshotS2C.failure(
                "SURVIVAL_MATERIAL_BINDING_REQUIRED", "create:creative_motor=creative_only_power_source");
        FriendlyByteBuf materialFailureBuffer = new FriendlyByteBuf(Unpooled.buffer());
        MaterialSnapshotS2C.encode(materialFailure, materialFailureBuffer);
        assertThat(MaterialSnapshotS2C.decode(materialFailureBuffer)).isEqualTo(materialFailure);

        MetalPressControlC2S metalPressControl = new MetalPressControlC2S(
                MetalPressAction.CANCEL);
        FriendlyByteBuf metalPressControlBuffer = new FriendlyByteBuf(Unpooled.buffer());
        MetalPressControlC2S.encode(metalPressControl, metalPressControlBuffer);
        assertThat(MetalPressControlC2S.decode(metalPressControlBuffer))
                .isEqualTo(metalPressControl);

        MetalPressStatusS2C metalPress = new MetalPressStatusS2C(true,
                "STATUS_REFRESHED", "b9cc2514-6db4-4a25-81dc-2eb11ec8db25",
                "PROCESSING", "IRON_INGOT_ADMITTED", 75,
                14, 71, 16, 20, 70, 24,
                16, 16, 2, 14, 2_400, 1, false, false,
                true, 0, 0, 0, 0, 0, 0, true, true);
        FriendlyByteBuf metalPressBuffer = new FriendlyByteBuf(Unpooled.buffer());
        MetalPressStatusS2C.encode(metalPress, metalPressBuffer);
        assertThat(MetalPressStatusS2C.decode(metalPressBuffer)).isEqualTo(metalPress);

        MetalPressStatusS2C failure = MetalPressStatusS2C.failure("NO_ACTIVE_IE_ORDER");
        FriendlyByteBuf failureBuffer = new FriendlyByteBuf(Unpooled.buffer());
        MetalPressStatusS2C.encode(failure, failureBuffer);
        assertThat(MetalPressStatusS2C.decode(failureBuffer)).isEqualTo(failure);
    }

    @Test
    void compositeStatusPacketRoundTripsBoundedNodeAndBufferEvidence() {
        CompositeControlC2S control = new CompositeControlC2S(CompositeAction.CANCEL);
        FriendlyByteBuf controlBuffer = new FriendlyByteBuf(Unpooled.buffer());
        CompositeControlC2S.encode(control, controlBuffer);
        assertThat(CompositeControlC2S.decode(controlBuffer)).isEqualTo(control);

        CompositeStatusS2C status = new CompositeStatusS2C(true, "STATUS_REFRESHED",
                "b9cc2514-6db4-4a25-81dc-2eb11ec8db25", "steve_industrial:composite/01",
                "a".repeat(64), 7,
                List.of(new NodeWire("steve_industrial:composite/01_cut_log", "SUCCEEDED"),
                        new NodeWire("steve_industrial:composite/01_cut_planks", "RUNNING")),
                List.of(new BufferWire("steve_industrial:composite/01_stripped_route",
                        "minecraft:stripped_oak_log", 1)));
        FriendlyByteBuf statusBuffer = new FriendlyByteBuf(Unpooled.buffer());
        CompositeStatusS2C.encode(status, statusBuffer);
        assertThat(CompositeStatusS2C.decode(statusBuffer)).isEqualTo(status);

        CompositeStatusS2C failure = CompositeStatusS2C.failure("NO_ACTIVE_COMPOSITE_ORDER");
        FriendlyByteBuf failureBuffer = new FriendlyByteBuf(Unpooled.buffer());
        CompositeStatusS2C.encode(failure, failureBuffer);
        assertThat(CompositeStatusS2C.decode(failureBuffer)).isEqualTo(failure);

        CompositeCompletionS2C completion = new CompositeCompletionS2C(true, "COMPLETED",
                "b9cc2514-6db4-4a25-81dc-2eb11ec8db25", "steve_industrial:composite/01",
                "minecraft:oak_planks", "REPORT_GENERATED", 8,
                List.of(new CompletionRowWire("minecraft:oak_log", 4, 4, 2, 2, 0, 0, 0),
                        new CompletionRowWire("minecraft:oak_planks", 0, 0, 0, 0, 1, 0, 0)),
                1, 0, 0, 0, 0, 0, 0, true, true, "c".repeat(64));
        FriendlyByteBuf completionBuffer = new FriendlyByteBuf(Unpooled.buffer());
        CompositeCompletionS2C.encode(completion, completionBuffer);
        assertThat(CompositeCompletionS2C.decode(completionBuffer)).isEqualTo(completion);

        CompositeCompletionS2C completionFailure =
                CompositeCompletionS2C.failure("NO_COMPOSITE_COMPLETION_REPORT");
        FriendlyByteBuf completionFailureBuffer = new FriendlyByteBuf(Unpooled.buffer());
        CompositeCompletionS2C.encode(completionFailure, completionFailureBuffer);
        assertThat(CompositeCompletionS2C.decode(completionFailureBuffer)).isEqualTo(completionFailure);
    }
}
