package dev.stevecreate.agent.forge1201.electrical;

import dev.stevecreate.agent.core.electrical.ElectricalNodeKind;
import dev.stevecreate.agent.core.model.BlockPos3i;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Tells a generator from a battery by watching, because the capability will not say.
 *
 * <p>{@link EnergyEndpointDiscovery} classifies by {@code canExtract} and
 * {@code canReceive}, which is all Forge's {@code IEnergyStorage} exposes — and on real
 * Immersive Engineering blocks every endpoint answers yes to both, so that classification
 * has never once distinguished a generator from a machine in a live world. There is no
 * {@code canGenerate} to read.
 *
 * <p>What can be observed is behaviour over time: energy that appears with nothing feeding
 * it came from generation, and energy that disappears went into work. This samples stored
 * energy across ticks and reports the trend, which is evidence rather than a flag.
 *
 * <p>It draws no conclusion from a single reading and none at all from a flat one — a
 * charged battery, an idle generator and a machine with nothing to do are genuinely
 * indistinguishable while nothing is moving, and reporting one of them would be inventing
 * a fact.
 */
public final class EnergyBehaviourSampler {
    /** Below this a change is noise from rounding or a single tick of transfer. */
    public static final long SIGNIFICANT_FE = 1;

    private final Map<BlockPos3i, Sample> samples = new LinkedHashMap<>();

    /** One endpoint's first and latest readings. */
    public record Sample(long firstFe, long latestFe, int readings, ElectricalNodeKind declared) {
        /** Net change since the first reading; positive means energy appeared. */
        public long deltaFe() {
            return latestFe - firstFe;
        }
    }

    /** What a trend says a node is doing, as opposed to what its flags permit. */
    public enum Observed { GENERATING, CONSUMING, STEADY }

    /** Takes one reading of every endpoint near {@code centre}. */
    public void observe(ServerLevel level, BlockPos centre, int radius) {
        Objects.requireNonNull(level, "level");
        for (EnergyEndpointDiscovery.Endpoint endpoint
                : EnergyEndpointDiscovery.endpoints(level, centre, radius, List.of())) {
            samples.compute(endpoint.position(), (position, existing) -> existing == null
                    ? new Sample(endpoint.storedFe(), endpoint.storedFe(), 1, endpoint.kind())
                    : new Sample(existing.firstFe(), endpoint.storedFe(),
                            existing.readings() + 1, existing.declared()));
        }
    }

    /**
     * What each sampled endpoint was observed doing.
     *
     * <p>An endpoint seen only once has no trend and is reported STEADY, because one
     * reading is a level and not a direction.</p>
     */
    public Map<BlockPos3i, Observed> behaviour() {
        Map<BlockPos3i, Observed> result = new LinkedHashMap<>();
        samples.forEach((position, sample) -> result.put(position,
                sample.readings() < 2 || Math.abs(sample.deltaFe()) < SIGNIFICANT_FE
                        ? Observed.STEADY
                        : sample.deltaFe() > 0 ? Observed.GENERATING : Observed.CONSUMING));
        return Map.copyOf(result);
    }

    /** The raw readings, for evidence a caller can print rather than a verdict to trust. */
    public Map<BlockPos3i, Sample> samples() {
        return Map.copyOf(samples);
    }

    /** Whether anything was observed actually producing energy. */
    public Optional<BlockPos3i> firstGenerating() {
        return behaviour().entrySet().stream()
                .filter(entry -> entry.getValue() == Observed.GENERATING)
                .map(Map.Entry::getKey)
                .findFirst();
    }
}
