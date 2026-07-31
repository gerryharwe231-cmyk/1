package com.slopeconnector.hotfix.client;

import com.slopeconnector.hotfix.ArcRibbonBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Global curvilinear material atlas for the complete arc component.
 *
 * <p>No stored prism UV value is used.  Every texture coordinate is rebuilt from the actual world
 * geometry:</p>
 * <ul>
 *     <li>S = accumulated real arc length along the reconstructed centreline;</li>
 *     <li>W = signed real distance across the ribbon width;</li>
 *     <li>N = signed real distance through the ribbon thickness.</li>
 * </ul>
 *
 * <p>The same S/W/N coordinate system is used by every holder block and every endpoint overlay.
 * Consequently a checkerboard or brick texture bends as one sheet over the complete arc instead of
 * restarting or rotating at individual Minecraft block boundaries.</p>
 */
public final class UnifiedSurfaceArcRenderer {
    private static final float EPS = 1.0E-6f;
    private static final float JOIN_EPS = 0.12f;
    private static final int DISCOVERY_RADIUS = 3;
    private static final int MAX_COMPONENT_ENTITIES = 4096;
    private static final int MAX_COMPONENT_SEGMENTS = 32768;
    private static final int MAX_TILE_CELLS_PER_FACE = 256;

    private static final Map<ArcRibbonBlockEntity, CompiledMesh> MESH_CACHE = new WeakHashMap<>();
    private static final Map<ArcRibbonBlockEntity, AtlasHandle> ATLAS_CACHE = new WeakHashMap<>();

    private UnifiedSurfaceArcRenderer() {}

    public static void renderReplacement(ArcRibbonBlockEntity entity, float tickDelta,
                                         MatrixStack matrices, VertexConsumerProvider consumers,
                                         int light, int overlay) {
        if (entity.getWorld() == null) return;
        ComponentAtlas atlas = atlasFor(entity);
        if (atlas.segments.isEmpty()) return;

        CompiledMesh mesh = MESH_CACHE.get(entity);
        if (mesh == null || mesh.revision != entity.getRenderRevision()
                || mesh.atlasRevision != atlas.revision) {
            mesh = compile(entity, atlas);
            MESH_CACHE.put(entity, mesh);
        }
        if (mesh.triangles.isEmpty()) return;

        VertexConsumer consumer = consumers.getBuffer(RenderLayers.getBlockLayer(entity.getSourceState()));
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f position = entry.getPositionMatrix();
        Matrix3f normal = entry.getNormalMatrix();
        int[] directionalLights = {-1, -1, -1, -1, -1, -1};
        BlockPos blockPos = entity.getPos();

        for (Triangle triangle : mesh.triangles) {
            int lightIndex = triangle.direction.ordinal();
            int packedLight = directionalLights[lightIndex];
            if (packedLight < 0) {
                int sampled = WorldRenderer.getLightmapCoordinates(entity.getWorld(),
                        entity.getSourceState(), blockPos.offset(triangle.direction));
                packedLight = maxPacked(light, sampled);
                directionalLights[lightIndex] = packedLight;
            }
            int color = triangle.material.color();
            int red = (color >> 16) & 255;
            int green = (color >> 8) & 255;
            int blue = color & 255;
            emit(consumer, position, normal, triangle.a, triangle, packedLight, overlay, red, green, blue);
            emit(consumer, position, normal, triangle.b, triangle, packedLight, overlay, red, green, blue);
            emit(consumer, position, normal, triangle.c, triangle, packedLight, overlay, red, green, blue);
            // Minecraft's standard block vertex format is quad based; repeat the final triangle point.
            emit(consumer, position, normal, triangle.c, triangle, packedLight, overlay, red, green, blue);
        }
    }

    private static CompiledMesh compile(ArcRibbonBlockEntity entity, ComponentAtlas atlas) {
        List<Triangle> triangles = new ArrayList<>();
        for (ArcRibbonBlockEntity.Prism prism : entity.getPrisms()) {
            float[] vertices = prism.xyz();
            if (prism.draws(0)) addFace(entity, atlas, triangles, vertices, new int[]{0, 4, 5, 1}, prism.materialHint(), null);
            if (prism.draws(1)) addFace(entity, atlas, triangles, vertices, new int[]{3, 2, 6, 7}, prism.materialHint(), null);
            if (prism.draws(2)) addFace(entity, atlas, triangles, vertices, new int[]{0, 3, 7, 4}, prism.materialHint(), null);
            if (prism.draws(3)) addFace(entity, atlas, triangles, vertices, new int[]{1, 5, 6, 2}, prism.materialHint(), null);
            if (prism.draws(4)) addFace(entity, atlas, triangles, vertices, new int[]{0, 1, 2, 3}, prism.materialHint(), null);
            if (prism.draws(5)) addFace(entity, atlas, triangles, vertices, new int[]{4, 7, 6, 5}, prism.materialHint(), null);
        }
        // Endpoint SurfaceQuad overlays are intentionally not rendered. The actual endpoint blocks
        // retain vanilla rendering, so every one of their faces continues perfectly into ordinary
        // blocks placed beside them. Texture phase matching is handled globally by ComponentAtlas.
        return new CompiledMesh(entity.getRenderRevision(), atlas.revision, List.copyOf(triangles));
    }

    private static void addFace(ArcRibbonBlockEntity entity, ComponentAtlas atlas,
                                List<Triangle> output, float[] source, int[] ids,
                                byte materialHint, Direction fixedDirection) {
        GeometryVertex[] corners = new GeometryVertex[4];
        for (int index = 0; index < 4; index++) {
            int p = ids[index] * 3;
            float lx = source[p];
            float ly = source[p + 1];
            float lz = source[p + 2];
            corners[index] = new GeometryVertex(lx, ly, lz,
                    entity.getPos().getX() + lx,
                    entity.getPos().getY() + ly,
                    entity.getPos().getZ() + lz);
        }

        Vector3f faceNormal = normal(corners[0], corners[1], corners[2]);
        if (faceNormal == null) return;
        Direction direction = fixedDirection == null
                ? ArcMaterialHelper.dominant(faceNormal.x, faceNormal.y, faceNormal.z)
                : fixedDirection;
        Vector3f expected = new Vector3f(direction.getOffsetX(), direction.getOffsetY(), direction.getOffsetZ());
        if (faceNormal.dot(expected) < 0.0f) {
            GeometryVertex swap = corners[1];
            corners[1] = corners[3];
            corners[3] = swap;
            faceNormal.mul(-1.0f);
        }

        GeometryVertex centre = average(corners);
        CurveCoordinate centreCoordinate = atlas.locate(centre.wx, centre.wy, centre.wz);
        CoordinatePair pair = CoordinatePair.forFace(faceNormal, centreCoordinate);
        ParameterVertex[] parameterized = new ParameterVertex[4];
        for (int index = 0; index < 4; index++) {
            CurveCoordinate coordinate = atlas.locate(corners[index].wx, corners[index].wy, corners[index].wz);
            float a = pair.first(coordinate, atlas);
            float b = pair.second(coordinate, atlas);
            parameterized[index] = new ParameterVertex(corners[index].lx, corners[index].ly, corners[index].lz, a, b);
        }

        float edgeA = Math.max(distance(corners[0], corners[1]), distance(corners[3], corners[2]));
        float edgeB = Math.max(distance(corners[0], corners[3]), distance(corners[1], corners[2]));
        float aspect = Math.max(edgeA, edgeB) / Math.max(1.0E-4f, Math.min(edgeA, edgeB));
        float area = Math.max(1.0E-4f, edgeA * edgeB);
        ArcMaterialHelper.FaceMaterial material = ArcMaterialHelper.material(
                entity.getSourceState(), direction, entity.getWorld(), entity.getPos(),
                materialHint, aspect, area);

        if (pair == CoordinatePair.WIDTH_THICKNESS) {
            float station = centreCoordinate.s * atlas.scaleS + atlas.phaseS;
            // Front/back caps generated inside endpoint blocks are not visible. Native endpoint side
            // faces close the ribbon at exactly station 0 / station visibleTiles.
            if (station < -EPS || station > atlas.visibleTiles + EPS) return;
            splitTriangleByTiles(output, parameterized[0], parameterized[1], parameterized[2], direction, material);
            splitTriangleByTiles(output, parameterized[0], parameterized[2], parameterized[3], direction, material);
            return;
        }

        // Remove the small bridge overlap that lies inside the two native endpoint blocks. This
        // prevents coplanar top/side faces and guarantees there is no z-fighting with endpoint faces.
        List<ParameterVertex> visible = new ArrayList<>(List.of(parameterized));
        visible = clip(visible, true, 0.0f, true);
        visible = clip(visible, true, atlas.visibleTiles, false);
        if (visible.size() < 3) return;
        ParameterVertex origin = visible.get(0);
        for (int index = 1; index < visible.size() - 1; index++) {
            splitTriangleByTiles(output, origin, visible.get(index), visible.get(index + 1), direction, material);
        }
    }

    private static void splitTriangleByTiles(List<Triangle> output,
                                             ParameterVertex a, ParameterVertex b, ParameterVertex c,
                                             Direction direction,
                                             ArcMaterialHelper.FaceMaterial material) {
        float minA = Math.min(a.a, Math.min(b.a, c.a));
        float maxA = Math.max(a.a, Math.max(b.a, c.a));
        float minB = Math.min(a.b, Math.min(b.b, c.b));
        float maxB = Math.max(a.b, Math.max(b.b, c.b));
        int firstA = floorTile(minA);
        int lastA = maxA - minA < EPS ? firstA : ceilTile(maxA) - 1;
        int firstB = floorTile(minB);
        int lastB = maxB - minB < EPS ? firstB : ceilTile(maxB) - 1;
        long tileCount = (long)(lastA - firstA + 1) * (long)(lastB - firstB + 1);
        if (tileCount > MAX_TILE_CELLS_PER_FACE) {
            // A malformed or extremely long face should never smear one atlas tile over the whole arc.
            // Split by its longest parameter axis before retrying.
            splitLongTriangle(output, a, b, c, direction, material);
            return;
        }

        List<ParameterVertex> original = List.of(a, b, c);
        for (int tileA = firstA; tileA <= lastA; tileA++) {
            for (int tileB = firstB; tileB <= lastB; tileB++) {
                List<ParameterVertex> polygon = new ArrayList<>(original);
                polygon = clip(polygon, true, tileA, true);
                polygon = clip(polygon, true, tileA + 1.0f, false);
                polygon = clip(polygon, false, tileB, true);
                polygon = clip(polygon, false, tileB + 1.0f, false);
                if (polygon.size() < 3) continue;
                ParameterVertex origin = polygon.get(0).localize(tileA, tileB);
                for (int index = 1; index < polygon.size() - 1; index++) {
                    ParameterVertex p1 = polygon.get(index).localize(tileA, tileB);
                    ParameterVertex p2 = polygon.get(index + 1).localize(tileA, tileB);
                    addTriangle(output, origin, p1, p2, direction, material);
                }
            }
        }
    }

    private static void splitLongTriangle(List<Triangle> output,
                                          ParameterVertex a, ParameterVertex b, ParameterVertex c,
                                          Direction direction,
                                          ArcMaterialHelper.FaceMaterial material) {
        float rangeA = Math.max(a.a, Math.max(b.a, c.a)) - Math.min(a.a, Math.min(b.a, c.a));
        float rangeB = Math.max(a.b, Math.max(b.b, c.b)) - Math.min(a.b, Math.min(b.b, c.b));
        ParameterVertex ab = a.lerp(b, 0.5f);
        ParameterVertex bc = b.lerp(c, 0.5f);
        ParameterVertex ca = c.lerp(a, 0.5f);
        if (Math.max(rangeA, rangeB) < 1.0f + EPS) {
            addTriangle(output, a.withLocalFraction(), b.withLocalFraction(), c.withLocalFraction(), direction, material);
            return;
        }
        splitTriangleByTiles(output, a, ab, ca, direction, material);
        splitTriangleByTiles(output, ab, b, bc, direction, material);
        splitTriangleByTiles(output, ca, bc, c, direction, material);
        splitTriangleByTiles(output, ab, bc, ca, direction, material);
    }

    private static List<ParameterVertex> clip(List<ParameterVertex> input,
                                               boolean firstAxis, float boundary,
                                               boolean keepGreater) {
        if (input.isEmpty()) return input;
        List<ParameterVertex> output = new ArrayList<>();
        ParameterVertex previous = input.get(input.size() - 1);
        boolean previousInside = inside(previous, firstAxis, boundary, keepGreater);
        for (ParameterVertex current : input) {
            boolean currentInside = inside(current, firstAxis, boundary, keepGreater);
            if (currentInside != previousInside) {
                float previousValue = firstAxis ? previous.a : previous.b;
                float currentValue = firstAxis ? current.a : current.b;
                float denominator = currentValue - previousValue;
                float amount = Math.abs(denominator) < EPS ? 0.0f : (boundary - previousValue) / denominator;
                output.add(previous.lerp(current, clamp01(amount)));
            }
            if (currentInside) output.add(current);
            previous = current;
            previousInside = currentInside;
        }
        return output;
    }

    private static boolean inside(ParameterVertex vertex, boolean firstAxis,
                                  float boundary, boolean keepGreater) {
        float value = firstAxis ? vertex.a : vertex.b;
        return keepGreater ? value >= boundary - EPS : value <= boundary + EPS;
    }

    private static void addTriangle(List<Triangle> output,
                                    ParameterVertex a, ParameterVertex b, ParameterVertex c,
                                    Direction direction,
                                    ArcMaterialHelper.FaceMaterial material) {
        Vector3f n = normal(a, b, c);
        if (n == null) return;
        Vector3f expected = new Vector3f(direction.getOffsetX(), direction.getOffsetY(), direction.getOffsetZ());
        if (n.dot(expected) < 0.0f) {
            ParameterVertex swap = b;
            b = c;
            c = swap;
            n.mul(-1.0f);
        }
        output.add(new Triangle(a, b, c, n.x, n.y, n.z,
                ArcMaterialHelper.dominant(n.x, n.y, n.z), material));
    }

    private static ComponentAtlas atlasFor(ArcRibbonBlockEntity entity) {
        AtlasHandle cached = ATLAS_CACHE.get(entity);
        if (cached != null && cached.entityRevision == entity.getRenderRevision()) return cached.atlas;
        ComponentAtlas atlas = ComponentAtlas.build(entity);
        for (ArcRibbonBlockEntity member : atlas.members) {
            ATLAS_CACHE.put(member, new AtlasHandle(member.getRenderRevision(), atlas));
        }
        return atlas;
    }

    private static final class ComponentAtlas {
        final List<ArcRibbonBlockEntity> members;
        final List<AtlasSegment> segments;
        final int revision;
        final float phaseS;
        final float phaseW;
        final float phaseN;
        final float scaleS;
        final int visibleTiles;

        private ComponentAtlas(List<ArcRibbonBlockEntity> members,
                               List<AtlasSegment> segments,
                               int revision,
                               float phaseS, float phaseW, float phaseN,
                               float scaleS, int visibleTiles) {
            this.members = members;
            this.segments = segments;
            this.revision = revision;
            this.phaseS = phaseS;
            this.phaseW = phaseW;
            this.phaseN = phaseN;
            this.scaleS = scaleS;
            this.visibleTiles = visibleTiles;
        }

        static ComponentAtlas build(ArcRibbonBlockEntity target) {
            List<ArcRibbonBlockEntity> members = discoverComponent(target);
            List<RawSegment> raw = new ArrayList<>();
            int revision = 1;
            for (ArcRibbonBlockEntity member : members) {
                revision = 31 * revision + member.getRenderRevision();
                revision = 31 * revision + member.getPos().hashCode();
                extractSegments(member, raw);
                if (raw.size() >= MAX_COMPONENT_SEGMENTS) break;
            }
            List<AtlasSegment> ordered = orderSegments(raw);
            if (ordered.isEmpty()) return new ComponentAtlas(members, List.of(), revision, 0, 0, 0, 1, 1);

            ComponentAtlas temporary = new ComponentAtlas(members, ordered, revision, 0, 0, 0, 1, 1);
            float minW = Float.POSITIVE_INFINITY;
            float minN = Float.POSITIVE_INFINITY;
            for (ArcRibbonBlockEntity member : members) {
                for (ArcRibbonBlockEntity.Prism prism : member.getPrisms()) {
                    float[] xyz = prism.xyz();
                    for (int vertex = 0; vertex < 8; vertex++) {
                        int p = vertex * 3;
                        CurveCoordinate coordinate = temporary.locate(
                                member.getPos().getX() + xyz[p],
                                member.getPos().getY() + xyz[p + 1],
                                member.getPos().getZ() + xyz[p + 2]);
                        minW = Math.min(minW, coordinate.w);
                        minN = Math.min(minN, coordinate.n);
                    }
                }
            }
            if (!Float.isFinite(minW)) minW = 0.0f;
            if (!Float.isFinite(minN)) minN = 0.0f;
            // Endpoint blocks render with normal block-local UVs. The curve may extend slightly into
            // both endpoints to hide geometry seams, so solve texture scale using only the visible
            // distance between the two block faces. Start and end connection planes then both have an
            // integer S coordinate and can match native endpoint/adjacent block textures simultaneously.
            AtlasSegment first = ordered.get(0);
            AtlasSegment last = ordered.get(ordered.size() - 1);
            float totalLength = last.s0 + last.length;
            float startInset = gridInset(first.c0, first.tangent);
            float endInset = gridInset(last.c1, last.tangent.multiply(-1.0f));
            float visibleLength = Math.max(EPS, totalLength - startInset - endInset);
            int visibleTiles = Math.max(1, Math.round(visibleLength));
            float scaleS = visibleTiles / visibleLength;
            float phaseS = -startInset * scaleS;
            return new ComponentAtlas(members, ordered, revision,
                    phaseS, -minW, -minN, scaleS, visibleTiles);
        }

        CurveCoordinate locate(double x, double y, double z) {
            Vec3 point = new Vec3((float)x, (float)y, (float)z);
            AtlasSegment nearest = segments.get(0);
            float ownershipAlong = nearest.clampedAlong(point);
            float best = nearest.distanceSquared(point, ownershipAlong);
            for (int index = 1; index < segments.size(); index++) {
                AtlasSegment candidate = segments.get(index);
                float along = candidate.clampedAlong(point);
                float distance = candidate.distanceSquared(point, along);
                if (distance < best) {
                    best = distance;
                    nearest = candidate;
                    ownershipAlong = along;
                }
            }
            // Ownership is selected on the finite segment; final projection is intentionally
            // unbounded so endpoint overlay faces continue into the endpoint blocks without freezing.
            float along = point.subtract(nearest.c0).dot(nearest.tangent);
            float frameT = clamp01(along / nearest.length);
            Vec3 centre = nearest.c0.add(nearest.tangent.multiply(along));
            Vec3 width = Vec3.lerp(nearest.width0, nearest.width1, frameT)
                    .normalizeOr(nearest.width0);
            Vec3 radial = Vec3.lerp(nearest.radial0, nearest.radial1, frameT)
                    .normalizeOr(nearest.radial0);
            Vec3 offset = point.subtract(centre);
            return new CurveCoordinate(nearest.s0 + along,
                    offset.dot(width), offset.dot(radial),
                    nearest.tangent, width, radial);
        }
    }

    /** Distance from a centreline endpoint to the nearest grid plane in its travel direction.
     * Only the dominant tangent axis is used, preventing an unrelated integer coordinate on another
     * axis from being mistaken for the endpoint connection plane. */
    private static float gridInset(Vec3 point, Vec3 direction) {
        float ax = Math.abs(direction.x), ay = Math.abs(direction.y), az = Math.abs(direction.z);
        float coordinate;
        float component;
        if (ax >= ay && ax >= az) { coordinate = point.x; component = direction.x; }
        else if (ay >= az) { coordinate = point.y; component = direction.y; }
        else { coordinate = point.z; component = direction.z; }
        if (Math.abs(component) < EPS) return 0.0f;
        float nearestInteger = Math.round(coordinate);
        if (Math.abs(coordinate - nearestInteger) < 1.0E-4f) return 0.0f;
        double boundary = component > 0.0f
                ? Math.ceil(coordinate - 1.0E-7)
                : Math.floor(coordinate + 1.0E-7);
        float distance = (float)((boundary - coordinate) / component);
        // Endpoint bridge overlap is intentionally small. A larger value means this segment did not
        // start inside an endpoint block and must not affect atlas phase.
        return distance >= -EPS && distance <= 0.26f ? Math.max(0.0f, distance) : 0.0f;
    }

    private static List<ArcRibbonBlockEntity> discoverComponent(ArcRibbonBlockEntity target) {
        if (target.getWorld() == null) return List.of(target);
        Set<ArcRibbonBlockEntity> accepted = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<ArcRibbonBlockEntity> queue = new ArrayDeque<>();
        Map<ArcRibbonBlockEntity, List<RawSegment>> segmentCache = new IdentityHashMap<>();
        accepted.add(target);
        queue.add(target);
        while (!queue.isEmpty() && accepted.size() < MAX_COMPONENT_ENTITIES) {
            ArcRibbonBlockEntity current = queue.removeFirst();
            List<RawSegment> currentSegments = segmentCache.computeIfAbsent(current, UnifiedSurfaceArcRenderer::segmentsOf);
            BlockPos origin = current.getPos();
            for (int dx = -DISCOVERY_RADIUS; dx <= DISCOVERY_RADIUS; dx++) {
                for (int dy = -DISCOVERY_RADIUS; dy <= DISCOVERY_RADIUS; dy++) {
                    for (int dz = -DISCOVERY_RADIUS; dz <= DISCOVERY_RADIUS; dz++) {
                        BlockEntity blockEntity = target.getWorld().getBlockEntity(origin.add(dx, dy, dz));
                        if (!(blockEntity instanceof ArcRibbonBlockEntity candidate)) continue;
                        if (accepted.contains(candidate)) continue;
                        if (!candidate.getSourceState().equals(target.getSourceState())) continue;
                        List<RawSegment> candidateSegments = segmentCache.computeIfAbsent(candidate, UnifiedSurfaceArcRenderer::segmentsOf);
                        if (connected(current, currentSegments, candidate, candidateSegments)) {
                            accepted.add(candidate);
                            queue.add(candidate);
                        }
                    }
                }
            }
        }
        List<ArcRibbonBlockEntity> result = new ArrayList<>(accepted);
        result.sort(Comparator.comparing(ArcRibbonBlockEntity::getPos, UnifiedSurfaceArcRenderer::comparePos));
        return List.copyOf(result);
    }

    private static boolean connected(ArcRibbonBlockEntity a, List<RawSegment> aSegments,
                                     ArcRibbonBlockEntity b, List<RawSegment> bSegments) {
        if (aSegments.isEmpty() || bSegments.isEmpty()) {
            return a.getPos().getSquaredDistance(b.getPos()) <= 12.0;
        }
        float limitSquared = JOIN_EPS * JOIN_EPS;
        for (RawSegment first : aSegments) {
            for (RawSegment second : bSegments) {
                if (first.c0.distanceSquared(second.c0) <= limitSquared
                        || first.c0.distanceSquared(second.c1) <= limitSquared
                        || first.c1.distanceSquared(second.c0) <= limitSquared
                        || first.c1.distanceSquared(second.c1) <= limitSquared) return true;
            }
        }
        return false;
    }

    private static List<RawSegment> segmentsOf(ArcRibbonBlockEntity entity) {
        List<RawSegment> result = new ArrayList<>();
        extractSegments(entity, result);
        return result;
    }

    private static void extractSegments(ArcRibbonBlockEntity entity, List<RawSegment> output) {
        float ox = entity.getPos().getX();
        float oy = entity.getPos().getY();
        float oz = entity.getPos().getZ();
        for (ArcRibbonBlockEntity.Prism prism : entity.getPrisms()) {
            float[] v = prism.xyz();
            Vec3 c0 = average(v, 0, 4).add(new Vec3(ox, oy, oz));
            Vec3 c1 = average(v, 4, 8).add(new Vec3(ox, oy, oz));
            if (c0.distanceSquared(c1) < EPS * EPS) continue;
            Vec3 width0 = point(v, 1).subtract(point(v, 0))
                    .add(point(v, 2).subtract(point(v, 3))).normalizeOr(new Vec3(0, 0, 1));
            Vec3 width1 = point(v, 5).subtract(point(v, 4))
                    .add(point(v, 6).subtract(point(v, 7))).normalizeOr(width0);
            Vec3 radial0 = point(v, 3).subtract(point(v, 0))
                    .add(point(v, 2).subtract(point(v, 1))).normalizeOr(new Vec3(0, 1, 0));
            Vec3 radial1 = point(v, 7).subtract(point(v, 4))
                    .add(point(v, 6).subtract(point(v, 5))).normalizeOr(radial0);
            output.add(new RawSegment(c0, c1, width0, width1, radial0, radial1));
            if (output.size() >= MAX_COMPONENT_SEGMENTS) return;
        }
    }

    private static List<AtlasSegment> orderSegments(List<RawSegment> raw) {
        if (raw.isEmpty()) return List.of();
        boolean[] used = new boolean[raw.size()];
        EndpointChoice start = chooseStart(raw);
        List<AtlasSegment> result = new ArrayList<>(raw.size());
        Vec3 current = start.point;
        Vec3 previousWidth = null;
        Vec3 previousRadial = null;
        float cumulative = 0.0f;
        int remaining = raw.size();
        while (remaining > 0) {
            int selected = -1;
            boolean reverse = false;
            float best = Float.POSITIVE_INFINITY;
            for (int index = 0; index < raw.size(); index++) {
                if (used[index]) continue;
                RawSegment segment = raw.get(index);
                float d0 = current.distanceSquared(segment.c0);
                float d1 = current.distanceSquared(segment.c1);
                if (d0 < best) { best = d0; selected = index; reverse = false; }
                if (d1 < best) { best = d1; selected = index; reverse = true; }
            }
            if (selected < 0) break;
            // A disconnected accidental neighbour must not be folded into the same texture atlas.
            if (!result.isEmpty() && best > JOIN_EPS * JOIN_EPS) break;
            used[selected] = true;
            remaining--;
            RawSegment rawSegment = raw.get(selected);
            RawSegment oriented = reverse ? rawSegment.reversed() : rawSegment;
            Vec3 width0 = oriented.width0;
            Vec3 width1 = oriented.width1;
            Vec3 radial0 = oriented.radial0;
            Vec3 radial1 = oriented.radial1;
            if (previousWidth != null && previousWidth.dot(width0) < 0.0f) {
                width0 = width0.multiply(-1.0f);
                width1 = width1.multiply(-1.0f);
            }
            if (previousRadial != null && previousRadial.dot(radial0) < 0.0f) {
                radial0 = radial0.multiply(-1.0f);
                radial1 = radial1.multiply(-1.0f);
            }
            Vec3 delta = oriented.c1.subtract(oriented.c0);
            float length = delta.length();
            if (length < EPS) continue;
            AtlasSegment segment = new AtlasSegment(oriented.c0, oriented.c1,
                    delta.multiply(1.0f / length), width0, width1,
                    radial0, radial1, length, cumulative);
            result.add(segment);
            cumulative += length;
            current = oriented.c1;
            previousWidth = width1;
            previousRadial = radial1;
        }
        return List.copyOf(result);
    }

    private static EndpointChoice chooseStart(List<RawSegment> segments) {
        List<Vec3> endpoints = new ArrayList<>(segments.size() * 2);
        for (RawSegment segment : segments) { endpoints.add(segment.c0); endpoints.add(segment.c1); }
        Vec3 bestOpen = null;
        Vec3 bestAny = null;
        for (int index = 0; index < endpoints.size(); index++) {
            Vec3 point = endpoints.get(index);
            if (bestAny == null || compare(point, bestAny) < 0) bestAny = point;
            int neighbors = 0;
            for (int other = 0; other < endpoints.size(); other++) {
                if (other == index) continue;
                if (point.distanceSquared(endpoints.get(other)) <= JOIN_EPS * JOIN_EPS) neighbors++;
            }
            if (neighbors == 0 && (bestOpen == null || compare(point, bestOpen) < 0)) bestOpen = point;
        }
        return new EndpointChoice(bestOpen == null ? bestAny : bestOpen);
    }

    private enum CoordinatePair {
        ARC_WIDTH {
            float first(CurveCoordinate c, ComponentAtlas a) { return c.s * a.scaleS + a.phaseS; }
            float second(CurveCoordinate c, ComponentAtlas a) { return c.w + a.phaseW; }
        },
        ARC_THICKNESS {
            float first(CurveCoordinate c, ComponentAtlas a) { return c.s * a.scaleS + a.phaseS; }
            float second(CurveCoordinate c, ComponentAtlas a) { return c.n + a.phaseN; }
        },
        WIDTH_THICKNESS {
            float first(CurveCoordinate c, ComponentAtlas a) { return c.w + a.phaseW; }
            float second(CurveCoordinate c, ComponentAtlas a) { return c.n + a.phaseN; }
        };

        abstract float first(CurveCoordinate coordinate, ComponentAtlas atlas);
        abstract float second(CurveCoordinate coordinate, ComponentAtlas atlas);

        static CoordinatePair forFace(Vector3f normal, CurveCoordinate coordinate) {
            Vec3 n = new Vec3(normal.x, normal.y, normal.z);
            float along = Math.abs(n.dot(coordinate.tangent));
            float across = Math.abs(n.dot(coordinate.width));
            float radial = Math.abs(n.dot(coordinate.radial));
            if (along >= across && along >= radial) return WIDTH_THICKNESS;
            if (across >= radial) return ARC_THICKNESS;
            return ARC_WIDTH;
        }
    }

    private static Vector3f normal(GeometryVertex a, GeometryVertex b, GeometryVertex c) {
        Vector3f first = new Vector3f(b.lx - a.lx, b.ly - a.ly, b.lz - a.lz);
        Vector3f second = new Vector3f(c.lx - a.lx, c.ly - a.ly, c.lz - a.lz);
        Vector3f result = first.cross(second);
        return result.lengthSquared() < 1.0E-10f ? null : result.normalize();
    }

    private static Vector3f normal(ParameterVertex a, ParameterVertex b, ParameterVertex c) {
        Vector3f first = new Vector3f(b.x - a.x, b.y - a.y, b.z - a.z);
        Vector3f second = new Vector3f(c.x - a.x, c.y - a.y, c.z - a.z);
        Vector3f result = first.cross(second);
        return result.lengthSquared() < 1.0E-10f ? null : result.normalize();
    }

    private static GeometryVertex average(GeometryVertex[] vertices) {
        float lx = 0, ly = 0, lz = 0;
        double wx = 0, wy = 0, wz = 0;
        for (GeometryVertex vertex : vertices) {
            lx += vertex.lx; ly += vertex.ly; lz += vertex.lz;
            wx += vertex.wx; wy += vertex.wy; wz += vertex.wz;
        }
        float scale = 1.0f / vertices.length;
        return new GeometryVertex(lx * scale, ly * scale, lz * scale,
                wx / vertices.length, wy / vertices.length, wz / vertices.length);
    }

    private static float distance(GeometryVertex a, GeometryVertex b) {
        float x = b.lx - a.lx, y = b.ly - a.ly, z = b.lz - a.lz;
        return (float)Math.sqrt(x * x + y * y + z * z);
    }

    private static void emit(VertexConsumer consumer, Matrix4f position, Matrix3f normal,
                             ParameterVertex vertex, Triangle triangle,
                             int light, int overlay, int red, int green, int blue) {
        consumer.vertex(position, vertex.x, vertex.y, vertex.z)
                .color(red, green, blue, 255)
                .texture(triangle.material.u(clamp01(vertex.a), clamp01(vertex.b)),
                        triangle.material.v(clamp01(vertex.a), clamp01(vertex.b)))
                .overlay(overlay).light(light)
                .normal(normal, triangle.nx, triangle.ny, triangle.nz).next();
    }

    private static int floorTile(float value) { return (int)Math.floor(value + EPS); }
    private static int ceilTile(float value) { return (int)Math.ceil(value - EPS); }
    private static float clamp01(float value) { return Math.max(0.0f, Math.min(1.0f, value)); }
    private static int maxPacked(int a, int b) {
        int block = Math.max(a & 0xFFFF, b & 0xFFFF);
        int sky = Math.max((a >>> 16) & 0xFFFF, (b >>> 16) & 0xFFFF);
        return block | (sky << 16);
    }
    private static int comparePos(BlockPos a, BlockPos b) {
        int y = Integer.compare(a.getY(), b.getY());
        if (y != 0) return y;
        int x = Integer.compare(a.getX(), b.getX());
        return x != 0 ? x : Integer.compare(a.getZ(), b.getZ());
    }
    private static int compare(Vec3 a, Vec3 b) {
        int y = Float.compare(a.y, b.y);
        if (y != 0) return y;
        int x = Float.compare(a.x, b.x);
        return x != 0 ? x : Float.compare(a.z, b.z);
    }
    private static Vec3 point(float[] data, int index) {
        return new Vec3(data[index * 3], data[index * 3 + 1], data[index * 3 + 2]);
    }
    private static Vec3 average(float[] data, int from, int to) {
        Vec3 result = new Vec3(0, 0, 0);
        for (int index = from; index < to; index++) result = result.add(point(data, index));
        return result.multiply(1.0f / (to - from));
    }

    private record GeometryVertex(float lx, float ly, float lz, double wx, double wy, double wz) {}
    private record ParameterVertex(float x, float y, float z, float a, float b) {
        ParameterVertex lerp(ParameterVertex other, float amount) {
            return new ParameterVertex(x + (other.x - x) * amount,
                    y + (other.y - y) * amount,
                    z + (other.z - z) * amount,
                    a + (other.a - a) * amount,
                    b + (other.b - b) * amount);
        }
        ParameterVertex localize(int tileA, int tileB) {
            return new ParameterVertex(x, y, z, clamp01(a - tileA), clamp01(b - tileB));
        }
        ParameterVertex withLocalFraction() {
            return new ParameterVertex(x, y, z,
                    a - (float)Math.floor(a), b - (float)Math.floor(b));
        }
    }
    private record Triangle(ParameterVertex a, ParameterVertex b, ParameterVertex c,
                            float nx, float ny, float nz,
                            Direction direction, ArcMaterialHelper.FaceMaterial material) {}
    private record CompiledMesh(int revision, int atlasRevision, List<Triangle> triangles) {}
    private record AtlasHandle(int entityRevision, ComponentAtlas atlas) {}
    private record CurveCoordinate(float s, float w, float n,
                                   Vec3 tangent, Vec3 width, Vec3 radial) {}
    private record EndpointChoice(Vec3 point) {}
    private record RawSegment(Vec3 c0, Vec3 c1,
                              Vec3 width0, Vec3 width1,
                              Vec3 radial0, Vec3 radial1) {
        RawSegment reversed() { return new RawSegment(c1, c0, width1, width0, radial1, radial0); }
    }
    private record AtlasSegment(Vec3 c0, Vec3 c1, Vec3 tangent,
                                Vec3 width0, Vec3 width1,
                                Vec3 radial0, Vec3 radial1,
                                float length, float s0) {
        float clampedAlong(Vec3 point) {
            return Math.max(0.0f, Math.min(length, point.subtract(c0).dot(tangent)));
        }
        float distanceSquared(Vec3 point, float along) {
            return c0.add(tangent.multiply(along)).distanceSquared(point);
        }
    }
    private record Vec3(float x, float y, float z) {
        Vec3 add(Vec3 other) { return new Vec3(x + other.x, y + other.y, z + other.z); }
        Vec3 subtract(Vec3 other) { return new Vec3(x - other.x, y - other.y, z - other.z); }
        Vec3 multiply(float amount) { return new Vec3(x * amount, y * amount, z * amount); }
        float dot(Vec3 other) { return x * other.x + y * other.y + z * other.z; }
        float length() { return (float)Math.sqrt(x * x + y * y + z * z); }
        float distanceSquared(Vec3 other) {
            float dx = x - other.x, dy = y - other.y, dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
        Vec3 normalizeOr(Vec3 fallback) {
            float length = length();
            return length < EPS ? fallback : multiply(1.0f / length);
        }
        static Vec3 lerp(Vec3 a, Vec3 b, float t) {
            return new Vec3(a.x + (b.x - a.x) * t,
                    a.y + (b.y - a.y) * t,
                    a.z + (b.z - a.z) * t);
        }
    }
}
