package io.github.flameyossnowy.uniform.json.mappers;

import io.github.flameyossnowy.uniform.json.JsonConfig;
import io.github.flameyossnowy.uniform.json.ReflectionConfig;
import io.github.flameyossnowy.uniform.json.reflect.ReflectionMapperFactory;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

public final class JsonMapperRegistry {
    private static final Map<Class<?>, JsonMapper<?>>       READERS = new ConcurrentHashMap<>(64);
    private static final Map<Class<?>, JsonWriterMapper<?>> WRITERS = new ConcurrentHashMap<>(64);

    private static volatile boolean    bootstrapped   = false;
    private static volatile JsonConfig activeConfig   = null;

    private JsonMapperRegistry() {}

    public static JsonConfig getActiveConfig() {
        return activeConfig;
    }

    private static void bootstrapIfNeeded() {
        if (bootstrapped) return;
        // Caller must hold lock on JsonMapperRegistry.class
        JsonMapperRegistry registry = new JsonMapperRegistry();
        JsonConfig cfg = activeConfig; // may be null if bootstrapped without config

        boolean loadedAny = false;
        for (JsonMapperModule module : ServiceLoader.load(JsonMapperModule.class)) {
            if (cfg != null) {
                module.register(registry, cfg);
            } else {
                module.register(registry);
            }
            loadedAny = true;
        }

        if (!loadedAny) {
            String override = System.getProperty("uniform.generatedModule");
            if (override != null && !override.isBlank()) {
                tryRegisterByName(registry, "io.github.flameyossnowy.uniform.generated." + override.trim(), cfg);
            }
            tryRegisterByName(registry, "io.github.flameyossnowy.uniform.generated.UniformGeneratedJsonModule", cfg);
            tryRegisterByName(registry, "io.github.flameyossnowy.uniform.generated.UniformGeneratedJsonModuleJmh", cfg);
        }

        bootstrapped = true;
    }

    private static void propagateConfigToModules(JsonMapperRegistry registry, JsonConfig config) {
        for (JsonMapperModule module : ServiceLoader.load(JsonMapperModule.class)) {
            module.register(registry, config);
        }
        String override = System.getProperty("uniform.generatedModule");
        if (override != null && !override.isBlank()) {
            tryRegisterByName(registry, "io.github.flameyossnowy.uniform.generated." + override.trim(), config);
        }
        tryRegisterByName(registry, "io.github.flameyossnowy.uniform.generated.UniformGeneratedJsonModule", config);
        tryRegisterByName(registry, "io.github.flameyossnowy.uniform.generated.UniformGeneratedJsonModuleJmh", config);
    }

    private static void tryRegisterByName(JsonMapperRegistry registry, String fqcn, JsonConfig config) {
        try {
            Class<?> clazz = Class.forName(fqcn);
            if (!JsonMapperModule.class.isAssignableFrom(clazz)) return;
            JsonMapperModule module = (JsonMapperModule) clazz.getDeclaredConstructor().newInstance();
            if (config != null) {
                module.register(registry, config);
            } else {
                module.register(registry);
            }
        } catch (Throwable ignored) {}
    }

    private static void tryRegisterByName(JsonMapperRegistry registry, String fqcn) {
        tryRegisterByName(registry, fqcn, activeConfig);
    }

    public <T> void registerReaderInstance(Class<T> type, JsonMapper<? extends T> mapper) {
        READERS.put(type, mapper);
    }

    public <T> void registerWriterInstance(Class<T> type, JsonWriterMapper<? extends T> mapper) {
        WRITERS.put(type, mapper);
    }

    public static <T> void registerReader(Class<T> type, JsonMapper<? extends T> mapper) {
        READERS.put(type, mapper);
    }

    public static <T> void registerWriter(Class<T> type, JsonWriterMapper<? extends T> mapper) {
        WRITERS.put(type, mapper);
    }

    private static volatile ReflectionConfig reflectionConfig = ReflectionConfig.DEFAULT;

    public static void applyConfig(JsonConfig config) {
        if (config == null) return;
        synchronized (JsonMapperRegistry.class) {
            activeConfig      = config;
            reflectionConfig  = config.reflectionConfig();
            bootstrapIfNeeded();
            propagateConfigToModules(new JsonMapperRegistry(), config);
        }
    }

    public static JsonMapper<?> getReader(Class<?> type) {
        bootstrapIfNeededUnsafe();
        JsonMapper<?> mapper = READERS.get(type);
        if (mapper != null) return mapper;
        tryLoadMissingGeneratedModules();
        mapper = READERS.get(type);
        if (mapper != null) return mapper;

        // Reflection fallback
        if (reflectionConfig.enabled()) {
            return ReflectionMapperFactory.buildReader(type);
        }
        return null;
    }

    public static JsonWriterMapper<?> getWriter(Class<?> type) {
        bootstrapIfNeededUnsafe();
        JsonWriterMapper<?> mapper = WRITERS.get(type);
        if (mapper != null) return mapper;
        tryLoadMissingGeneratedModules();
        mapper = WRITERS.get(type);
        if (mapper != null) return mapper;

        if (reflectionConfig.enabled()) {
            return ReflectionMapperFactory.buildWriter(type);
        }
        return null;
    }

    /** Fast unsynchronized check for the read/write hot path. */
    private static void bootstrapIfNeededUnsafe() {
        if (bootstrapped) return;
        synchronized (JsonMapperRegistry.class) {
            bootstrapIfNeeded();
        }
    }

    private static void tryLoadMissingGeneratedModules() {
        JsonMapperRegistry registry = new JsonMapperRegistry();
        JsonConfig cfg = activeConfig;
        String override = System.getProperty("uniform.generatedModule");
        if (override != null && !override.isBlank()) {
            tryRegisterByName(registry, "io.github.flameyossnowy.uniform.generated." + override.trim(), cfg);
        }
        tryRegisterByName(registry, "io.github.flameyossnowy.uniform.generated.UniformGeneratedJsonModule", cfg);
        tryRegisterByName(registry, "io.github.flameyossnowy.uniform.generated.UniformGeneratedJsonModuleJmh", cfg);
    }
}