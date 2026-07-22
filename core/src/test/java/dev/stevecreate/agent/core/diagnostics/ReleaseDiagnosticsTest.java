package dev.stevecreate.agent.core.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ReleaseDiagnosticsTest {
    @Test
    void rendersOnlyTheSmallPublicAllowlistAndDeclaresEveryRedaction() {
        String report = ReleaseDiagnostics.render(Map.of(
                "version", "0.1.0-alpha.1", "minecraft", "1.20.1",
                "network", "false", "telemetry", "false"));
        assertThat(report).contains("version=0.1.0-alpha.1", "network=false",
                "telemetry=false", "automaticUpload=false", "redactedFields=");
    }

    @Test
    void redactsAbsolutePathsAndSecretShapedValues() {
        String report = ReleaseDiagnostics.render(Map.of(
                "os", "C:\\Users\\player\\machine", "gitCommit", "token=do-not-print"));
        assertThat(report).doesNotContain("machine", "do-not-print")
                .contains("os=[REDACTED]", "gitCommit=[REDACTED]");
    }

    @Test
    void refusesUnknownFieldsInsteadOfAccidentallyExportingPrivateState() {
        assertThatThrownBy(() -> ReleaseDiagnostics.render(Map.of("chat", "hello")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void archiveContainsOnlyOneRedactedDiagnosticTextEntry() throws Exception {
        byte[] archive = ReleaseDiagnostics.archive(Map.of(
                "version", "0.1.0-alpha.1", "os", "C:\\Users\\private\\host",
                "network", "false", "telemetry", "false"));
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getNextEntry();
            assertThat(entry.getName()).isEqualTo("diagnostics.txt");
            String report = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(report).contains("os=[REDACTED]", "automaticUpload=false")
                    .doesNotContain("private", "host");
            assertThat(zip.getNextEntry()).isNull();
        }
    }
}
