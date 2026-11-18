package com.schembuilder;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Draws the blocks that are still missing as translucent, block-coloured boxes in the world (a "hologram").
 *
 * <p>This is the ONLY class that touches Minecraft's low-level render API, which changed a lot in 1.21.x and
 * could not be compiled offline. It is loaded by reflection from {@link SchemClient}, and the build can be run with
 * {@code -PnoHologram} to leave this file out, so a problem here never takes the rest of the mod down.
 *
 * <p>Based on the Fabric "Rendering in the World" custom pipeline for 1.21.11: extraction (vertices into a
 * BufferBuilder, camera-relative) followed by a draw (upload + RenderPass).
 */
public final class HologramRenderer {
    private static final RenderPipeline PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("schembuilder", "pipeline/hologram"))
                    .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
                    .build());

    private static final ByteBufferBuilder ALLOCATOR = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
    private static MappableRingBuffer vertexBuffer;
    private static boolean failed;

    private HologramRenderer() {}

    public static void register() {
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(HologramRenderer::render);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> close());
    }

    private static void render(WorldRenderContext context) {
        if (failed || !Config.hologram) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;

        PoseStack matrices = context.matrices();
        boolean pushed = false;
        try {
            Vec3 cam = context.worldState().cameraRenderState.pos;
            BufferBuilder buffer = new BufferBuilder(ALLOCATOR, PIPELINE.getVertexFormatMode(), PIPELINE.getVertexFormat());

            matrices.pushPose();
            pushed = true;
            matrices.translate(-cam.x, -cam.y, -cam.z);
            Matrix4fc pose = matrices.last().pose();

            double range = Config.hologramRange;
            double rangeSq = range * range;
            int[] count = {0};
            SchemClient.BUILDER.forEachHologramTarget(t -> {
                if (count[0] >= Config.HOLOGRAM_MAX_BOXES) return;
                if (t.pos.distToCenterSqr(cam.x, cam.y, cam.z) > rangeSq) return;
                if (!level.hasChunkAt(t.pos)) return;
                if (SchematicBuilder.matches(t.want, level.getBlockState(t.pos))) return; // already built

                int col = t.want.getMapColor(level, t.pos).col;
                float r = ((col >> 16) & 0xFF) / 255f;
                float g = ((col >> 8) & 0xFF) / 255f;
                float b = (col & 0xFF) / 255f;
                addBox(buffer, pose, t.pos.getX(), t.pos.getY(), t.pos.getZ(), r, g, b, 0.45f);
                count[0]++;
            });

            matrices.popPose();
            pushed = false;

            MeshData mesh = buffer.build(); // null when no vertices were added
            if (mesh != null) draw(mc, mesh);
        } catch (Throwable e) {
            failed = true;
            Config.hologramAvailable = false;
            System.out.println("[Schem] Hologram renderer disabled after an error: " + e);
            e.printStackTrace();
        } finally {
            if (pushed) matrices.popPose();
        }
    }

    /** A slightly shrunk box so it doesn't fight with neighbouring blocks. Faces are shaded for a 3D look. */
    private static void addBox(BufferBuilder b, Matrix4fc m, int bx, int by, int bz, float r, float g, float bl, float a) {
        float x0 = bx + 0.08f, x1 = bx + 0.92f;
        float y0 = by + 0.08f, y1 = by + 0.92f;
        float z0 = bz + 0.08f, z1 = bz + 0.92f;

        // top
        face(b, m, r, g, bl, a, 1.0f, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);
        // bottom
        face(b, m, r, g, bl, a, 0.6f, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        // south / north
        face(b, m, r, g, bl, a, 0.85f, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        face(b, m, r, g, bl, a, 0.85f, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);
        // east / west
        face(b, m, r, g, bl, a, 0.75f, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1);
        face(b, m, r, g, bl, a, 0.75f, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
    }

    private static void face(BufferBuilder b, Matrix4fc m, float r, float g, float bl, float a, float shade,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz) {
        float rr = r * shade;
        float gg = g * shade;
        float bb = bl * shade;
        b.addVertex(m, ax, ay, az).setColor(rr, gg, bb, a);
        b.addVertex(m, bx, by, bz).setColor(rr, gg, bb, a);
        b.addVertex(m, cx, cy, cz).setColor(rr, gg, bb, a);
        b.addVertex(m, dx, dy, dz).setColor(rr, gg, bb, a);
    }

    private static void draw(Minecraft client, MeshData mesh) {
        MeshData.DrawState drawState = mesh.drawState();
        VertexFormat format = drawState.format();

        // Upload the vertices into a ring buffer, growing it when needed.
        int vertexBytes = drawState.vertexCount() * format.getVertexSize();
        if (vertexBuffer == null || vertexBuffer.size() < vertexBytes) {
            if (vertexBuffer != null) vertexBuffer.close();
            vertexBuffer = new MappableRingBuffer(() -> "schembuilder hologram vertices",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, vertexBytes);
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuBufferSlice slice = vertexBuffer.currentBuffer().slice(0, mesh.vertexBuffer().remaining());
        try (GpuBuffer.MappedView view = encoder.mapBuffer(slice, false, true)) {
            MemoryUtil.memCopy(mesh.vertexBuffer(), view.data());
        }
        GpuBuffer vertices = vertexBuffer.currentBuffer();

        // Sort the quads back-to-front so the translucency blends properly, then upload the indices.
        mesh.sortQuads(ALLOCATOR, RenderSystem.getProjectionType().vertexSorting());
        GpuBuffer indices = PIPELINE.getVertexFormat().uploadImmediateIndexBuffer(mesh.indexBuffer());
        VertexFormat.IndexType indexType = mesh.drawState().indexType();

        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(
                RenderSystem.getModelViewMatrix(), new Vector4f(1.0f, 1.0f, 1.0f, 1.0f), new Vector3f(),
                RenderSystem.getTextureMatrix(), 1.0f);

        try (RenderPass pass = encoder.createRenderPass(() -> "schembuilder hologram",
                client.getMainRenderTarget().getColorTextureView(), OptionalInt.empty(),
                client.getMainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, vertices);
            pass.setIndexBuffer(indices, indexType);
            pass.drawIndexed(0, 0, drawState.indexCount(), 1);
        }

        mesh.close();
        vertexBuffer.rotate(); // use a different buffer next frame, the GPU may still be reading this one
    }

    private static void close() {
        ALLOCATOR.close();
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
    }
}
