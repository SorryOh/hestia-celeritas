package org.taumc.celeritas.api;

// Hestia build (2026-10-14): new, for Hestia's depth-based occlusion (its "Feature D"): while a test is set, the
// sections the visibility search found visible but the test reports hidden are left out of the frame's terrain draws;
// a listener hears of every section whose geometry changes, so depth drawn before the change isn't trusted there.

/**
 * A test that can leave visible chunk sections out of the terrain draws, for sections another mod knows to be hidden
 * (Hestia: wholly behind the depth drawn in an earlier frame). The visibility search, block entities, entities and
 * animated sprites still see every visible section; only the draws are filtered.
 */
public final class SectionOcclusion {
    private SectionOcclusion() {
    }

    /** The question asked for each visible section with geometry, once per frame, on the render thread. */
    public interface Test {
        boolean isHidden(int sectionX, int sectionY, int sectionZ);
    }

    /**
     * Told of each section whose geometry changes (a rebuild's upload, a new or removed section), on the render thread,
     * before the frame's terrain draws.
     */
    public interface GeometryListener {
        void geometryChanged(int sectionX, int sectionY, int sectionZ);
    }

    private static volatile Test test;
    private static volatile GeometryListener geometryListener;
    private static int frame;

    /** Sets the test (null: every visible section is drawn, at no cost). */
    public static void setTest(Test newTest) {
        test = newTest;
    }

    public static Test getTest() {
        return test;
    }

    /** Sets the geometry listener (null: none). */
    public static void setGeometryListener(GeometryListener listener) {
        geometryListener = listener;
    }

    /** Called by the section manager for each section whose geometry changed. */
    public static void geometryChanged(int sectionX, int sectionY, int sectionZ) {
        GeometryListener listener = geometryListener;
        if (listener != null) {
            listener.geometryChanged(sectionX, sectionY, sectionZ);
        }
    }

    /** Called once per frame before the terrain draws, so each frame asks the test once per section. */
    public static void beginFrame() {
        frame++;
    }

    public static int frame() {
        return frame;
    }
}
