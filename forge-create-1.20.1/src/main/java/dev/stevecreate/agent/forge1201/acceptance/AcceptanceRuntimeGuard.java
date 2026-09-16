package dev.stevecreate.agent.forge1201.acceptance;

import net.minecraftforge.fml.loading.FMLLoader;

/**
 * Refuses to let acceptance scaffolding run in a production runtime.
 *
 * <p>These fixtures place and remove blocks, spawn and drive entities, and move items
 * out of containers. They are gated by system properties, but that gate lives at the
 * call site and says nothing about which runtime is executing — and the scaffolding
 * ships inside the production JAR, so the class being present is not itself unusual.
 * Only five of eighteen fixtures previously refused a production runtime; the rest
 * would have proceeded if their property were ever set.</p>
 *
 * <p>This is deliberately defence in depth rather than the primary control. Setting a
 * JVM system property already implies control of the launch command, so this does not
 * pretend to stop an attacker. It stops the realistic case: a property left in a launch
 * script, copied from a dev profile, or inherited by a modpack launcher.</p>
 */
public final class AcceptanceRuntimeGuard {
    private AcceptanceRuntimeGuard() {}

    /**
     * @throws IllegalStateException in a production runtime, naming the fixture so the
     *     refusal is diagnosable rather than a bare failure at some later step.
     */
    public static void requireDevelopmentRuntime(String fixtureName) {
        if (FMLLoader.isProduction()) {
            throw new IllegalStateException(
                    fixtureName + " is acceptance scaffolding and is disabled in production");
        }
    }
}
