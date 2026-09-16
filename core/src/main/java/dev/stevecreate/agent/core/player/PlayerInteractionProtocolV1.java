package dev.stevecreate.agent.core.player;

import dev.stevecreate.agent.core.execution.construction.MaterialExecutorKind;
import dev.stevecreate.agent.core.execution.construction.MaterialTransactionState;
import java.util.List;

/**
 * Frozen Phase IV player interaction and item-material compatibility surface.
 *
 * <p>Phase V and later features must be additive. Existing saves and clients keep this exact
 * vocabulary; warehouse, fluid, electrical and production-order protocols use separate versioned
 * envelopes rather than silently changing this contract.</p>
 */
public final class PlayerInteractionProtocolV1 {
    public static final String NETWORK_PROTOCOL = "phase-iv-player-workflow-v3";
    public static final int WORKFLOW_SAVED_DATA_SCHEMA = 2;
    public static final int MATERIAL_SAVED_DATA_SCHEMA = 3;
    public static final int SALVAGE_SAVED_DATA_SCHEMA = 1;
    public static final int MAX_MATERIAL_SOURCES = 8;

    public static final List<WorkflowStage> WORKFLOW_STAGES = List.of(
            WorkflowStage.GOAL_SELECTION,
            WorkflowStage.PLACEMENT_PREVIEW,
            WorkflowStage.SITE_SURVEY,
            WorkflowStage.AWAITING_APPROVAL,
            WorkflowStage.CLEARING,
            WorkflowStage.POST_CLEAR_RESCAN,
            WorkflowStage.MATERIAL_SOURCE_SELECTION,
            WorkflowStage.MATERIAL_RESERVED,
            WorkflowStage.CONSTRUCTION,
            WorkflowStage.PAUSED,
            WorkflowStage.COMPLETED,
            WorkflowStage.CANCELLED,
            WorkflowStage.REFUSED);

    public static final List<PlayerExecutionMode> EXECUTION_MODES = List.of(
            PlayerExecutionMode.SMART_RECOMMENDED,
            PlayerExecutionMode.DIRECT,
            PlayerExecutionMode.BOTS,
            PlayerExecutionMode.HYBRID);

    public static final List<MaterialTransactionState> ITEM_TRANSACTION_STATES = List.of(
            MaterialTransactionState.PREPARED,
            MaterialTransactionState.WITHDRAWN,
            MaterialTransactionState.DELIVERED,
            MaterialTransactionState.CONSUMED,
            MaterialTransactionState.RELEASED,
            MaterialTransactionState.RETURN_PENDING,
            MaterialTransactionState.RETURNED);

    public static final List<MaterialExecutorKind> MATERIAL_EXECUTORS = List.of(
            MaterialExecutorKind.DIRECT,
            MaterialExecutorKind.BOT,
            MaterialExecutorKind.HYBRID,
            MaterialExecutorKind.SALVAGE_TRANSFER);

    private PlayerInteractionProtocolV1() {}
}
