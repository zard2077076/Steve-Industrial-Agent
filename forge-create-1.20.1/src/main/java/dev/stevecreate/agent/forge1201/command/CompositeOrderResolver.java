package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.industrial.CompositeGraphExpander;
import dev.stevecreate.agent.core.industrial.CompositePlayerOrderCatalogV1;
import dev.stevecreate.agent.core.industrial.CompositePlayerOrderSpecV1;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;

/**
 * Finds the order behind a request, whether someone wrote it or it was derived.
 *
 * <p>Until now only the two reviewed graphs could be ordered, which meant automatic
 * derivation had been shown to work and could not be used. A derived order is not a
 * lesser one: it goes through the same layout, the same physical contract, the same
 * reservation ledger and the same wrapper, and the contract refuses it offline if the
 * site cannot be built.</p>
 *
 * <p>Reviewed graphs win over derived ones for the same identity. They carry real
 * three-mode acceptance evidence, and silently substituting a freshly derived
 * alternative would discard that.</p>
 */
final class CompositeOrderResolver {
    /** Prefix {@link CompositeGraphExpander} stamps on the orders it synthesises. */
    static final String DERIVED_PREFIX = "steve_industrial:derived/";

    private CompositeOrderResolver() {}

    /**
     * @param orderType either a reviewed order id, or a product to derive a chain for
     */
    static Resolution resolve(ServerLevel level, ResourceId orderType, long targetQuantity) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(orderType, "orderType");
        if (orderType.toString().startsWith(DERIVED_PREFIX)) {
            // A derived spec's orderType is a synthesised identity, not a product. Re-
            // resolving one would look for a recipe producing it and report the
            // misleading "no recipe for" rather than the caller's actual mistake.
            return Resolution.refused("COMPOSITE_ORDER_TYPE_IS_A_DERIVED_IDENTITY:"
                    + "resolve the product id instead of " + orderType);
        }
        Optional<CompositePlayerOrderSpecV1> reviewed =
                CompositePlayerOrderCatalogV1.find(orderType);
        if (reviewed.isPresent()) {
            return new Resolution(true, "REVIEWED", reviewed.get(), false);
        }
        CompositeGraphExpander.Result derived = CompositeGraphExpander.expand(
                orderType, targetQuantity,
                LiveRecipeCatalog.admittedRecipes(level),
                LiveRecipeCatalog.rawMaterials(level));
        if (!derived.success()) {
            return Resolution.refused(derived.code());
        }
        if (derived.singleMachine().isPresent()) {
            // One recipe is one machine, and the classification stays — C3a draws it
            // deliberately and the single-machine order path is still where a player's
            // one-off goes. What changed is that refusing here also kept those goals off
            // the production path entirely: the ledger, multi-source reservation, the
            // carry, residency, unattended dispatch and restart recovery are all built
            // on this one and on no other, so eighty-five products had none of it while
            // a hundred and eleven cutting chains had all of it.
            //
            // A one-stage spec is an addition rather than a reclassification. The graph
            // and the executor both accept it without any rule being relaxed: a single
            // stage has no edges and needs none.
            CompositeGraphExpander.Result asChain =
                    CompositeGraphExpander.expandSingleMachineAsChain(
                            derived.singleMachine().orElseThrow(), targetQuantity,
                            LiveRecipeCatalog.rawMaterials(level));
            if (!asChain.success()) {
                return Resolution.refused("COMPOSITE_SINGLE_MACHINE_NOT_EXPRESSIBLE:"
                        + asChain.code());
            }
            CompositePlayerOrderSpecV1 oneStage = asChain.spec().orElseThrow();
            // Held to the same layout probe as any other derived order. A one-stage site
            // is smaller, not exempt: if it cannot physically be stood up, saying so here
            // is the whole point of resolving offline.
            return CompositeSiteLayoutProbe.isBuildable(oneStage)
                    ? Resolution.derived(oneStage)
                    : Resolution.refused("COMPOSITE_DERIVED_LAYOUT_NOT_BUILDABLE");
        }
        CompositePlayerOrderSpecV1 spec = derived.spec().orElseThrow();
        if (!CompositeSiteLayoutProbe.isBuildable(spec)) {
            return Resolution.refused("COMPOSITE_DERIVED_LAYOUT_NOT_BUILDABLE");
        }
        return new Resolution(true, "DERIVED", spec, true);
    }

    record Resolution(boolean success, String code, CompositePlayerOrderSpecV1 spec, boolean derived) {
        Resolution {
            Objects.requireNonNull(code, "code");
        }

        static Resolution refused(String code) {
            return new Resolution(false, code, null, false);
        }

        static Resolution derived(CompositePlayerOrderSpecV1 spec) {
            return new Resolution(true, "COMPOSITE_ORDER_DERIVED", spec, true);
        }
    }
}
