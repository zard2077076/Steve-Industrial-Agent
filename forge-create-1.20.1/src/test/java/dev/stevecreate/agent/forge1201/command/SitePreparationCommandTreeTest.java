package dev.stevecreate.agent.forge1201.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

class SitePreparationCommandTreeTest {
    @Test
    void publishesTheBoundedSiteSelectionApprovalClearingAndPreparedSurface() {
        LiteralArgumentBuilder<CommandSourceStack> builder = SitePreparationCommand.command();
        var root = builder.build();
        assertThat(root.getLiteral()).isEqualTo("site");
        assertThat(root.getChildren().stream().map(value -> value.getName())
                .collect(Collectors.toSet()))
                .isEqualTo(Set.of("anchor", "pos1", "pos2", "region", "facing", "survey",
                        "obstacles", "demolition", "salvage", "clearing", "grade",
                        "prepared"));
        assertThat(root.getChild("demolition").getChildren().stream()
                .map(value -> value.getName()).collect(Collectors.toSet()))
                .isEqualTo(Set.of("preview", "approve-safe", "approve", "revoke", "status"));
        assertThat(root.getChild("clearing").getChildren().stream()
                .map(value -> value.getName()).collect(Collectors.toSet()))
                .isEqualTo(Set.of("start", "cancel", "status"));
        assertThat(root.getChild("grade").getChildren().stream()
                .map(value -> value.getName()).collect(Collectors.toSet()))
                .isEqualTo(Set.of("corner1", "corner2", "height", "supply", "start",
                        "force-confirm", "status", "cancel"));
    }

    @Test
    void preservesTypedClearingFailureCodesAtThePlayerCommandBoundary() {
        assertThat(SitePreparationCommand.clearingFailureCode(
                "site-prep:demolition_approval_stale: exact rescan changed"))
                .isEqualTo("DEMOLITION_APPROVAL_STALE");
        assertThat(SitePreparationCommand.clearingFailureCode(
                "BOT_CLEARING_TOOL_UNAVAILABLE"))
                .isEqualTo("BOT_CLEARING_TOOL_UNAVAILABLE");
        assertThat(SitePreparationCommand.clearingFailureCode(
                "unknown movement failure"))
                .isEqualTo("BOT_CLEARING_PATH_UNREACHABLE");
    }
}
