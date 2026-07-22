package dev.stevecreate.agent.adapter.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import org.junit.jupiter.api.Test;

class ScanRequestTest {
    @Test
    void refusesUnboundedScan() {
        assertThatThrownBy(() -> new ScanRequest(new BlockPos3i(0, 64, 0), 17))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

