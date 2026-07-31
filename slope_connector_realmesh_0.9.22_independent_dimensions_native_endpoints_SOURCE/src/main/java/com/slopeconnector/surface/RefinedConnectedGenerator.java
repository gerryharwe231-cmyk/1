package com.slopeconnector.surface;

import com.slopeconnector.connected.ConnectedArcBlockEntity;
import com.slopeconnector.connected.ConnectedArcMod;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generator used only by the dedicated connected-profile wand.
 *
 * <p>The centerline always enters the geometric middle of the selected endpoint side.  A short
 * cardinal lead is kept at both ends, so a wide newel/post cannot pull the first railing module
 * toward its left or right edge.  The visible modules are placed at equal arc-length intervals;
 * each module remains roughly one source block long and is internally bent by the renderer.</p>
 */
public final class RefinedConnectedGenerator {
    private static final double ENDPOINT_OVERLAP = 2.0 / 16.0;
    private static final double MIN_END_STRAIGHT = 8.0 / 16.0;
    private static final int LENGTH_TABLE_STEPS = 2048;
    private static final int MAX_SECTIONS = 512;
    private static final Method IS_SUPPORTED;
    private static final Method STRAIGHT_STATE;

    static {
        try {
            Class<?> classifier = Class.forName("com.slopeconnector.connected.ConnectedBlockClassifier");
            IS_SUPPORTED = classifier.getDeclaredMethod("isSupported", BlockState.class);
            STRAIGHT_STATE = classifier.getDeclaredMethod("straightState", BlockState.class);
            IS_SUPPORTED.setAccessible(true);
            STRAIGHT_STATE.setAccessible(true);
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    public record Result(int placed, int sections, String error) {
        public static Result error(String message) { return new Result(0, 0, message); }
    }

    private record HolderData(List<ConnectedArcBlockEntity.Section> sections,
                              List<ConnectedArcBlockEntity.CollisionBox> boxes) {
        HolderData() { this(new ArrayList<>(), new ArrayList<>()); }
    }

    private record FaceAnchor(Vec3d point, Direction outward) {}
    private record SourceBounds(double minX, double maxX, double minZ, double maxZ,
                                double centerZ, List<Box> boxes) {
        double moduleLength() {
            // Connected models are authored as one block modules.  Keep unusual overhangs from
            // changing post spacing, but allow true half-length modular pieces.
            return clamp(maxX - minX, 0.5, 1.0);
        }
    }
    private record Frame(Vec3d center, Vec3d tangent, Vec3d side) {}

    private interface Curve {
        Vec3d point(double t);
        Vec3d derivative(double t);
    }

    private record Line(Vec3d p0, Vec3d p1) implements Curve {
        @Override public Vec3d point(double t) { return p0.lerp(p1, t); }
        @Override public Vec3d derivative(double t) { return p1.subtract(p0); }
    }

    private record Cubic(Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3) implements Curve {
        @Override public Vec3d point(double t) {
            double u = 1.0 - t;
            return p0.multiply(u*u*u)
                    .add(p1.multiply(3.0*u*u*t))
                    .add(p2.multiply(3.0*u*t*t))
                    .add(p3.multiply(t*t*t));
        }
        @Override public Vec3d derivative(double t) {
            double u = 1.0 - t;
            return p1.subtract(p0).multiply(3.0*u*u)
                    .add(p2.subtract(p1).multiply(6.0*u*t))
                    .add(p3.subtract(p2).multiply(3.0*t*t));
        }
    }

    /** Equal parameter spans are fine because final sampling is reparameterized by arc length. */
    private record Piecewise(List<Curve> pieces) implements Curve {
        @Override public Vec3d point(double t) {
            SegmentParameter p = parameter(t);
            return pieces.get(p.index).point(p.local);
        }
        @Override public Vec3d derivative(double t) {
            SegmentParameter p = parameter(t);
            return pieces.get(p.index).derivative(p.local).multiply(pieces.size());
        }
        private SegmentParameter parameter(double t) {
            if (pieces.size() == 1) return new SegmentParameter(0, clamp(t, 0.0, 1.0));
            double scaled = clamp(t, 0.0, 1.0) * pieces.size();
            int index = Math.min(pieces.size() - 1, (int)Math.floor(scaled));
            return new SegmentParameter(index, index == pieces.size() - 1 && t >= 1.0
                    ? 1.0 : scaled - index);
        }
    }
    private record SegmentParameter(int index, double local) {}

    private RefinedConnectedGenerator() {}

    public static Result generate(ServerWorld world, BlockPos templatePos, BlockState templateState,
                                  BlockPos startPos, BlockPos controlPos, BlockPos endPos,
                                  boolean threePoint, int sideMode) {
        try {
            if (!(boolean)IS_SUPPORTED.invoke(null, templateState)) {
                return Result.error("选择的模板不是可连接的栏杆、围栏、墙或玻璃板类方块");
            }
        } catch (ReflectiveOperationException error) {
            return Result.error("无法读取栏杆连接状态：" + error.getMessage());
        }
        if (startPos.equals(endPos)) return Result.error("两个端点不能相同");
        if (world.getBlockState(startPos).isAir() || world.getBlockState(endPos).isAir()) {
            return Result.error("两个端点必须是已放置的方块");
        }
        if (startPos.getY() != endPos.getY()) return Result.error("栏杆弧的两个端点必须处于同一高度");
        if (threePoint && controlPos != null && controlPos.getY() != startPos.getY()) {
            return Result.error("三点模式的定位点必须与两个端点处于同一高度");
        }

        BlockState straightState;
        try {
            straightState = (BlockState)STRAIGHT_STATE.invoke(null, templateState);
        } catch (ReflectiveOperationException error) {
            return Result.error("无法取得栏杆直连模型：" + error.getMessage());
        }
        SourceBounds source = sourceBounds(world, templatePos, straightState);
        if (source == null || source.maxX - source.minX < 1.0E-4) {
            return Result.error("栏杆直连模型没有有效长度");
        }

        Vec3d startCenter = baseCenter(startPos);
        Vec3d endCenter = baseCenter(endPos);
        Vec3d controlCenter = controlPos == null ? null : baseCenter(controlPos);
        Direction startDirection;
        Direction endDirection;
        if (threePoint && controlCenter != null) {
            startDirection = snapCardinal(controlCenter.subtract(startCenter));
            endDirection = snapCardinal(endCenter.subtract(controlCenter));
        } else {
            Direction[] pair = tangentPair(endCenter.subtract(startCenter), sideMode);
            startDirection = pair[0];
            endDirection = pair[1];
        }
        Vec3d startTangent = unit(startDirection);
        Vec3d endTangent = unit(endDirection);

        FaceAnchor startAnchor = endpointAnchor(world, startPos, startDirection);
        FaceAnchor endAnchor = endpointAnchor(world, endPos, endDirection.getOpposite());
        // Move just inside each endpoint so a wide post hides the join.  The lateral coordinate is
        // never taken from an extreme sub-box; it is always the full endpoint's face centre.
        Vec3d startPoint = startAnchor.point.subtract(startTangent.multiply(ENDPOINT_OVERLAP));
        Vec3d endPoint = endAnchor.point.add(endTangent.multiply(ENDPOINT_OVERLAP));

        double directDistance = horizontalDistance(startPoint, endPoint);
        if (directDistance < 0.25) return Result.error("两个端点的连接面距离太短");
        // A complete straight source module is reserved at both ends whenever space allows.  The
        // mandatory frame at each lead boundary prevents a model module from straddling the
        // straight-to-curve transition, which was the reason endpoint pieces appeared crooked.
        double lead = Math.min(source.moduleLength(), Math.max(MIN_END_STRAIGHT, directDistance * 0.14));
        lead = Math.min(lead, directDistance * 0.24);
        if (lead < 1.0 / 16.0) lead = Math.max(1.0 / 32.0, directDistance * 0.18);
        Vec3d startCore = startPoint.add(startTangent.multiply(lead));
        Vec3d endCore = endPoint.subtract(endTangent.multiply(lead));

        Curve middle;
        if (threePoint && controlCenter != null) {
            middle = throughControl(startCore, controlCenter, endCore, startTangent, endTangent);
        } else {
            middle = twoPoint(startCore, endCore, startTangent, endTangent, sideMode);
        }
        List<Frame> frames = sampleWithMandatoryLeads(startPoint, startCore, middle,
                endCore, endPoint, source.moduleLength(), startTangent, endTangent);
        if (frames.size() < 2) return Result.error("无法生成有效栏杆弧线");
        if (frames.size() - 1 > MAX_SECTIONS) return Result.error("弧线过长，请缩短端点距离");

        Map<BlockPos, HolderData> grouped = new LinkedHashMap<>();
        Set<BlockPos> protectedPositions = new HashSet<>();
        protectedPositions.add(startPos); protectedPositions.add(endPos);
        if (controlPos != null) protectedPositions.add(controlPos);

        for (int index = 0; index < frames.size() - 1; index++) {
            Frame a = frames.get(index), b = frames.get(index + 1);
            Vec3d midpoint = a.center.add(b.center).multiply(0.5);
            BlockPos holder = chooseHolder(world, midpoint, protectedPositions, grouped.keySet());
            if (holder == null) return Result.error("弧线附近没有可用空间放置承载方块");
            HolderData data = grouped.computeIfAbsent(holder, key -> new HolderData());
            data.sections.add(localSection(holder, a, b));
            addCollision(data.boxes, holder, a, b, source);
        }

        // Validate every position before changing the world, so failure never leaves half an arc.
        for (BlockPos holder : grouped.keySet()) {
            BlockState existing = world.getBlockState(holder);
            if (!existing.isAir() && existing.getBlock() != ConnectedArcMod.CONNECTED_ARC) {
                return Result.error("弧线经过了非空气方块，生成已取消：" + holder.toShortString());
            }
            BlockEntity blockEntity = world.getBlockEntity(holder);
            if (blockEntity != null && !(blockEntity instanceof ConnectedArcBlockEntity)) {
                return Result.error("弧线经过了机器或其他方块实体，生成已取消");
            }
        }

        int placed = 0;
        for (Map.Entry<BlockPos, HolderData> entry : grouped.entrySet()) {
            BlockPos holder = entry.getKey();
            world.setBlockState(holder, ConnectedArcMod.CONNECTED_ARC.getDefaultState(), 3);
            BlockEntity blockEntity = world.getBlockEntity(holder);
            if (!(blockEntity instanceof ConnectedArcBlockEntity connected)) {
                return Result.error("创建栏杆弧承载方块失败");
            }
            HolderData data = entry.getValue();
            connected.setData(templateState, straightState, data.sections, data.boxes);
            world.updateListeners(holder, world.getBlockState(holder), world.getBlockState(holder), 3);
            placed++;
        }

        // The custom holder is not a vanilla FenceBlock/PaneBlock/WallBlock, so endpoints cannot
        // discover it through their normal canConnect checks. Force the exact arc-facing side once;
        // the neighbor-update Mixin then keeps this state correct when ordinary railings are added or
        // removed on any other side later.
        ConnectionStateHelper.forceWorldConnection(world, startPos, startDirection);
        ConnectionStateHelper.forceWorldConnection(world, endPos, endDirection.getOpposite());
        return new Result(placed, frames.size() - 1, "");
    }

    private static Curve twoPoint(Vec3d start, Vec3d end, Vec3d startTangent,
                                  Vec3d endTangent, int sideMode) {
        double distance = horizontalDistance(start, end);
        if (sameHorizontalDirection(startTangent, endTangent)) {
            Vec3d chord = horizontalUnit(end.subtract(start), startTangent);
            Vec3d normal = new Vec3d(-chord.z, 0.0, chord.x)
                    .multiply(sideMode < 0 ? -1.0 : 1.0);
            double bulge = Math.max(0.30, Math.min(distance * 0.28, 6.0));
            Vec3d middle = start.add(end).multiply(0.5).add(normal.multiply(bulge));
            Vec3d middleTangent = chord;
            return new Piecewise(List.of(
                    hermite(start, middle, startTangent.multiply(distance * 0.45),
                            middleTangent.multiply(distance * 0.38)),
                    hermite(middle, end, middleTangent.multiply(distance * 0.38),
                            endTangent.multiply(distance * 0.45))));
        }
        double handle = Math.max(0.45, distance * 0.55);
        return new Cubic(start, start.add(startTangent.multiply(handle)),
                end.subtract(endTangent.multiply(handle)), end);
    }

    private static Curve throughControl(Vec3d start, Vec3d control, Vec3d end,
                                        Vec3d startTangent, Vec3d endTangent) {
        Vec3d middleTangent = horizontalUnit(end.subtract(start),
                horizontalUnit(end.subtract(control), startTangent));
        double firstLength = horizontalDistance(start, control);
        double secondLength = horizontalDistance(control, end);
        return new Piecewise(List.of(
                hermite(start, control,
                        startTangent.multiply(Math.max(0.30, firstLength * 0.52)),
                        middleTangent.multiply(Math.max(0.30, firstLength * 0.46))),
                hermite(control, end,
                        middleTangent.multiply(Math.max(0.30, secondLength * 0.46)),
                        endTangent.multiply(Math.max(0.30, secondLength * 0.52)))));
    }

    private static Cubic hermite(Vec3d start, Vec3d end, Vec3d startDerivative,
                                 Vec3d endDerivative) {
        return new Cubic(start, start.add(startDerivative.multiply(1.0 / 3.0)),
                end.subtract(endDerivative.multiply(1.0 / 3.0)), end);
    }

    private static List<Frame> sampleWithMandatoryLeads(Vec3d startPoint, Vec3d startCore,
                                                         Curve middle, Vec3d endCore, Vec3d endPoint,
                                                         double moduleLength, Vec3d exactStartTangent,
                                                         Vec3d exactEndTangent) {
        List<Frame> frames = new ArrayList<>();
        addFrame(frames, startPoint, exactStartTangent);
        addFrame(frames, startCore, exactStartTangent);

        List<Frame> middleFrames = sampleCurveByArcLength(middle, moduleLength,
                exactStartTangent, exactEndTangent);
        for (int index = 1; index < middleFrames.size() - 1; index++) {
            Frame frame = middleFrames.get(index);
            addFrame(frames, frame.center, frame.tangent);
        }
        addFrame(frames, endCore, exactEndTangent);
        addFrame(frames, endPoint, exactEndTangent);
        return frames;
    }

    private static List<Frame> sampleCurveByArcLength(Curve curve, double moduleLength,
                                                       Vec3d exactStartTangent,
                                                       Vec3d exactEndTangent) {
        Vec3d[] points = new Vec3d[LENGTH_TABLE_STEPS + 1];
        double[] cumulative = new double[LENGTH_TABLE_STEPS + 1];
        points[0] = curve.point(0.0);
        for (int index = 1; index <= LENGTH_TABLE_STEPS; index++) {
            double t = index / (double)LENGTH_TABLE_STEPS;
            points[index] = curve.point(t);
            cumulative[index] = cumulative[index - 1] + points[index].distanceTo(points[index - 1]);
        }
        double totalLength = cumulative[LENGTH_TABLE_STEPS];
        int sections = Math.max(1, Math.min(MAX_SECTIONS,
                (int)Math.round(totalLength / Math.max(0.25, moduleLength))));
        List<Frame> frames = new ArrayList<>(sections + 1);
        for (int index = 0; index <= sections; index++) {
            double target = totalLength * index / sections;
            double t = parameterAtLength(cumulative, target);
            Vec3d center = curve.point(t);
            Vec3d fallback = index == 0 ? exactStartTangent
                    : (index == sections ? exactEndTangent : frames.get(index - 1).tangent);
            Vec3d tangent = horizontalUnit(curve.derivative(t), fallback);
            if (index == 0) tangent = exactStartTangent;
            if (index == sections) tangent = exactEndTangent;
            addFrame(frames, center, tangent);
        }
        return frames;
    }

    private static void addFrame(List<Frame> frames, Vec3d center, Vec3d tangent) {
        tangent = horizontalUnit(tangent, frames.isEmpty() ? new Vec3d(1.0, 0.0, 0.0)
                : frames.get(frames.size() - 1).tangent);
        if (!frames.isEmpty() && frames.get(frames.size() - 1).center.distanceTo(center) < 1.0E-5) {
            Frame previous = frames.get(frames.size() - 1);
            frames.set(frames.size() - 1, new Frame(center, tangent,
                    new Vec3d(-tangent.z, 0.0, tangent.x)));
            return;
        }
        frames.add(new Frame(center, tangent, new Vec3d(-tangent.z, 0.0, tangent.x)));
    }

    private static double parameterAtLength(double[] cumulative, double target) {
        int low = 0, high = cumulative.length - 1;
        while (low + 1 < high) {
            int middle = (low + high) >>> 1;
            if (cumulative[middle] < target) low = middle; else high = middle;
        }
        double a = cumulative[low], b = cumulative[high];
        double amount = b - a < 1.0E-10 ? 0.0 : (target - a) / (b - a);
        return (low + amount) / LENGTH_TABLE_STEPS;
    }

    /**
     * Returns the centre of the dominant outward connection face.  Face rectangles are grouped by
     * depth and the group with the largest visible area is selected; a thin decorative cap that
     * sticks out farther than the actual post can therefore never drag the rail to an edge.
     */
    private static FaceAnchor endpointAnchor(ServerWorld world, BlockPos pos, Direction outward) {
        List<Box> boxes;
        try {
            VoxelShape shape = world.getBlockState(pos).getOutlineShape(world, pos, ShapeContext.absent());
            boxes = shape.getBoundingBoxes();
        } catch (RuntimeException error) {
            boxes = List.of();
        }
        if (boxes.isEmpty()) {
            return new FaceAnchor(baseCenter(pos).add(unit(outward).multiply(0.5)), outward);
        }

        Map<Integer, FaceGroup> groups = new LinkedHashMap<>();
        for (Box box : boxes) {
            double coordinate = switch (outward) {
                case EAST -> box.maxX;
                case WEST -> box.minX;
                case SOUTH -> box.maxZ;
                case NORTH -> box.minZ;
                default -> 0.5;
            };
            if ((outward == Direction.EAST || outward == Direction.SOUTH) && coordinate < 0.5 - 1.0E-6) continue;
            if ((outward == Direction.WEST || outward == Direction.NORTH) && coordinate > 0.5 + 1.0E-6) continue;
            double lateralCenter = outward.getAxis() == Direction.Axis.X
                    ? (box.minZ + box.maxZ) * 0.5 : (box.minX + box.maxX) * 0.5;
            double lateralSize = outward.getAxis() == Direction.Axis.X
                    ? box.maxZ - box.minZ : box.maxX - box.minX;
            double area = Math.max(1.0E-6, lateralSize * Math.max(1.0E-6, box.maxY - box.minY));
            int key = (int)Math.round(coordinate * 64.0);
            FaceGroup group = groups.computeIfAbsent(key, ignored -> new FaceGroup(coordinate));
            group.area += area;
            group.weightedLateral += lateralCenter * area;
        }
        FaceGroup selected = null;
        for (FaceGroup candidate : groups.values()) {
            if (selected == null || candidate.area > selected.area + 1.0E-6
                    || (Math.abs(candidate.area - selected.area) <= 1.0E-6
                    && fartherOut(candidate.coordinate, selected.coordinate, outward))) {
                selected = candidate;
            }
        }
        if (selected == null || selected.area < 1.0E-8) {
            return new FaceAnchor(baseCenter(pos).add(unit(outward).multiply(0.5)), outward);
        }
        double lateral = selected.weightedLateral / selected.area;
        double x = pos.getX() + (outward.getAxis() == Direction.Axis.Z ? lateral : selected.coordinate);
        double z = pos.getZ() + (outward.getAxis() == Direction.Axis.X ? lateral : selected.coordinate);
        return new FaceAnchor(new Vec3d(x, pos.getY(), z), outward);
    }

    private static boolean fartherOut(double a, double b, Direction outward) {
        return (outward == Direction.EAST || outward == Direction.SOUTH) ? a > b : a < b;
    }

    private static final class FaceGroup {
        final double coordinate;
        double area;
        double weightedLateral;
        FaceGroup(double coordinate) { this.coordinate = coordinate; }
    }

    private static SourceBounds sourceBounds(ServerWorld world, BlockPos pos, BlockState state) {
        List<Box> boxes;
        try {
            VoxelShape shape = state.getCollisionShape(world, pos, ShapeContext.absent());
            if (shape.isEmpty()) shape = state.getOutlineShape(world, pos, ShapeContext.absent());
            boxes = shape.getBoundingBoxes();
        } catch (RuntimeException error) {
            boxes = List.of();
        }
        if (boxes.isEmpty()) return null;
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (Box box : boxes) {
            minX = Math.min(minX, box.minX); maxX = Math.max(maxX, box.maxX);
            minZ = Math.min(minZ, box.minZ); maxZ = Math.max(maxZ, box.maxZ);
        }
        return new SourceBounds(minX, maxX, minZ, maxZ,
                (minZ + maxZ) * 0.5, List.copyOf(boxes));
    }

    private static ConnectedArcBlockEntity.Section localSection(BlockPos holder, Frame a, Frame b) {
        float[] data = new float[18];
        put(data, 0, a.center.subtract(holder.getX(), holder.getY(), holder.getZ()));
        put(data, 3, b.center.subtract(holder.getX(), holder.getY(), holder.getZ()));
        put(data, 6, a.side); put(data, 9, b.side);
        double length = Math.max(1.0E-5, b.center.distanceTo(a.center));
        put(data, 12, a.tangent.multiply(length));
        put(data, 15, b.tangent.multiply(length));
        return new ConnectedArcBlockEntity.Section(data);
    }

    private static void addCollision(List<ConnectedArcBlockEntity.CollisionBox> output,
                                     BlockPos holder, Frame a, Frame b, SourceBounds source) {
        double sourceLength = Math.max(1.0E-5, source.maxX - source.minX);
        for (Box box : source.boxes) {
            int slices = Math.max(1, Math.min(12,
                    (int)Math.ceil((box.maxX - box.minX) * 12.0)));
            for (int slice = 0; slice < slices; slice++) {
                double x0 = box.minX + (box.maxX - box.minX) * slice / slices;
                double x1 = box.minX + (box.maxX - box.minX) * (slice + 1) / slices;
                double t0 = (x0 - source.minX) / sourceLength;
                double t1 = (x1 - source.minX) / sourceLength;
                double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY,
                        minZ = Double.POSITIVE_INFINITY;
                double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY,
                        maxZ = Double.NEGATIVE_INFINITY;
                for (double t : new double[]{t0, t1}) {
                    Frame frame = interpolate(a, b, t);
                    for (double y : new double[]{box.minY, box.maxY}) {
                        for (double z : new double[]{box.minZ, box.maxZ}) {
                            Vec3d point = frame.center.add(frame.side.multiply(z - source.centerZ))
                                    .add(0.0, y, 0.0)
                                    .subtract(holder.getX(), holder.getY(), holder.getZ());
                            minX = Math.min(minX, point.x); minY = Math.min(minY, point.y);
                            minZ = Math.min(minZ, point.z); maxX = Math.max(maxX, point.x);
                            maxY = Math.max(maxY, point.y); maxZ = Math.max(maxZ, point.z);
                        }
                    }
                }
                output.add(new ConnectedArcBlockEntity.CollisionBox(
                        minX, minY, minZ, maxX, maxY, maxZ));
            }
        }
    }

    private static Frame interpolate(Frame a, Frame b, double t) {
        Vec3d center = a.center.lerp(b.center, t);
        Vec3d tangent = horizontalUnit(a.tangent.lerp(b.tangent, t), a.tangent);
        return new Frame(center, tangent, new Vec3d(-tangent.z, 0.0, tangent.x));
    }

    private static BlockPos chooseHolder(ServerWorld world, Vec3d midpoint,
                                         Set<BlockPos> protectedPositions,
                                         Set<BlockPos> alreadyUsed) {
        BlockPos base = BlockPos.ofFloored(midpoint);
        List<BlockPos> candidates = new ArrayList<>();
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            candidates.add(base.add(dx, dy, dz));
        }
        candidates.sort(Comparator.comparingDouble(pos -> Vec3d.ofCenter(pos).squaredDistanceTo(midpoint)));
        for (BlockPos candidate : candidates) {
            if (protectedPositions.contains(candidate)) continue;
            BlockState state = world.getBlockState(candidate);
            if (state.isAir() || state.getBlock() == ConnectedArcMod.CONNECTED_ARC
                    || alreadyUsed.contains(candidate)) return candidate;
        }
        return null;
    }

    private static Direction[] tangentPair(Vec3d delta, int sideMode) {
        double ax = Math.abs(delta.x), az = Math.abs(delta.z);
        Direction xDirection = delta.x >= 0 ? Direction.EAST : Direction.WEST;
        Direction zDirection = delta.z >= 0 ? Direction.SOUTH : Direction.NORTH;
        if (ax < 0.20) return new Direction[]{zDirection, zDirection};
        if (az < 0.20) return new Direction[]{xDirection, xDirection};
        boolean xFirst = sideMode >= 0 ? ax >= az : ax < az;
        return xFirst ? new Direction[]{xDirection, zDirection}
                : new Direction[]{zDirection, xDirection};
    }

    private static Direction snapCardinal(Vec3d vector) {
        return Math.abs(vector.x) >= Math.abs(vector.z)
                ? (vector.x >= 0 ? Direction.EAST : Direction.WEST)
                : (vector.z >= 0 ? Direction.SOUTH : Direction.NORTH);
    }
    private static Vec3d unit(Direction direction) { return new Vec3d(direction.getOffsetX(), 0.0, direction.getOffsetZ()); }
    private static Vec3d baseCenter(BlockPos pos) { return new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5); }
    private static Vec3d horizontalUnit(Vec3d value, Vec3d fallback) {
        Vec3d horizontal = new Vec3d(value.x, 0.0, value.z);
        Vec3d fallbackHorizontal = new Vec3d(fallback.x, 0.0, fallback.z);
        if (horizontal.lengthSquared() < 1.0E-10) return fallbackHorizontal.normalize();
        return horizontal.normalize();
    }
    private static double horizontalDistance(Vec3d a, Vec3d b) { return Math.hypot(a.x - b.x, a.z - b.z); }
    private static boolean sameHorizontalDirection(Vec3d a, Vec3d b) { return a.dotProduct(b) > 0.98; }
    private static void put(float[] data, int offset, Vec3d value) {
        data[offset] = (float)value.x; data[offset + 1] = (float)value.y; data[offset + 2] = (float)value.z;
    }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
