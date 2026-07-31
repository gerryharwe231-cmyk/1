package com.slopeconnector.surface.client;

import com.slopeconnector.connected.ConnectedArcBlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Bends the actual connected baked model. The source model is recentered around its own lateral
 * bounds before warping, so a narrow middle railing joins the geometric center of a wide endpoint.
 */
public final class RefinedConnectedArcRenderer implements BlockEntityRenderer<ConnectedArcBlockEntity> {
    private static final float SOURCE_SLICE = 1.0f / 32.0f;
    private static final float EPS = 1.0E-6f;
    private static final Map<ConnectedArcBlockEntity, CompiledMesh> CACHE = new WeakHashMap<>();

    public RefinedConnectedArcRenderer(BlockEntityRendererFactory.Context context) {}

    @Override
    public void render(ConnectedArcBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider consumers, int light, int overlay) {
        renderReplacement(entity, tickDelta, matrices, consumers, light, overlay);
    }

    public static void renderReplacement(ConnectedArcBlockEntity entity, float tickDelta,
                                         MatrixStack matrices, VertexConsumerProvider consumers,
                                         int light, int overlay) {
        if (entity.getWorld() == null) return;
        CompiledMesh mesh = CACHE.get(entity);
        if (mesh == null || mesh.revision != entity.getRenderRevision()) {
            mesh = compile(entity);
            CACHE.put(entity, mesh);
        }
        if (mesh.triangles.isEmpty()) return;

        RenderLayer layer = RenderLayers.getBlockLayer(entity.getStraightState());
        VertexConsumer consumer = consumers.getBuffer(layer);
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f position = entry.getPositionMatrix();
        Matrix3f normal = entry.getNormalMatrix();
        BlockPos pos = entity.getPos();
        int[] directionLights = {-1, -1, -1, -1, -1, -1};

        for (Triangle triangle : mesh.triangles) {
            int lightIndex = triangle.direction.ordinal();
            int packedLight = directionLights[lightIndex];
            if (packedLight < 0) {
                packedLight = WorldRenderer.getLightmapCoordinates(entity.getWorld(),
                        entity.getStraightState(), pos.offset(triangle.direction));
                packedLight = maxPacked(light, packedLight);
                directionLights[lightIndex] = packedLight;
            }
            for (int index = 0; index < 3; index++) {
                Vertex vertex = triangle.vertices[index];
                emit(consumer, position, normal, vertex, triangle, packedLight, overlay);
            }
            emit(consumer, position, normal, triangle.vertices[2], triangle, packedLight, overlay);
        }
    }

    private static CompiledMesh compile(ConnectedArcBlockEntity entity) {
        BlockState state = entity.getStraightState();
        MinecraftClient client = MinecraftClient.getInstance();
        BakedModel model = client.getBlockRenderManager().getModel(state);
        List<BakedQuad> quads = allQuads(model, state);
        if (quads.isEmpty()) return new CompiledMesh(entity.getRenderRevision(), List.of());
        Bounds bounds = bounds(quads);
        if (bounds.maxX - bounds.minX < EPS) return new CompiledMesh(entity.getRenderRevision(), List.of());

        List<Triangle> triangles = new ArrayList<>();
        BlockColors colors = client.getBlockColors();
        for (ConnectedArcBlockEntity.Section section : entity.getSections()) {
            for (BakedQuad quad : quads) {
                List<SourceVertex> polygon = sourceVertices(quad);
                if (polygon.size() < 3) continue;
                float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
                for (SourceVertex vertex : polygon) {
                    minX = Math.min(minX, vertex.x);
                    maxX = Math.max(maxX, vertex.x);
                }
                int slices = Math.max(1, Math.min(32,
                        (int) Math.ceil((maxX - minX) / SOURCE_SLICE)));
                int tint = 0xFFFFFF;
                if (quad.hasColor() && entity.getWorld() != null) {
                    int resolved = colors.getColor(state, entity.getWorld(), entity.getPos(), quad.getColorIndex());
                    if (resolved != -1) tint = resolved & 0xFFFFFF;
                }
                for (int slice = 0; slice < slices; slice++) {
                    float low = minX + (maxX - minX) * slice / slices;
                    float high = minX + (maxX - minX) * (slice + 1) / slices;
                    List<SourceVertex> clipped = clipX(polygon, low, true);
                    clipped = clipX(clipped, high, false);
                    if (clipped.size() < 3) continue;
                    WarpedVertex first = warp(section, clipped.get(0), bounds);
                    for (int index = 1; index < clipped.size() - 1; index++) {
                        WarpedVertex second = warp(section, clipped.get(index), bounds);
                        WarpedVertex third = warp(section, clipped.get(index + 1), bounds);
                        addTriangle(triangles, first, second, third, quad.getSprite(), tint);
                    }
                }
            }
        }
        return new CompiledMesh(entity.getRenderRevision(), List.copyOf(triangles));
    }

    private static List<BakedQuad> allQuads(BakedModel model, BlockState state) {
        List<BakedQuad> out = new ArrayList<>();
        long seed = state.getRenderingSeed(BlockPos.ORIGIN);
        for (Direction direction : Direction.values()) {
            out.addAll(model.getQuads(state, direction, Random.create(seed + direction.ordinal())));
        }
        out.addAll(model.getQuads(state, null, Random.create(seed + 91L)));
        return out;
    }

    private static Bounds bounds(List<BakedQuad> quads) {
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (BakedQuad quad : quads) {
            for (SourceVertex vertex : sourceVertices(quad)) {
                minX = Math.min(minX, vertex.x); maxX = Math.max(maxX, vertex.x);
                minZ = Math.min(minZ, vertex.z); maxZ = Math.max(maxZ, vertex.z);
            }
        }
        if (!Float.isFinite(minX)) return new Bounds(0, 1, 0, 1, 0.5f);
        return new Bounds(minX, maxX, minZ, maxZ, (minZ + maxZ) * 0.5f);
    }

    private static List<SourceVertex> sourceVertices(BakedQuad quad) {
        int[] data = quad.getVertexData();
        int stride = data.length / 4;
        List<SourceVertex> out = new ArrayList<>(4);
        for (int index = 0; index < 4; index++) {
            int offset = index * stride;
            out.add(new SourceVertex(
                    Float.intBitsToFloat(data[offset]),
                    Float.intBitsToFloat(data[offset + 1]),
                    Float.intBitsToFloat(data[offset + 2]),
                    Float.intBitsToFloat(data[offset + 4]),
                    Float.intBitsToFloat(data[offset + 5])));
        }
        return out;
    }

    private static List<SourceVertex> clipX(List<SourceVertex> input, float boundary,
                                            boolean keepGreater) {
        if (input.isEmpty()) return input;
        List<SourceVertex> output = new ArrayList<>(input.size() + 2);
        SourceVertex previous = input.get(input.size() - 1);
        boolean previousInside = keepGreater ? previous.x >= boundary - EPS
                : previous.x <= boundary + EPS;
        for (SourceVertex current : input) {
            boolean currentInside = keepGreater ? current.x >= boundary - EPS
                    : current.x <= boundary + EPS;
            if (currentInside != previousInside) {
                float denominator = current.x - previous.x;
                float t = Math.abs(denominator) < EPS ? 0.0f
                        : (boundary - previous.x) / denominator;
                output.add(previous.lerp(current, t));
            }
            if (currentInside) output.add(current);
            previous = current;
            previousInside = currentInside;
        }
        return output;
    }

    private static WarpedVertex warp(ConnectedArcBlockEntity.Section section,
                                     SourceVertex source, Bounds bounds) {
        float normalizedX = (source.x - bounds.minX) / Math.max(EPS, bounds.maxX - bounds.minX);
        CurveSample curve = evaluate(section, normalizedX);
        double lateral = source.z - bounds.centerZ;
        float x = (float) (curve.center.x + curve.side.x * lateral);
        float y = (float) (curve.center.y + source.y);
        float z = (float) (curve.center.z + curve.side.z * lateral);
        return new WarpedVertex(x, y, z, source.u, source.v);
    }

    private static CurveSample evaluate(ConnectedArcBlockEntity.Section section, double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        Vec3d p0 = new Vec3d(section.c0x(), section.c0y(), section.c0z());
        Vec3d p1 = new Vec3d(section.c1x(), section.c1y(), section.c1z());
        Vec3d m0 = new Vec3d(section.t0x(), section.t0y(), section.t0z());
        Vec3d m1 = new Vec3d(section.t1x(), section.t1y(), section.t1z());
        double t2 = t * t, t3 = t2 * t;
        double h00 = 2 * t3 - 3 * t2 + 1;
        double h10 = t3 - 2 * t2 + t;
        double h01 = -2 * t3 + 3 * t2;
        double h11 = t3 - t2;
        Vec3d center = p0.multiply(h00).add(m0.multiply(h10))
                .add(p1.multiply(h01)).add(m1.multiply(h11));
        double dh00 = 6 * t2 - 6 * t;
        double dh10 = 3 * t2 - 4 * t + 1;
        double dh01 = -6 * t2 + 6 * t;
        double dh11 = 3 * t2 - 2 * t;
        Vec3d tangent = p0.multiply(dh00).add(m0.multiply(dh10))
                .add(p1.multiply(dh01)).add(m1.multiply(dh11));
        tangent = new Vec3d(tangent.x, 0.0, tangent.z);
        if (tangent.lengthSquared() < 1.0E-10) tangent = p1.subtract(p0);
        tangent = new Vec3d(tangent.x, 0.0, tangent.z).normalize();
        return new CurveSample(center, tangent, new Vec3d(-tangent.z, 0.0, tangent.x));
    }

    private static void addTriangle(List<Triangle> output, WarpedVertex a,
                                    WarpedVertex b, WarpedVertex c,
                                    Sprite sprite, int tint) {
        Vector3f va = new Vector3f(a.x, a.y, a.z);
        Vector3f vb = new Vector3f(b.x, b.y, b.z);
        Vector3f vc = new Vector3f(c.x, c.y, c.z);
        Vector3f normal = new Vector3f(vb).sub(va).cross(new Vector3f(vc).sub(va));
        if (normal.lengthSquared() < 1.0E-10f) return;
        normal.normalize();
        Direction direction = dominant(normal);
        output.add(new Triangle(new Vertex[]{a.vertex(), b.vertex(), c.vertex()},
                normal.x, normal.y, normal.z, direction, sprite, tint));
    }

    private static Direction dominant(Vector3f normal) {
        float ax = Math.abs(normal.x), ay = Math.abs(normal.y), az = Math.abs(normal.z);
        if (ay >= ax && ay >= az) return normal.y >= 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return normal.x >= 0 ? Direction.EAST : Direction.WEST;
        return normal.z >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static int maxPacked(int a, int b) {
        int block = Math.max(a & 0xFFFF, b & 0xFFFF);
        int sky = Math.max((a >>> 16) & 0xFFFF, (b >>> 16) & 0xFFFF);
        return block | (sky << 16);
    }

    private static void emit(VertexConsumer consumer, Matrix4f position, Matrix3f normal,
                             Vertex vertex, Triangle triangle, int light, int overlay) {
        int red = (triangle.tint >> 16) & 255;
        int green = (triangle.tint >> 8) & 255;
        int blue = triangle.tint & 255;
        consumer.vertex(position, vertex.x, vertex.y, vertex.z)
                .color(red, green, blue, 255)
                .texture(vertex.u, vertex.v)
                .overlay(overlay).light(light)
                .normal(normal, triangle.nx, triangle.ny, triangle.nz).next();
    }

    @Override public boolean rendersOutsideBoundingBox(ConnectedArcBlockEntity entity) { return true; }

    private record Bounds(float minX, float maxX, float minZ, float maxZ, float centerZ) {}
    private record SourceVertex(float x, float y, float z, float u, float v) {
        SourceVertex lerp(SourceVertex other, float t) {
            return new SourceVertex(x + (other.x - x) * t,
                    y + (other.y - y) * t, z + (other.z - z) * t,
                    u + (other.u - u) * t, v + (other.v - v) * t);
        }
    }
    private record WarpedVertex(float x, float y, float z, float u, float v) {
        Vertex vertex() { return new Vertex(x, y, z, u, v); }
    }
    private record Vertex(float x, float y, float z, float u, float v) {}
    private record CurveSample(Vec3d center, Vec3d tangent, Vec3d side) {}
    private record Triangle(Vertex[] vertices, float nx, float ny, float nz,
                            Direction direction, Sprite sprite, int tint) {}
    private record CompiledMesh(int revision, List<Triangle> triangles) {}
}
