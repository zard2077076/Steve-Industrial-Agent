package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.GoalCatalogEntry;
import dev.stevecreate.agent.forge1201.player.PlayerGoalCatalog;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.level.Level;

/**
 * A single-machine goal, reviewed if one exists and derived from the live registry
 * otherwise.
 *
 * <p>The same shape as {@link CompositeOrderResolver}, and for the same reason: a player
 * naming a target should not have to know whether somebody enumerated it by hand. The
 * eleven reviewed entries win when they match, because they carry decisions the registry
 * cannot express — how many machines run side by side, which deployment flow the target
 * belongs to, and in one case a material constraint no other field reveals.
 *
 * <p>This lives in the command package rather than beside the catalog on purpose.
 * {@code PlayerGoalCatalog} is in the player package and the recipe projection is here;
 * having the catalog reach for the projection would close a cycle between them.
 */
public final class SingleMachineGoalResolver {

    private SingleMachineGoalResolver() {}

    /**
     * The entry for a target, or empty when nothing reviewed or admitted produces it.
     *
     * <p>A derived entry reports {@code executionVerified() == false}, which is the
     * truth — the registry says the recipe exists, not that anything has run it.</p>
     */
    public static Optional<GoalCatalogEntry> resolve(Level level, ResourceId target) {
        Objects.requireNonNull(target, "target");
        Optional<GoalCatalogEntry> reviewed = PlayerGoalCatalog.find(target);
        if (reviewed.isPresent() || level == null) return reviewed;
        return LiveRecipeCatalog.admittedRecipes(level).stream()
                .filter(entry -> entry.target().equals(target))
                .findFirst();
    }

    /** Whether this target came from the reviewed catalog rather than the registry. */
    public static boolean reviewed(ResourceId target) {
        return PlayerGoalCatalog.find(target).isPresent();
    }
}
