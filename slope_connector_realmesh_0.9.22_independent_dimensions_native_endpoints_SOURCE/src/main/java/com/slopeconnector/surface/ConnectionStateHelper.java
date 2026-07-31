package com.slopeconnector.surface;

import com.slopeconnector.connected.ConnectedArcMod;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;

/** Shared endpoint state logic for vanilla and property-driven modded connected blocks. */
public final class ConnectionStateHelper {
    private static volatile Method supportedMethod;
    private static final Map<Block, Boolean> SUPPORTED_CACHE = new ConcurrentHashMap<>();

    private ConnectionStateHelper() {}

    public static void forceWorldConnection(ServerWorld world, BlockPos pos, Direction direction) {
        if (direction.getAxis().isVertical()) return;
        BlockState state = world.getBlockState(pos);
        BlockState connected = forceConnection(state, direction);
        if (!connected.equals(state)) world.setBlockState(pos, connected, 3);
    }

    public static BlockState forceConnection(BlockState state, Direction direction) {
        if (state == null || direction.getAxis().isVertical() || !isSupported(state)) return state;
        String name = direction.getName();
        for (Property<?> property : state.getProperties()) {
            if (!property.getName().equalsIgnoreCase(name)) continue;
            return setConnected(state, property);
        }
        return state;
    }

    public static boolean shouldForce(BlockState current, BlockState neighbor) {
        if (!isSupported(current)) return false;
        if (neighbor.getBlock() == ConnectedArcMod.CONNECTED_ARC) return true;
        if (!isSupported(neighbor)) return false;
        String a = family(current);
        String b = family(neighbor);
        return a.equals(b) || current.getBlock() == neighbor.getBlock();
    }

    public static boolean isSupported(BlockState state) {
        if (state == null || state.isAir()) return false;
        Block block = state.getBlock();
        Boolean cached = SUPPORTED_CACHE.get(block);
        if (cached != null) return cached;
        boolean result;
        if (block instanceof FenceBlock || block instanceof PaneBlock || block instanceof WallBlock) {
            result = true;
        } else {
            String id = Registries.BLOCK.getId(block).toString().toLowerCase(Locale.ROOT);
            // Avoid reflective classifier calls for the overwhelming majority of ordinary blocks.
            if (!containsAny(id, "fence", "railing", "balustrade", "baluster", "bars", "pane", "wall")) {
                result = false;
            } else {
                try {
                    Method method = supportedMethod;
                    if (method == null) {
                        Class<?> type = Class.forName("com.slopeconnector.connected.ConnectedBlockClassifier");
                        method = type.getDeclaredMethod("isSupported", BlockState.class);
                        method.setAccessible(true);
                        supportedMethod = method;
                    }
                    result = (boolean) method.invoke(null, state);
                } catch (ReflectiveOperationException ignored) {
                    result = true;
                }
            }
        }
        SUPPORTED_CACHE.put(block, result);
        return result;
    }

    private static String family(BlockState state) {
        String id = Registries.BLOCK.getId(state.getBlock()).toString().toLowerCase(Locale.ROOT);
        if (state.getBlock() instanceof FenceBlock || containsAny(id, "fence", "railing")) return "fence";
        if (containsAny(id, "balustrade", "baluster")) return "balustrade";
        if (state.getBlock() instanceof PaneBlock || containsAny(id, "pane", "bars")) return "pane";
        if (state.getBlock() instanceof WallBlock || id.contains("wall")) return "wall";
        return id;
    }

    private static boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }

    private static BlockState setConnected(BlockState state, Property<?> property) {
        if (property instanceof BooleanProperty booleanProperty) {
            return state.with(booleanProperty, true);
        }
        for (String candidate : new String[]{"low", "side", "tall", "connected", "true", "wall"}) {
            BlockState parsed = setParsed(state, property, candidate);
            if (parsed != null) return parsed;
        }
        for (Comparable<?> value : property.getValues()) {
            String named = propertyValueName(property, value).toLowerCase(Locale.ROOT);
            if (!named.equals("none") && !named.equals("false") && !named.equals("empty")) {
                return withRaw(state, property, value);
            }
        }
        return state;
    }

    private static BlockState setParsed(BlockState state, Property<?> property, String value) {
        Optional<?> parsed = property.parse(value);
        if (parsed.isEmpty()) return null;
        return withRaw(state, property, (Comparable<?>) parsed.get());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState withRaw(BlockState state, Property property, Comparable value) {
        return property.getValues().contains(value) ? state.with(property, value) : state;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyValueName(Property property, Comparable value) {
        return property.name(value);
    }
}
