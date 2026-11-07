/*
 * GNU General Public License v3
 *
 * PaperTweaks, a performant replacement for the VanillaTweaks datapacks.
 *
 * Copyright (C) 2022-2026 Machine_Maker
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package me.machinemaker.papertweaks.modules.utilities.spawningspheres;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.Callables;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import me.machinemaker.mirror.Mirror;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.checkerframework.checker.nullness.qual.Nullable;

class PaperDespawnDistances implements DespawnDistances {

    private static final Function<World, Callable<Integer>> HARD_DESPAWN_DISTANCE_FUNCTION;

    static {
        Function<World, Callable<Integer>> hardDespawnDistanceFunction = world -> Callables.returning(128);
        try {
            final @Nullable Class<?> paperWorldConfigClass = Mirror.maybeGetClass("io.papermc.paper.configuration.WorldConfiguration");
            if (paperWorldConfigClass != null) {
                hardDespawnDistanceFunction = new PerCategoryDespawnDistances(paperWorldConfigClass);
            } else {
                throw new IllegalArgumentException("Old version");
            }
        } catch (final IllegalArgumentException exception) {
            SpawningSpheres.LOGGER.warn("Paper environment detected, but could not hook into any custom spawning ranges. This might be a bug", exception);
        }
        HARD_DESPAWN_DISTANCE_FUNCTION = hardDespawnDistanceFunction;
    }

    private final Cache<NamespacedKey, Integer> hardDespawnCache;

    PaperDespawnDistances() {
        this.hardDespawnCache = CacheBuilder.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();
    }

    @Override
    public int soft(final World world) {
        return 24;
    }

    @Override
    public int hard(final World world) {
        try {
            return this.hardDespawnCache.get(world.getKey(), HARD_DESPAWN_DISTANCE_FUNCTION.apply(world));
        } catch (final ExecutionException e) {
            throw new RuntimeException("Error getting hard despawn distance for " + world.getKey(), e);
        }
    }

    // WorldConfiguration.java per-category despawn distances, read from the
    // world's live WorldConfiguration instead of the deprecated
    // Server.Spigot#getPaperConfig() YAML view. The value read is
    // entities.spawning.despawn-ranges.monster.hard, resolved the same way
    // the server resolves it (a configured value, or 128 when it is "default").
    static class PerCategoryDespawnDistances implements Function<World, Callable<Integer>> {

        private final Class<?> worldConfigurationClass;

        PerCategoryDespawnDistances(final Class<?> worldConfigurationClass) {
            this.worldConfigurationClass = worldConfigurationClass;
        }

        @Override
        public Callable<Integer> apply(final World world) {
            return () -> readMonsterHardDespawnDistance(world);
        }

        private int readMonsterHardDespawnDistance(final World world) {
            try {
                // CraftWorld#getHandle() -> ServerLevel, which holds the
                // world's WorldConfiguration in a field
                final Object handle = world.getClass().getMethod("getHandle").invoke(world);
                final Object worldConfiguration = findFieldOfType(handle, this.worldConfigurationClass);
                if (worldConfiguration == null) {
                    return 128;
                }
                final Object spawning = getField(getField(worldConfiguration, "entities"), "spawning");
                @SuppressWarnings("unchecked")
                final Map<Object, Object> despawnRanges = (Map<Object, Object>) getField(spawning, "despawnRanges");
                final Class<?> mobCategoryClass = Class.forName("net.minecraft.world.entity.MobCategory");
                @SuppressWarnings({"unchecked", "rawtypes"})
                final Object monster = Enum.valueOf((Class<Enum>) mobCategoryClass, "MONSTER");
                final Object pair = despawnRanges.get(monster);
                if (pair == null) {
                    return 128;
                }
                // DespawnRangePair is a record: hard() -> DespawnRange, whose
                // horizontalLimit is an IntOr$Default record holding an
                // OptionalInt (empty when the config value is "default")
                final Object hard = pair.getClass().getMethod("hard").invoke(pair);
                final Field horizontalLimitField = hard.getClass().getDeclaredField("horizontalLimit");
                horizontalLimitField.setAccessible(true);
                final Object horizontalLimit = horizontalLimitField.get(hard);
                final OptionalInt value = (OptionalInt) horizontalLimit.getClass().getMethod("value").invoke(horizontalLimit);
                return value.orElse(128);
            } catch (final ReflectiveOperationException | ClassCastException exception) {
                SpawningSpheres.LOGGER.warn("Could not read the monster hard despawn distance for " + world.getKey() + ", falling back to 128", exception);
                return 128;
            }
        }

        private static @Nullable Object findFieldOfType(final Object object, final Class<?> type) throws IllegalAccessException {
            Class<?> clazz = object.getClass();
            while (clazz != null) {
                for (final Field field : clazz.getDeclaredFields()) {
                    if (type.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        final Object value = field.get(object);
                        if (value != null) {
                            return value;
                        }
                    }
                }
                clazz = clazz.getSuperclass();
            }
            return null;
        }

        private static Object getField(final Object object, final String name) throws ReflectiveOperationException {
            return object.getClass().getField(name).get(object);
        }
    }
}
