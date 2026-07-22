package dev.stevecreate.agent.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ResourceIdTest {
    @Test
    void parsesNamespacedIds() {
        ResourceId id = ResourceId.parse("create:mechanical_press");
        assertThat(id.namespace()).isEqualTo("create");
        assertThat(id.path()).isEqualTo("mechanical_press");
    }

    @Test
    void rejectsFreeText() {
        assertThatThrownBy(() -> ResourceId.parse("mechanical press"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

