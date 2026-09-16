package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.stevecreate.agent.adapter.api.create.CreateCapabilityId;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CreateV606RuntimeObserversTest {
    @Test
    void everyExpansionCapabilityRoutesToItsVersionConfinedReadOnlyObserver() {
        Map<CreateCapabilityId, Class<?>> expected = new EnumMap<>(CreateCapabilityId.class);
        expected.put(CreateCapabilityId.CRUSHING, CreateV606CrushingRuntimeObserver.class);
        expected.put(CreateCapabilityId.FAN_WASHING, CreateV606FanRuntimeObserver.class);
        expected.put(CreateCapabilityId.FAN_SMOKING, CreateV606FanRuntimeObserver.class);
        expected.put(CreateCapabilityId.FAN_HAUNTING, CreateV606FanRuntimeObserver.class);
        expected.put(CreateCapabilityId.FAN_BLASTING, CreateV606FanRuntimeObserver.class);
        expected.put(CreateCapabilityId.CUTTING, CreateV606CuttingRuntimeObserver.class);
        expected.put(CreateCapabilityId.MIXING_PHASE_I, CreateV606MixingRuntimeObserver.class);
        expected.put(CreateCapabilityId.COMPACTING_PHASE_I, CreateV606CompactingRuntimeObserver.class);
        expected.put(CreateCapabilityId.DEPLOYING_PHASE_I, CreateV606DeployingRuntimeObserver.class);

        assertEquals(CreateCapabilityId.values().length, expected.size());
        expected.forEach((capability, type) -> {
            CreateV606RuntimeObserver observer = CreateV606RuntimeObservers.forCapability(capability);
            assertEquals(type, observer.getClass());
            assertSame(observer, CreateV606RuntimeObservers.forCapability(capability));
        });
    }
}
