package org.taumc.celeritas.mixin.core;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.render.world.ChunkRenderDispatcher;
import net.minecraft.client.render.world.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hestia build (2026-10-12): vanilla's chunk builder (two worker threads and their preallocated vertex buffers) is
 * never created; Celeritas builds chunks itself. Every use of it is in a method Celeritas replaces except
 * {@code resetDirtyChunks}, which reload() calls.
 */
@Mixin(WorldRenderer.class)
public abstract class VanillaChunkBuilderMixin {
    @WrapOperation(method = "<init>", at = @At(value = "NEW", target = "()Lnet/minecraft/client/render/world/ChunkRenderDispatcher;"))
    private ChunkRenderDispatcher celeritas$skipChunkBuilder(Operation<ChunkRenderDispatcher> original) {
        return null;
    }

    @WrapWithCondition(method = "resetDirtyChunks", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/world/ChunkRenderDispatcher;runTasks()V"))
    private boolean celeritas$noChunkBuilder(ChunkRenderDispatcher dispatcher) {
        return dispatcher != null;
    }
}
