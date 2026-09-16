package dev.stevecreate.agent.core.mekanism;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.CapabilityResourceRequirement;
import dev.stevecreate.agent.core.planning.CapabilityVersionLimits;
import dev.stevecreate.agent.core.planning.MachineCapability;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What a Mekanism adapter will have to declare, written before the dependency is pinned.
 *
 * <p>M-02 through M-04 are blocked on an official Mekanism dependency, which this repository
 * does not have: `build.gradle` names Mekanism only through `mekanismAbsentSmoke`, the gate that
 * proves the mod's *absence* degrades safely. This contract is the part that can be settled
 * without the jar — the shape an adapter must fill — so that pinning the dependency is followed
 * by implementing against a fixed target rather than by designing one.</p>
 *
 * <h2>Nothing here is verified</h2>
 *
 * <p>Every declaration carries an empty {@code acceptedRuntimeFingerprints}. That is not an
 * oversight and does not need a new flag: {@link CapabilityVersionLimits} already means "these
 * are the runtimes this capability has been accepted against", and an empty set means none.
 * A capability in this state cannot pass a runtime-bound gate, which is the correct outcome for
 * a declaration nobody has run.</p>
 *
 * <h2>Why no energy conversion ratio appears here</h2>
 *
 * <p>The obvious thing to add is Joules-per-FE so plans can size a generator. It is deliberately
 * absent. Mekanism's energy conversion is a server configuration value, so any ratio written
 * here would be a guess that reads like a measurement — and the existing {@code electrical}
 * package already models real FE networks, ledgers and voltage tiers for the Immersive
 * Engineering line. When the dependency lands, the ratio must be read from the running
 * configuration and carried as runtime evidence, exactly as Create's speeds are.</p>
 */
public final class MekanismCapabilityContractV1 {
    /**
     * Candidate Mekanism coordinate for 1.20.1, from the public Maven record.
     *
     * <p>Not verified against this repository: no build has resolved it, and its compatibility
     * with Forge 47.4.10 is unconfirmed. M-02 begins by settling this.</p>
     */
    public static final String CANDIDATE_PIN = "1.20.1-10.4.9.61";

    public static final ResourceId ADAPTER_ID = id("steve_industrial:mekanism_declared_v1");
    public static final ResourceId ENRICHING = id("mekanism:enriching");
    public static final ResourceId ENRICHMENT_CHAMBER = id("mekanism:enrichment_chamber");

    /** The action id this contract requires an adapter to register. Nothing implements it yet. */
    public static final ResourceId ENRICHING_ACTION =
            id("steve_industrial:mekanism/enriching_action");

    private MekanismCapabilityContractV1() {}

    /**
     * The Enrichment Chamber, M-04's target: one item in, one item out, electrical energy.
     *
     * <p>Chosen because it is the simplest machine that still exercises what Create never
     * did — {@link GenericResourceType#ELECTRICAL_ENERGY} as the process requirement instead of
     * rotational power. Chemical-carrying machines are intentionally not declared yet; see
     * {@link MekanismChemicalForm} for why that distinction cannot be flattened.</p>
     */
    public static MachineCapability enrichmentChamber() {
        return new MachineCapability(
                ENRICHING,
                ADAPTER_ID,
                Set.of(ENRICHING),
                Set.of(GenericResourceType.ITEM),
                Set.of(GenericResourceType.ITEM),
                // Continuous, and only a positive lower bound: the real draw depends on the
                // recipe and on upgrades, so a fixed number here would be invented.
                Set.of(new CapabilityResourceRequirement(
                        GenericResourceType.ELECTRICAL_ENERGY, 1, true)),
                // The action a Mekanism adapter must register to satisfy this capability.
                // No handler answers to it yet: naming it here is what makes the gap explicit
                // instead of leaving the capability actionless, which the bounds refuse.
                Set.of(ENRICHING_ACTION),
                Set.of(
                        VerificationEvidenceKind.BLOCK_PRESENT,
                        VerificationEvidenceKind.POWER_PRESENT,
                        VerificationEvidenceKind.INPUT_CONSUMED,
                        VerificationEvidenceKind.OUTPUT_PRODUCED),
                Set.of(
                        id("steve_industrial:no_electrical_energy"),
                        id("steve_industrial:machine_not_formed")),
                unverified());
    }

    /** Every capability this contract declares, in canonical order. */
    public static List<MachineCapability> declaredCapabilities() {
        return List.of(enrichmentChamber());
    }

    /**
     * The version this contract is written against, with no accepted runtime fingerprint.
     *
     * <p>{@link CapabilityVersionLimits} refuses a declaration that bounds nothing at all —
     * correctly, since a capability that accepts every runtime is not a claim. So the candidate
     * pin is named as the lower bound while the fingerprint set stays empty, and it is the empty
     * set that keeps these declarations out of any runtime-bound gate.</p>
     *
     * <p>{@value #CANDIDATE_PIN} is taken from the public Maven record for Mekanism on 1.20.1.
     * It is a candidate, not a verified pin: nobody has built against it here, and its
     * compatibility with this repository's Forge 47.4.10 is unconfirmed. Confirming it is the
     * first step of M-02.</p>
     */
    public static CapabilityVersionLimits unverified() {
        return new CapabilityVersionLimits(
                Optional.of(CANDIDATE_PIN), Optional.empty(), Set.of());
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
