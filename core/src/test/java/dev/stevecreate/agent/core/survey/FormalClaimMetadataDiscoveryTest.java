package dev.stevecreate.agent.core.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.stevecreate.agent.core.deployment.PermissionDecision;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormalClaimMetadataDiscoveryTest {
    @Test
    void detectsOpacVersionAndHintsButKeepsFormalPermissionUnknown() {
        ClaimMetadataDocument descriptor = descriptor(
                "openpartiesandclaims", "0.25.8", "Open Parties and Claims");
        ClaimMetadataDocument config = new ClaimMetadataDocument("serverconfig/openpartiesandclaims-server.toml",
                Map.of("modId", "openpartiesandclaims", "configPath", "serverconfig/openpac-server.toml",
                        "dataSource", "data/openpartiesandclaims", "apiHint", "xaero.pac.common.server.api"));

        ClaimPermissionDiscoveryResult result = new FormalClaimMetadataDiscovery()
                .discover(List.of(config, descriptor));

        assertThat(result.permissionDecision()).isEqualTo(PermissionDecision.UNKNOWN);
        assertThat(result.detectedMods()).singleElement().satisfies(mod -> {
            assertThat(mod.modId()).isEqualTo("openpartiesandclaims");
            assertThat(mod.version()).isEqualTo("0.25.8");
            assertThat(mod.adapterImplemented()).isFalse();
            assertThat(mod.permissionVerified()).isFalse();
            assertThat(mod.possibleDataSources()).containsExactly("data/openpartiesandclaims");
        });
        assertThat(result.requiredAdapterTasks())
                .containsExactly("IMPLEMENT_READ_ONLY_CLAIM_ADAPTER:openpartiesandclaims:0.25.8");
        assertThat(result.limitations()).extracting(SurveyLimitation::code)
                .containsExactly("CLAIM_ADAPTER_REQUIRED");
        assertThat(result.credentialsRead()).isFalse();
        assertThat(result.privateDatabaseRead()).isFalse();
        assertThat(result.remoteAccessPerformed()).isFalse();
        assertThat(result.claimModified()).isFalse();
        assertThat(result.formalExecutionAllowed()).isFalse();
    }

    @Test
    void absentKnownClaimMetadataStillReturnsUnknownRatherThanAllowed() {
        ClaimPermissionDiscoveryResult result = new FormalClaimMetadataDiscovery().discover(List.of(
                descriptor("unrelatedmod", "1.0.0", "Unrelated Mod")));

        assertThat(result.detectedMods()).isEmpty();
        assertThat(result.permissionDecision()).isEqualTo(PermissionDecision.UNKNOWN);
        assertThat(result.limitations()).extracting(SurveyLimitation::code)
                .containsExactly("CLAIM_PERMISSION_UNKNOWN");
    }

    @Test
    void rejectsCredentialsPrivateDatabasesAndOutsideMetadataPaths() {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new ClaimMetadataDocument("config/opac.toml", Map.of("password", "secret")));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new ClaimMetadataDocument("config/opac.toml", Map.of("dataSource", "https://remote/db")));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new ClaimMetadataDocument("config/opac.toml", Map.of("apiHint", "token=secret")));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new FormalClaimMetadataDiscovery().discover(List.of(
                        new ClaimMetadataDocument("world/data/claims.dat", Map.of("modId", "openpartiesandclaims")))));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new FormalClaimMetadataDiscovery().discover(List.of(
                        new ClaimMetadataDocument("config/claims-database.toml",
                                Map.of("modId", "openpartiesandclaims")))));
    }

    @Test
    void outputIsDeterministicAndConflictingDescriptorsFailClosed() {
        ClaimMetadataDocument opac = descriptor("openpartiesandclaims", "0.25.8", "Open Parties and Claims");
        ClaimMetadataDocument ftb = descriptor("ftbchunks", "2001.3.1", "FTB Chunks");
        FormalClaimMetadataDiscovery service = new FormalClaimMetadataDiscovery();

        assertThat(service.discover(List.of(opac, ftb))).isEqualTo(service.discover(List.of(ftb, opac)));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> service.discover(List.of(
                opac, descriptor("openpartiesandclaims", "0.26.0", "Open Parties and Claims"))));
    }

    @Test
    void discoveryIsBoundedAndCannotConstructAuthoritativeResult() {
        ClaimMetadataDocument descriptor = descriptor(
                "openpartiesandclaims", "0.25.8", "Open Parties and Claims");
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new FormalClaimMetadataDiscovery().discover(java.util.Collections.nCopies(129, descriptor)));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new ClaimPermissionDiscoveryResult(List.of(), PermissionDecision.ALLOWED, List.of(),
                        List.of(new SurveyLimitation("CLAIM_PERMISSION_UNKNOWN", "formal-world",
                                "unknown", true, true)), false, false, false, false, true));
    }

    private static ClaimMetadataDocument descriptor(String modId, String version, String displayName) {
        return new ClaimMetadataDocument("mods/" + modId + "-" + version + ".jar!/META-INF/mods.toml",
                Map.of("modId", modId, "version", version, "displayName", displayName));
    }
}
