package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Exact safe action vocabulary for a controlled Bot worker. */
public enum BotWorkerCapability {
    REGISTRATION,
    NAVIGATE_TO,
    REACHABILITY,
    LOOK_AT,
    FETCH_MATERIAL,
    CARRY_MATERIAL,
    DELIVER_MATERIAL,
    PLACE_BLOCK,
    REMOVE_SESSION_OWNED_BLOCK,
    REMOVE_APPROVED_TERRAIN_BLOCK,
    COLLECT_APPROVED_SALVAGE,
    DELIVER_APPROVED_SALVAGE,
    FILL_APPROVED_TERRAIN,
    VERIFY_PREPARED_GROUND,
    INTERACT_SAFE_MACHINE_FACE,
    VERIFY_BLOCK_STATE,
    RETURN_LEFTOVERS,
    REPORT_EVIDENCE,
    RETRY_REPATH,
    CANCEL,
    RELOAD_RECOVERY;

    private final ResourceId id = ResourceId.parse(
            "bot:" + name().toLowerCase(Locale.ROOT));

    public ResourceId id() {
        return id;
    }

    public static Set<BotWorkerCapability> requiredFor(TaskKind kind) {
        return switch (kind) {
            case RESERVE_PLACEMENT, RESERVE_MATERIAL, RELEASE_RESERVATION ->
                    EnumSet.of(REGISTRATION, REPORT_EVIDENCE);
            case FETCH_MATERIAL -> EnumSet.of(
                    NAVIGATE_TO, REACHABILITY, FETCH_MATERIAL, CARRY_MATERIAL, REPORT_EVIDENCE);
            case TRANSPORT_MATERIAL -> EnumSet.of(
                    NAVIGATE_TO, REACHABILITY, CARRY_MATERIAL, DELIVER_MATERIAL, REPORT_EVIDENCE);
            case PLACE_COMPONENT -> EnumSet.of(
                    NAVIGATE_TO, REACHABILITY, LOOK_AT, PLACE_BLOCK,
                    VERIFY_BLOCK_STATE, REPORT_EVIDENCE);
            case REMOVE_SESSION_OWNED_COMPONENT -> EnumSet.of(
                    NAVIGATE_TO, REACHABILITY, LOOK_AT, REMOVE_SESSION_OWNED_BLOCK,
                    VERIFY_BLOCK_STATE, REPORT_EVIDENCE);
            case CONNECT_COMPONENTS -> EnumSet.of(
                    NAVIGATE_TO, REACHABILITY, LOOK_AT, PLACE_BLOCK,
                    INTERACT_SAFE_MACHINE_FACE, VERIFY_BLOCK_STATE, REPORT_EVIDENCE);
            case SAFE_MACHINE_INTERACTION -> EnumSet.of(
                    NAVIGATE_TO, REACHABILITY, LOOK_AT,
                    INTERACT_SAFE_MACHINE_FACE, REPORT_EVIDENCE);
            case VERIFY_STATE, VERIFY_OUTPUT -> EnumSet.of(
                    REACHABILITY, VERIFY_BLOCK_STATE, REPORT_EVIDENCE);
            case RETURN_MATERIAL -> EnumSet.of(
                    NAVIGATE_TO, REACHABILITY, CARRY_MATERIAL,
                    RETURN_LEFTOVERS, REPORT_EVIDENCE);
            case CLEANUP -> EnumSet.of(
                    NAVIGATE_TO, REACHABILITY, REMOVE_SESSION_OWNED_BLOCK,
                    RETURN_LEFTOVERS, VERIFY_BLOCK_STATE, REPORT_EVIDENCE);
        };
    }
}
