package dev.stevecreate.agent.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.ImmutableRuntimeRecipeCatalog;
import dev.stevecreate.agent.core.planning.RecipeIngredient;
import dev.stevecreate.agent.core.planning.RecipeSource;
import dev.stevecreate.agent.core.planning.RuntimeRecipeCatalogEntry;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuntimeRecipeCatalogContractTest {
    @Test
    void definesEveryRequiredRuntimeKnowledgeFailureCode() {
        assertThat(RuntimeKnowledgeFailureCode.values()).containsExactly(
                RuntimeKnowledgeFailureCode.RUNTIME_RECIPE_MANAGER_UNAVAILABLE,
                RuntimeKnowledgeFailureCode.RECIPE_TYPE_UNSUPPORTED,
                RuntimeKnowledgeFailureCode.INGREDIENT_UNSUPPORTED,
                RuntimeKnowledgeFailureCode.INGREDIENT_CHOICE_UNRESOLVED,
                RuntimeKnowledgeFailureCode.OUTPUT_UNSUPPORTED,
                RuntimeKnowledgeFailureCode.RECIPE_MAPPING_FAILED,
                RuntimeKnowledgeFailureCode.RUNTIME_FINGERPRINT_MISMATCH,
                RuntimeKnowledgeFailureCode.RECIPE_CATALOG_STALE,
                RuntimeKnowledgeFailureCode.CAPABILITY_CATALOG_MISSING,
                RuntimeKnowledgeFailureCode.REQUIRED_MOD_UNAVAILABLE,
                RuntimeKnowledgeFailureCode.RECIPE_NOT_FOUND,
                RuntimeKnowledgeFailureCode.MACHINE_CAPABILITY_MISSING,
                RuntimeKnowledgeFailureCode.QUANTITY_CONVERSION_INVALID,
                RuntimeKnowledgeFailureCode.DATAPACK_RELOAD_IN_PROGRESS,
                RuntimeKnowledgeFailureCode.WRONG_THREAD,
                RuntimeKnowledgeFailureCode.WORLD_NOT_AVAILABLE);
    }

    @Test
    void canonicalizesRuntimeAndCarriesAnImmutableLoaderNeutralSnapshot() {
        RuntimeFingerprint first = runtime(Map.of("create", "6.0.6-150", "mekanism", "10.4.0"));
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("mekanism", "10.4.0");
        reversed.put("create", "6.0.6-150");
        RuntimeFingerprint second = runtime(reversed);
        assertThat(first.canonicalIdentity()).isEqualTo(second.canonicalIdentity());

        RuntimeKnowledgeFailure limitation = limitation(first.canonicalIdentity());
        RuntimeRecipeCatalogSnapshot snapshot = new RuntimeRecipeCatalogSnapshot(
                new ImmutableRuntimeRecipeCatalog(List.of(milling(first.canonicalIdentity()))),
                first,
                first.canonicalIdentity(),
                "minecraft:overworld",
                4,
                2,
                1,
                List.of(limitation),
                List.of(new RuntimeRecipeMappingWarning(
                        id("create:milling/cobblestone"),
                        RuntimeRecipeMappingWarningCode.PROBABILISTIC_BYPRODUCT,
                        List.of("output:1", "chance:0.25"),
                        "Byproduct is not guaranteed")));

        assertThat(snapshot.catalog().recipes()).hasSize(1);
        assertThat(snapshot.canonicalRuntimeFingerprint()).isEqualTo(first.canonicalIdentity());
        assertThat(snapshot.reloadGeneration()).isEqualTo(4);
        assertThat(snapshot.discoveredRecipeCount()).isEqualTo(2);
        assertThat(snapshot.mappedRecipeCount()).isEqualTo(1);
        assertThat(snapshot.limitations()).containsExactly(limitation);
        assertThat(snapshot.warnings()).singleElement().satisfies(warning ->
                assertThat(warning.code())
                        .isEqualTo(RuntimeRecipeMappingWarningCode.PROBABILISTIC_BYPRODUCT));
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> snapshot.limitations().clear());
        assertThat(new RuntimeRecipeCatalogResult.Success(snapshot).snapshot()).isEqualTo(snapshot);
        assertThat(new RuntimeRecipeCatalogResult.Failure(limitation).failure()).isEqualTo(limitation);
    }

    @Test
    void cacheNeverReusesSnapshotsAcrossWorldFingerprintOrReloadGeneration() {
        RuntimeFingerprint runtime = runtime(Map.of("create", "6.0.6-150"));
        RuntimeRecipeCatalogSnapshot snapshot = new RuntimeRecipeCatalogSnapshot(
                new ImmutableRuntimeRecipeCatalog(List.of(milling(runtime.canonicalIdentity()))),
                runtime,
                runtime.canonicalIdentity(),
                "minecraft:overworld",
                7,
                1,
                1,
                List.of(),
                List.of());
        RuntimeRecipeCatalogCache cache = new RuntimeRecipeCatalogCache();

        cache.store(snapshot);
        assertThat(cache.find("minecraft:overworld", runtime.canonicalIdentity(), 7))
                .contains(snapshot);
        assertThat(cache.find("minecraft:the_nether", runtime.canonicalIdentity(), 7)).isEmpty();
        assertThat(cache.find("minecraft:overworld", runtime.canonicalIdentity() + "-changed", 7))
                .isEmpty();
        assertThat(cache.find("minecraft:overworld", runtime.canonicalIdentity(), 8)).isEmpty();

        assertThat(cache.invalidate(8)).isEqualTo(8);
        assertThat(cache.find("minecraft:overworld", runtime.canonicalIdentity(), 7)).isEmpty();
        assertThat(cache.generation()).isEqualTo(8);
        assertThatIllegalArgumentException().isThrownBy(() -> cache.invalidate(8));
    }

    @Test
    void rejectsIncompleteFailureAndInconsistentSnapshotEvidence() {
        RuntimeFingerprint runtime = runtime(Map.of("create", "6.0.6-150"));
        assertThatIllegalArgumentException().isThrownBy(() -> new RuntimeKnowledgeFailure(
                RuntimeKnowledgeFailureCode.RECIPE_MAPPING_FAILED,
                RuntimeKnowledgeStage.RECIPE_MAPPING,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                id("steve_industrial:create_runtime_1_20_1_6_0_6"),
                runtime.canonicalIdentity(),
                List.of(),
                "mapping failed"));
        assertThatIllegalArgumentException().isThrownBy(() -> new RuntimeRecipeCatalogSnapshot(
                new ImmutableRuntimeRecipeCatalog(List.of(milling(runtime.canonicalIdentity()))),
                runtime,
                runtime.canonicalIdentity(),
                "minecraft:overworld",
                1,
                0,
                1,
                List.of(),
                List.of()));
    }

    private static RuntimeKnowledgeFailure limitation(String fingerprint) {
        return new RuntimeKnowledgeFailure(
                RuntimeKnowledgeFailureCode.INGREDIENT_UNSUPPORTED,
                RuntimeKnowledgeStage.RECIPE_MAPPING,
                Optional.of(id("create:milling/complex")),
                Optional.empty(),
                Optional.of("ingredient[0]"),
                id("steve_industrial:create_runtime_1_20_1_6_0_6"),
                fingerprint,
                List.of("recipe:create:milling/complex", "ingredient:0"),
                "Ingredient uses semantics that the loader-neutral model cannot prove");
    }

    private static RuntimeFingerprint runtime(Map<String, String> mods) {
        return new RuntimeFingerprint(
                "1.20.1",
                "forge",
                "47.4.10",
                mods,
                "steve_industrial:create_runtime_1_20_1_6_0_6",
                1);
    }

    private static RuntimeRecipeCatalogEntry milling(String fingerprint) {
        return new RuntimeRecipeCatalogEntry(
                id("create:milling/cobblestone"),
                id("create:milling"),
                List.of(new RecipeIngredient.ExactResource(id("minecraft:cobblestone"), 1)),
                List.of(item("minecraft:gravel")),
                List.of(),
                Set.of(id("create:milling")),
                Set.of(GenericResourceType.ITEM, GenericResourceType.ROTATIONAL_POWER),
                OptionalLong.of(250),
                new RecipeSource(
                        id("steve_industrial:create_runtime_1_20_1_6_0_6"),
                        "create",
                        fingerprint,
                        true));
    }

    private static ProcessResource item(String value) {
        return new ProcessResource(id(value), GenericResourceType.ITEM, 1);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
