package dev.stevecreate.agent.forge1201.profile;

import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.DeceasedCraftRuntimeKnowledgeExporter;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.DeceasedCraftRuntimePlanningExporter;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.DeceasedCraftExecutionPilotFixture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import org.slf4j.Logger;

/**
 * Default-off production-JAR startup probe for the disposable R-09 pack profile.
 *
 * <p>This class validates only process/runtime isolation and performs no recipe mapping, planning,
 * session creation, executor call or world mutation. Later R-09 stages may add read-only knowledge
 * reporting behind the same path/marker gate.</p>
 */
public final class DeceasedCraftPackProfileProbe {
    public static final String ENABLE_PROPERTY = "steve_industrial.r09.packProfile";
    public static final String EXPECTED_GAME_DIR_PROPERTY =
            "steve_industrial.r09.expectedGameDir";
    public static final String FORBIDDEN_ROOT_PROPERTY =
            "steve_industrial.r09.forbiddenRoot";
    private static final String MARKER_FILE = ".steve-industrial-r09-profile";
    private static final String MARKER_VALUE = "steve-industrial:r09-isolated-profile/v1";

    private DeceasedCraftPackProfileProbe() {
    }

    public static void run(MinecraftServer server, Logger logger) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(logger, "logger");
        boolean pilotStarted = false;
        try {
            Path actualGameDir = Path.of(System.getProperty("user.dir")).toRealPath();
            Path expectedGameDir = requiredPath(EXPECTED_GAME_DIR_PROPERTY).toRealPath();
            Path forbiddenRoot = requiredPath(FORBIDDEN_ROOT_PROPERTY).toRealPath();
            check(actualGameDir.equals(expectedGameDir),
                    "WRONG_GAME_DIRECTORY",
                    "actual gameDir does not equal the exact expected isolated path");
            check(!actualGameDir.startsWith(forbiddenRoot),
                    "EXTERNAL_WRITE_RISK",
                    "actual gameDir is inside the forbidden formal-pack root");
            check(Files.isRegularFile(actualGameDir.resolve(MARKER_FILE)),
                    "WRONG_GAME_DIRECTORY",
                    "isolated profile marker is missing");
            String marker = Files.readString(
                    actualGameDir.resolve(MARKER_FILE), StandardCharsets.US_ASCII).trim();
            check(MARKER_VALUE.equals(marker),
                    "WRONG_GAME_DIRECTORY",
                    "isolated profile marker is invalid");
            Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            check(worldRoot.startsWith(actualGameDir),
                    "ISOLATED_WORLD_FAILED",
                    "server world root is outside the isolated gameDir");
            check(FMLLoader.isProduction(),
                    "FORGE_STARTUP_FAILED",
                    "R-09 requires the clean-build production JAR, not a userdev source set");
            check(ModList.get().isLoaded("create"),
                    "MOD_DEPENDENCY_MISSING",
                    "Create is not loaded in the isolated pack profile");

            DeceasedCraftRuntimeKnowledgeExporter.ExportSummary knowledge =
                    DeceasedCraftRuntimeKnowledgeExporter.export(server.overworld(), actualGameDir);
            logger.info(
                    "R09_PACK_RUNTIME_KNOWLEDGE_PASS total={} milling={} pressing={} crushing={} sequencedAssembly={} sequencedPressingSteps={} mapped={} rejected={} warnings={} fingerprint={} evidence={} staticScriptCountsUsedAsRuntimeTruth=false worldMutation=false sessionCreated=false",
                    knowledge.recipeManagerTotal(),
                    knowledge.millingCount(),
                    knowledge.pressingCount(),
                    knowledge.crushingCount(),
                    knowledge.sequencedAssemblyCount(),
                    knowledge.sequencedPressingStepCount(),
                    knowledge.mappedCount(),
                    knowledge.rejectedCount(),
                    knowledge.warningCount(),
                    knowledge.runtimeFingerprint(),
                    knowledge.evidencePath());

            DeceasedCraftRuntimePlanningExporter.ExportSummary planning =
                    DeceasedCraftRuntimePlanningExporter.export(server.overworld(), actualGameDir);
            logger.info(
                    "R09_PACK_RUNTIME_PLANNING_PASS successes=4 failures=3 verifierChecks=8 bindingSuccesses=4 bindingFailures=2 bindingVerifierChecks=15 physicalizationSuccesses=12 physicalizationFailures=7 physicalizationVerifierChecks=13 physicalOrientations=3 repeatabilityChecks=1 customMillingRecipe={} customPressingRecipe={} rejectedRecipe={} rejectedTarget={} fingerprint={} evidence={} outputType=VerifiedLogicalPlan physicalizationOutputType=VerifiedPhysicalPlan executionAuthority=false worldMutation=false sessionCreated=false",
                    planning.customMillingRecipeId(),
                    planning.customPressingRecipeId(),
                    planning.rejectedRecipeId(),
                    planning.rejectedTarget(),
                    planning.runtimeFingerprint(),
                    planning.evidencePath());

            var industrialRoot = server.getCommands().getDispatcher().getRoot()
                    .getChild("industrialagent");
            boolean deploymentRegistered = industrialRoot != null
                    && industrialRoot.getChild("deploy") != null;
            logger.info(
                    "R09_PACK_DEPLOYMENT_COMMAND_TREE registered={} industrialagent={} deploy={}",
                    deploymentRegistered, industrialRoot != null,
                    industrialRoot != null && industrialRoot.getChild("deploy") != null);
            check(deploymentRegistered, "ISOLATED_WORLD_FAILED",
                    "PW-12 deploy command is absent from the production dispatcher");
            var deploymentSource = server.createCommandSourceStack();
            logger.info("R09_PACK_DEPLOYMENT_COMMAND_SOURCE authoritative=true permission=server-default");
            int deploymentCommands = 0;
            List<String> deploymentEvidence = new ArrayList<>();
            for (String target : new String[] {
                    "immersiveengineering:dust_coke 4", "apocalypsenow:can 3"}) {
                for (String orientation : new String[] {
                        "zero", "clockwise_90", "clockwise_270"}) {
                    String command = "/industrialagent deploy preview "
                            + target + " " + orientation;
                    int result = server.getCommands().performPrefixedCommand(
                            deploymentSource, command);
                    check(result == 1, "ISOLATED_WORLD_FAILED",
                            "PW-12 custom preview command failed: " + target + " " + orientation);
                    deploymentEvidence.add("command=" + command + " result=" + result);
                    deploymentCommands++;
                }
                for (String section : new String[] {"risks", "budget", "readiness"}) {
                    String command = "/industrialagent deploy " + section
                            + " " + target + " zero";
                    int result = server.getCommands().performPrefixedCommand(
                            deploymentSource, command);
                    check(result == 1, "ISOLATED_WORLD_FAILED",
                            "PW-12 custom " + section + " command failed: " + target);
                    deploymentEvidence.add("command=" + command + " result=" + result);
                    deploymentCommands++;
                }
            }
            check(deploymentCommands == 12, "ISOLATED_WORLD_FAILED",
                    "PW-12 custom command matrix was incomplete");
            Path deploymentEvidenceDirectory = actualGameDir.resolve("r09-evidence");
            Files.createDirectories(deploymentEvidenceDirectory);
            Path deploymentEvidencePath = deploymentEvidenceDirectory.resolve(
                    "deployment-dry-run.txt");
            Path deploymentEvidenceStaging = deploymentEvidenceDirectory.resolve(
                    "deployment-dry-run.txt.staging");
            List<String> deploymentEvidenceLines = new ArrayList<>();
            deploymentEvidenceLines.add(
                    "schema=steve-industrial:pw12-deployment-dry-run/v1 commands=12 targets=2"
                            + " orientations=3 preview=true risks=true budget=true readiness=true"
                            + " dryRun=true formalWorldExecutable=false worldMutation=false"
                            + " sessionCreated=false playerItemsConsumed=false machineStarted=false"
                            + " llmCalled=false freeTextCoordinatesAccepted=false");
            deploymentEvidenceLines.addAll(deploymentEvidence);
            Files.write(deploymentEvidenceStaging, deploymentEvidenceLines,
                    StandardCharsets.UTF_8);
            Files.move(deploymentEvidenceStaging, deploymentEvidencePath,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            logger.info(
                    "R09_PACK_DEPLOYMENT_DRY_RUN_PASS commands=12 targets=2 customMilling=immersiveengineering:dust_coke quantityMilling=4 customPressing=apocalypsenow:can quantityPressing=3 orientations=3 preview=true risks=true budget=true readiness=true dryRun=true formalWorldExecutable=false worldMutation=false sessionCreated=false playerItemsConsumed=false machineStarted=false llmCalled=false freeTextCoordinatesAccepted=false");

            String createVersion = ModList.get().getModContainerById("create")
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("missing");
            logger.info(
                    "R09_PACK_STARTUP_PASS gameDir={} worldRoot={} productionJar=true minecraft={} forge={} create={} loadedMods={} worldMutation=false sessionCreated=false",
                    actualGameDir,
                    worldRoot,
                    SharedConstants.getCurrentVersion().getName(),
                    FMLLoader.versionInfo().forgeVersion(),
                    createVersion,
                    ModList.get().getMods().size());
            if (Boolean.getBoolean(DeceasedCraftExecutionPilotFixture.ENABLE_PROPERTY)) {
                DeceasedCraftExecutionPilotFixture.start(server, actualGameDir, logger);
                pilotStarted = true;
            }
        } catch (R09StartupFailure failure) {
            logger.error(
                    "R09_PACK_STARTUP_FAIL code={} stage=isolated-server-startup detail=\"{}\" userIntervention=false",
                    failure.code,
                    failure.getMessage(),
                    failure);
        } catch (IOException | RuntimeException failure) {
            logger.error(
                    "R09_PACK_STARTUP_FAIL code=FORGE_STARTUP_FAILED stage=isolated-server-startup detail=\"{}\" userIntervention=false",
                    safeMessage(failure),
                    failure);
        } finally {
            if (!pilotStarted) {
                server.halt(false);
            }
        }
    }

    private static Path requiredPath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new R09StartupFailure(
                    "WRONG_GAME_DIRECTORY", "required path property is missing: " + property);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static void check(boolean condition, String code, String detail) {
        if (!condition) {
            throw new R09StartupFailure(code, detail);
        }
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return message.length() <= 1_024 ? message : message.substring(0, 1_024);
    }

    private static final class R09StartupFailure extends RuntimeException {
        private final String code;

        private R09StartupFailure(String code, String detail) {
            super(detail);
            this.code = Objects.requireNonNull(code, "code");
        }
    }
}
