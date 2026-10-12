package org.taumc.celeritas.impl.render.terrain;

// Hestia build (2026-10-12): new. celeritas-common's GenericSectionSpriteTicker marks the sprites of every section in a
// render region (8x4x8 sections) that has one visible section with sprites, and builds a new set on every search; this
// one marks only the visible sections' sprites and allocates only when that set changes.

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.embeddedt.embeddium.impl.render.chunk.RenderSection;
import org.embeddedt.embeddium.impl.render.chunk.data.MinecraftBuiltRenderSectionData;
import org.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderList;
import org.embeddedt.embeddium.impl.render.chunk.lists.SectionTicker;
import org.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
import org.embeddedt.embeddium.impl.util.iterator.ByteIterator;
import org.taumc.celeritas.api.VisibleSprites;

import java.util.Collection;
import java.util.List;

/**
 * Reports the animated sprites of the visible sections to {@link VisibleSprites} every frame. The set is worked out
 * when the section search finishes (on the occlusion thread) and published as an array the render thread walks.
 */
final class VisibleSectionSpriteTicker implements SectionTicker {
    private static final Object[] NO_SPRITES = new Object[0];

    // Occlusion thread only: the searches never overlap.
    private final ReferenceOpenHashSet<Object> collecting = new ReferenceOpenHashSet<>();

    private volatile Object[] visible = NO_SPRITES;

    @Override
    public void onRenderListUpdated(List<ChunkRenderList> lists) {
        ReferenceOpenHashSet<Object> sprites = this.collecting;

        for (int i = 0; i < lists.size(); i++) {
            ChunkRenderList list = lists.get(i);
            ByteIterator sections = list.sectionsWithSpritesIterator();
            if (sections == null) {
                continue;
            }
            RenderRegion region = list.getRegion();
            while (sections.hasNext()) {
                RenderSection section = region.getSection(sections.nextByteAsInt());
                if (section != null && section.getBuiltContext() instanceof MinecraftBuiltRenderSectionData<?, ?> data) {
                    add(sprites, data.animatedSprites);
                }
            }
        }

        if (!this.matches(sprites)) {
            this.visible = sprites.isEmpty() ? NO_SPRITES : sprites.toArray();
        }
        sprites.clear();
    }

    // Built sections hold an immutable list (MinecraftBuiltRenderSectionData.bake); walking it by index needs no iterator.
    private static void add(ReferenceOpenHashSet<Object> sprites, Collection<?> animated) {
        if (animated instanceof List<?> list) {
            for (int i = 0, size = list.size(); i < size; i++) {
                sprites.add(list.get(i));
            }
        } else {
            sprites.addAll(animated);
        }
    }

    private boolean matches(ReferenceOpenHashSet<Object> sprites) {
        Object[] current = this.visible;
        if (current.length != sprites.size()) {
            return false;
        }
        for (Object sprite : current) {
            if (!sprites.contains(sprite)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void tickVisibleRenders() {
        for (Object sprite : this.visible) {
            VisibleSprites.report(sprite);
        }
    }

    @Override
    public String getDebugString() {
        return "Visible animated sprites: " + this.visible.length;
    }
}
