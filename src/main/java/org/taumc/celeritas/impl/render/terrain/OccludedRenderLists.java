package org.taumc.celeritas.impl.render.terrain;

// Hestia build (2026-10-14): new, for SectionOcclusion (another mod's test leaving visible sections out of the draws).

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import org.embeddedt.embeddium.impl.render.chunk.RenderSection;
import org.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderList;
import org.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderListIterable;
import org.embeddedt.embeddium.impl.render.chunk.lists.SortedRenderLists;
import org.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
import org.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import org.embeddedt.embeddium.impl.util.iterator.ReversibleObjectArrayIterator;
import org.taumc.celeritas.api.SectionOcclusion;

import java.lang.reflect.Field;
import java.util.Iterator;

/**
 * The frame's render lists without the sections a {@link SectionOcclusion.Test} reports hidden: filtered once per frame
 * (the first terrain pass asks, the others reuse it). A region's filtered list is kept while its sections stay the
 * same, so a still camera allocates nothing; regions with nothing hidden use their own list.
 */
final class OccludedRenderLists implements ChunkRenderListIterable {
    private static final Field LISTS = listsField();

    private final ObjectArrayList<ChunkRenderList> lists = new ObjectArrayList<>();
    private final Reference2ObjectOpenHashMap<RenderRegion, Kept> kept = new Reference2ObjectOpenHashMap<>();
    private final byte[] scratch = new byte[RenderRegion.REGION_SIZE];
    private ChunkRenderList[] chosen = new ChunkRenderList[64];
    private ChunkRenderListIterable source;
    private int frame = Integer.MIN_VALUE;

    private static final class Kept {
        final byte[] sections = new byte[RenderRegion.REGION_SIZE];
        int count = -1;
        ChunkRenderList list;
    }

    ChunkRenderListIterable filter(ChunkRenderListIterable renderLists, SectionOcclusion.Test test) {
        if (LISTS == null || !(renderLists instanceof SortedRenderLists)) {
            return renderLists;
        }
        int now = SectionOcclusion.frame();
        if (renderLists == this.source && now == this.frame) {
            return this;
        }
        ObjectArrayList<ChunkRenderList> sourceLists;
        try {
            @SuppressWarnings("unchecked")
            ObjectArrayList<ChunkRenderList> read = (ObjectArrayList<ChunkRenderList>) LISTS.get(renderLists);
            sourceLists = read;
        } catch (IllegalAccessException e) {
            return renderLists;
        }
        this.source = renderLists;
        this.frame = now;
        this.lists.clear();
        if (this.kept.size() > 4096) {
            this.kept.clear();
        }
        int size = sourceLists.size();
        if (this.chosen.length < size) {
            this.chosen = new ChunkRenderList[Math.max(size, this.chosen.length * 2)];
        }
        // Farthest regions first (the lists run near to far), where sections are most often hidden, in case the test
        // runs out of time; the draws keep the lists' order.
        for (int l = size - 1; l >= 0; l--) {
            this.chosen[l] = this.filterList(sourceLists.get(l), test);
        }
        for (int l = 0; l < size; l++) {
            ChunkRenderList list = this.chosen[l];
            if (list != null) {
                this.lists.add(list);
            }
            this.chosen[l] = null;
        }
        return this;
    }

    /** The list without its hidden sections: the list itself when none is hidden, null when all are. */
    private ChunkRenderList filterList(ChunkRenderList list, SectionOcclusion.Test test) {
        RenderRegion region = list.getRegion();
        byte[] sections = list.getSectionsWithGeometry();
        int count = list.getSectionsWithGeometryCount();
        int n = 0;
        for (int i = 0; i < count; i++) {
            int index = sections[i] & 0xFF;
            RenderSection section = region.getSection(index);
            if (section != null && test.isHidden(section.getChunkX(), section.getChunkY(), section.getChunkZ())) {
                continue;
            }
            this.scratch[n++] = (byte) index;
        }
        if (n == count) {
            return list;
        }
        if (n == 0) {
            return null;
        }
        Kept k = this.kept.get(region);
        if (k == null) {
            k = new Kept();
            this.kept.put(region, k);
        }
        boolean same = k.list != null && k.count == n;
        for (int i = 0; same && i < n; i++) {
            same = k.sections[i] == this.scratch[i];
        }
        if (!same) {
            // Flags: bit 0 is geometry, the only list the draws read.
            ChunkRenderList filtered = new ChunkRenderList(region);
            for (int i = 0; i < n; i++) {
                filtered.add(this.scratch[i] & 0xFF, 1);
            }
            System.arraycopy(this.scratch, 0, k.sections, 0, n);
            k.count = n;
            k.list = filtered;
        }
        return k.list;
    }

    @Override
    public Iterator<ChunkRenderList> iterator(boolean reverse) {
        return new ReversibleObjectArrayIterator<>(this.lists, reverse);
    }

    @Override
    public int getNumRegions() {
        return this.lists.size();
    }

    @Override
    public boolean hasPass(TerrainRenderPass pass) {
        ChunkRenderListIterable from = this.source;
        return from == null || from.hasPass(pass);
    }

    private static Field listsField() {
        try {
            Field field = SortedRenderLists.class.getDeclaredField("lists");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
