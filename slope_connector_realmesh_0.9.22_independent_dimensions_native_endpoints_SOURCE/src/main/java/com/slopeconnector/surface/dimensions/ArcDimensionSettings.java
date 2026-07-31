package com.slopeconnector.surface.dimensions;

import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adds a second independent arc dimension without modifying the embedded 0.9.10 settings class.
 *
 * <p>The original {@code width} field remains the left/right ribbon width.  This class stores the
 * new up/down thickness beside the exact PlayerSettings object used by the generator, so the
 * private generator can read it through a lightweight Mixin without any global cross-player state.</p>
 */
public final class ArcDimensionSettings {
    public static final int MIN_SIZE = 1;
    public static final int MAX_LEFT_RIGHT = 32;
    public static final int MAX_UP_DOWN = 16;

    private static final Map<UUID, Integer> UP_DOWN_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<Object, Integer> UP_DOWN_BY_SETTINGS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile Method settingsMethod;
    private static volatile Field widthField;

    private ArcDimensionSettings() {}

    public static int clampLeftRight(int value) {
        return Math.max(MIN_SIZE, Math.min(MAX_LEFT_RIGHT, value));
    }

    public static int clampUpDown(int value) {
        return Math.max(MIN_SIZE, Math.min(MAX_UP_DOWN, value));
    }

    public static int setLeftRight(ServerPlayerEntity player, int value) {
        value = clampLeftRight(value);
        Object settings = settingsObject(player);
        if (settings == null) return value;
        try {
            Field field = widthField(settings);
            field.setInt(settings, value);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法设置左右宽度", error);
        }
        return value;
    }

    public static int leftRight(ServerPlayerEntity player) {
        Object settings = settingsObject(player);
        if (settings == null) return 1;
        try {
            return clampLeftRight(widthField(settings).getInt(settings));
        } catch (ReflectiveOperationException error) {
            return 1;
        }
    }

    public static int setUpDown(ServerPlayerEntity player, int value) {
        value = clampUpDown(value);
        Object settings = settingsObject(player);
        UP_DOWN_BY_PLAYER.put(player.getUuid(), value);
        if (settings != null) UP_DOWN_BY_SETTINGS.put(settings, value);
        return value;
    }

    public static int upDown(ServerPlayerEntity player) {
        return UP_DOWN_BY_PLAYER.getOrDefault(player.getUuid(), 1);
    }

    /** Used from the ArcRibbonGenerator Mixin; the argument is the embedded PlayerSettings object. */
    public static int upDownForSettings(Object settings) {
        if (settings == null) return 1;
        return clampUpDown(UP_DOWN_BY_SETTINGS.getOrDefault(settings, 1));
    }

    private static Object settingsObject(ServerPlayerEntity player) {
        try {
            Method method = settingsMethod;
            if (method == null) {
                Class<?> mod = Class.forName("com.slopeconnector.SlopeConnectorMod");
                method = mod.getMethod("settings", ServerPlayerEntity.class);
                method.setAccessible(true);
                settingsMethod = method;
            }
            Object settings = method.invoke(null, player);
            Integer stored = UP_DOWN_BY_PLAYER.get(player.getUuid());
            if (stored != null) UP_DOWN_BY_SETTINGS.put(settings, stored);
            return settings;
        } catch (ReflectiveOperationException error) {
            return null;
        }
    }

    private static Field widthField(Object settings) throws NoSuchFieldException {
        Field field = widthField;
        if (field == null || field.getDeclaringClass() != settings.getClass()) {
            field = settings.getClass().getField("width");
            field.setAccessible(true);
            widthField = field;
        }
        return field;
    }
}
