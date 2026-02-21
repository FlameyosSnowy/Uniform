package me.flame.uniform.json.mappers;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

public final class JsonMapperRegistry {
    private static final Map<Class<?>, JsonMapper<?>> READERS = new ConcurrentHashMap<>(64);
    private static final Map<Class<?>, JsonWriterMapper<?>> WRITERS = new ConcurrentHashMap<>(64);

    private static volatile boolean bootstrapped = false;

    private JsonMapperRegistry() {
    }

    private static void bootstrapIfNeeded() {
        if (bootstrapped) return;
        synchronized (JsonMapperRegistry.class) {
            if (bootstrapped) return;
            JsonMapperRegistry registry = new JsonMapperRegistry();

            boolean loadedAny = false;
            for (JsonMapperModule module : ServiceLoader.load(JsonMapperModule.class)) {
                module.register(registry);
                loadedAny = true;
            }

            if (!loadedAny) {
                // ServiceLoader can be unreliable in some build layouts (notably JMH + incremental builds)
                // when META-INF/services resources collide across source sets.
                // Fall back to reflectively loading the known generated module names.
                String override = System.getProperty("uniform.generatedModule");
                if (override != null && !override.isBlank()) {
                    tryRegisterByName(registry, "me.flame.uniform.generated." + override.trim());
                }
                tryRegisterByName(registry, "me.flame.uniform.generated.UniformGeneratedJsonModule");
                tryRegisterByName(registry, "me.flame.uniform.generated.UniformGeneratedJsonModuleJmh");
            }

            bootstrapped = true;
        }
    }

    private static void tryRegisterByName(JsonMapperRegistry registry, String fqcn) {
        try {
            Class<?> clazz = Class.forName(fqcn);
            if (!JsonMapperModule.class.isAssignableFrom(clazz)) return;
            JsonMapperModule module = (JsonMapperModule) clazz.getDeclaredConstructor().newInstance();
            module.register(registry);
        } catch (Throwable ignored) {
            // ignored
        }
    }

    public <T> void registerReaderInstance(Class<T> type, JsonMapper<? extends T> mapper) {
        registerReader(type, mapper);
    }

    public <T> void registerWriterInstance(Class<T> type, JsonWriterMapper<? extends T> mapper) {
        registerWriter(type, mapper);
    }

    public static <T> void registerReader(Class<T> type, JsonMapper<? extends T> mapper) {
        READERS.put(type, mapper);
    }

    public static <T> void registerWriter(Class<T> type, JsonWriterMapper<? extends T> mapper) {
        WRITERS.put(type, mapper);
    }

    public static JsonMapper<?> getReader(Class<?> type) {
        bootstrapIfNeeded();
        JsonMapper<?> mapper = READERS.get(type);
        if (mapper != null) return mapper;

        // If ServiceLoader loaded only a subset of modules (e.g. main/test) we may be missing JMH mappers.
        // Try loading the generated module specified by system property and known defaults.
        tryLoadMissingGeneratedModules();
        return READERS.get(type);
    }

    public static JsonWriterMapper<?> getWriter(Class<?> type) {
        bootstrapIfNeeded();
        JsonWriterMapper<?> mapper = WRITERS.get(type);
        if (mapper != null) return mapper;

        tryLoadMissingGeneratedModules();
        return WRITERS.get(type);
    }

    private static void tryLoadMissingGeneratedModules() {
        JsonMapperRegistry registry = new JsonMapperRegistry();

        String override = System.getProperty("uniform.generatedModule");
        if (override != null && !override.isBlank()) {
            tryRegisterByName(registry, "me.flame.uniform.generated." + override.trim());
        }

        tryRegisterByName(registry, "me.flame.uniform.generated.UniformGeneratedJsonModule");
        tryRegisterByName(registry, "me.flame.uniform.generated.UniformGeneratedJsonModuleJmh");
    }
}
