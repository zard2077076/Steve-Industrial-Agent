package journeymap.client.ui.fullscreen;

/**
 * Empty run-only target for Create 6.0.6's optional JourneyMap Mixin lookup.
 *
 * <p>Create declares an optional compatibility Mixin for this class, and Mixin
 * resolves the annotation target during startup even when the actual JourneyMap
 * mod is absent. This class is never loaded by the profile. Its source set is
 * present only in the isolated create-only-client userdev run and is excluded
 * from every production artifact.</p>
 */
public final class Fullscreen {
    private Fullscreen() {
    }
}
