package dev.stevecreate.agent.core.player;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Choosing which goals to show when there are more than fit on a screen.
 *
 * <p>The reviewed catalog held eleven entries and every one could be sent at once. A
 * derived catalog holds a couple of hundred, which is past what the wire format carries
 * and well past what anyone reads, so something has to decide what to send. This does,
 * and it is pure so the decision can be tested without a server.
 *
 * <p>Reviewed entries come first whatever the query matches. They are the ones with
 * physical evidence behind them, and burying a verified goal beneath fifty projected ones
 * that happen to sort earlier would make the catalog worse than the short list it
 * replaced.
 */
public final class GoalSearch {

    private GoalSearch() {}

    /**
     * The goals worth showing for a query, reviewed first and capped at {@code limit}.
     *
     * <p>An empty query is not "match everything": it returns the reviewed entries
     * alone. Two hundred goals in no particular order is not a menu, and a player who has
     * not asked for anything specific is best served by the list that was curated. Typing
     * anything at all opens up the rest.</p>
     *
     * @param query matched case-insensitively against the target id and the recipe id;
     *     blank means reviewed-only
     */
    public static List<GoalCatalogEntry> matching(
            List<GoalCatalogEntry> reviewed,
            List<GoalCatalogEntry> derived,
            String query,
            int limit) {
        Objects.requireNonNull(reviewed, "reviewed");
        Objects.requireNonNull(derived, "derived");
        Objects.requireNonNull(query, "query");
        if (limit < 1) throw new IllegalArgumentException("a goal listing needs room for one");
        String needle = query.trim().toLowerCase(Locale.ROOT);

        List<GoalCatalogEntry> result = new ArrayList<>();
        Set<ResourceId> seen = new LinkedHashSet<>();
        for (GoalCatalogEntry entry : sorted(reviewed)) {
            if (!matches(entry, needle)) continue;
            if (seen.add(entry.target())) result.add(entry);
        }
        if (needle.isEmpty()) {
            return List.copyOf(result.size() > limit ? result.subList(0, limit) : result);
        }
        for (GoalCatalogEntry entry : sorted(derived)) {
            if (result.size() >= limit) break;
            if (!matches(entry, needle)) continue;
            // A reviewed entry and a derived one can name the same product. The reviewed
            // one is already in, and it carries decisions the registry cannot express —
            // showing the derived twin beside it would offer the player a worse copy of
            // something they can already pick.
            if (seen.add(entry.target())) result.add(entry);
        }
        return List.copyOf(result.size() > limit ? result.subList(0, limit) : result);
    }

    private static List<GoalCatalogEntry> sorted(List<GoalCatalogEntry> entries) {
        return entries.stream()
                .sorted(Comparator.comparing(entry -> entry.target().toString()))
                .toList();
    }

    private static boolean matches(GoalCatalogEntry entry, String needle) {
        if (needle.isEmpty()) return true;
        return entry.target().toString().toLowerCase(Locale.ROOT).contains(needle)
                || entry.recipe().toString().toLowerCase(Locale.ROOT).contains(needle);
    }
}
