package dev.stevecreate.agent.forge1201.adapter.create;

import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateRuntimeRecipeCatalog;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Server-scoped owner that prevents catalog instances or snapshots from crossing worlds. */
public final class ForgeCreateRuntimeRecipeCatalogs {
    private static final Map<MinecraftServer, Map<ServerLevel, CreateRuntimeRecipeCatalog>> SERVERS =
            new WeakHashMap<>();

    private ForgeCreateRuntimeRecipeCatalogs() {
    }

    public static synchronized CreateRuntimeRecipeCatalog forLevel(ServerLevel level) {
        ServerLevel requiredLevel = Objects.requireNonNull(level, "level");
        return SERVERS.computeIfAbsent(requiredLevel.getServer(), ignored -> new IdentityHashMap<>())
                .computeIfAbsent(requiredLevel, CreateRuntimeRecipeCatalog::new);
    }

    public static synchronized void beginReload(MinecraftServer server) {
        Map<ServerLevel, CreateRuntimeRecipeCatalog> catalogs = SERVERS.get(
                Objects.requireNonNull(server, "server"));
        if (catalogs != null) {
            catalogs.values().forEach(CreateRuntimeRecipeCatalog::invalidateForReload);
        }
    }

    public static synchronized void completeReload(MinecraftServer server) {
        Map<ServerLevel, CreateRuntimeRecipeCatalog> catalogs = SERVERS.get(
                Objects.requireNonNull(server, "server"));
        if (catalogs != null) {
            catalogs.values().forEach(CreateRuntimeRecipeCatalog::completeReload);
        }
    }

    public static synchronized void clear(MinecraftServer server) {
        SERVERS.remove(Objects.requireNonNull(server, "server"));
    }
}
