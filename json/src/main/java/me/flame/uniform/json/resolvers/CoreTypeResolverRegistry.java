package me.flame.uniform.json.resolvers;

import me.flame.uniform.json.dom.JsonBoolean;
import me.flame.uniform.json.dom.JsonNull;
import me.flame.uniform.json.dom.JsonNumber;
import me.flame.uniform.json.dom.JsonString;
import me.flame.uniform.json.dom.JsonValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of {@link CoreTypeResolver} instances used by
 * {@code JsonAdapter.treeToValue(JsonValue, Class)} to convert DOM nodes to
 * Java types without going through the mapper/codegen registry.
 *
 * <h3>Built-in resolvers</h3>
 * Registered automatically at construction time:
 * <ul>
 *   <li>All primitives and their wrappers</li>
 *   <li>{@link String}</li>
 *   <li>{@link BigInteger}, {@link BigDecimal}</li>
 *   <li>{@link UUID}</li>
 *   <li>{@link URI}, {@link URL}</li>
 *   <li>{@link Path}</li>
 *   <li>Java Time: {@link LocalDate}, {@link LocalTime}, {@link LocalDateTime},
 *       {@link ZonedDateTime}, {@link OffsetDateTime}, {@link Instant},
 *       {@link Duration}, {@link Period}</li>
 * </ul>
 *
 * <h3>Custom resolvers</h3>
 * Call {@link #register(CoreTypeResolver)} before the first {@code treeToValue} call.
 * Custom resolvers override built-ins for the same type.
 */
public final class CoreTypeResolverRegistry {

    /** Singleton — shared across all {@code JsonAdapter} instances. */
    public static final CoreTypeResolverRegistry INSTANCE = new CoreTypeResolverRegistry();

    @SuppressWarnings("rawtypes")
    private static final CoreTypeResolver NULL_MARKER = new CoreTypeResolver<>() {
        @Override public @NotNull Class<Object> getType() { return Object.class; }
        @Override public @Nullable Object resolve(@NotNull JsonValue value) { return null; }
    };

    private final Map<Class<?>, CoreTypeResolver<?>> resolvers        = new ConcurrentHashMap<>(32);
    private final Map<Class<?>, CoreTypeResolver<?>> assignableCache  = new ConcurrentHashMap<>(16);

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    private CoreTypeResolverRegistry() {
        registerDefaults();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Registers a custom resolver, replacing any existing one for the same type.
     * Clears the assignable cache so subtype lookups are re-evaluated.
     */
    public <T> void register(@NotNull CoreTypeResolver<T> resolver) {
        resolvers.put(resolver.getType(), resolver);
        assignableCache.clear();
    }

    /**
     * Looks up the resolver for {@code type}, checking the exact type first,
     * then walking registered resolvers for an assignable match (e.g. an enum
     * resolver registered for {@code Enum.class} will match any concrete enum).
     *
     * @return the resolver, or {@code null} if none is registered
     */
    @SuppressWarnings("unchecked")
    public <T> @Nullable CoreTypeResolver<T> resolve(@NotNull Class<T> type) {
        // 1. Direct hit
        CoreTypeResolver<?> direct = resolvers.get(type);
        if (direct != null && direct != NULL_MARKER) return (CoreTypeResolver<T>) direct;
        if (direct == NULL_MARKER) return null;

        // 2. Assignable cache
        CoreTypeResolver<?> cached = assignableCache.get(type);
        if (cached != null && cached != NULL_MARKER) return (CoreTypeResolver<T>) cached;
        if (cached == NULL_MARKER) return null;

        // 3. Enum shortcut
        if (type.isEnum()) {
            CoreTypeResolver<T> enumResolver = enumResolver(type);
            resolvers.put(type, enumResolver);
            return enumResolver;
        }

        // 4. Assignable scan
        for (Map.Entry<Class<?>, CoreTypeResolver<?>> entry : resolvers.entrySet()) {
            if (entry.getKey().isAssignableFrom(type)) {
                assignableCache.put(type, entry.getValue());
                return (CoreTypeResolver<T>) entry.getValue();
            }
        }

        // 5. Negative cache
        assignableCache.put(type, NULL_MARKER);
        return null;
    }

    public boolean has(@NotNull Class<?> type) {
        return resolve(type) != null;
    }

    // -------------------------------------------------------------------------
    // Default registrations
    // -------------------------------------------------------------------------

    private void registerDefaults() {
        // Primitives & String
        put(String.class, CoreTypeResolverRegistry::coerceToString);
        put(int.class, CoreTypeResolverRegistry::coerceToInt);
        put(Integer.class, CoreTypeResolverRegistry::coerceToInt);
        put(long.class, CoreTypeResolverRegistry::coerceToLong);
        put(Long.class, CoreTypeResolverRegistry::coerceToLong);
        put(double.class, CoreTypeResolverRegistry::coerceToDouble);
        put(Double.class, CoreTypeResolverRegistry::coerceToDouble);
        put(float.class, CoreTypeResolverRegistry::coerceToFloat);
        put(Float.class, CoreTypeResolverRegistry::coerceToFloat);
        put(short.class, CoreTypeResolverRegistry::coerceToShort);
        put(Short.class, CoreTypeResolverRegistry::coerceToShort);
        put(byte.class, CoreTypeResolverRegistry::coerceToByte);
        put(Byte.class, CoreTypeResolverRegistry::coerceToByte);
        put(boolean.class, CoreTypeResolverRegistry::coerceToBool);
        put(Boolean.class, CoreTypeResolverRegistry::coerceToBool);
        put(char.class, CoreTypeResolverRegistry::coerceToChar);
        put(Character.class, CoreTypeResolverRegistry::coerceToChar);

        // Big numbers
        put(BigInteger.class, v -> {
            if (v instanceof JsonNull) return null;
            if (v instanceof JsonNumber n) return BigInteger.valueOf(n.longValue());
            if (v instanceof JsonString s) return new BigInteger(s.value());
            throw bad(v, BigInteger.class);
        });
        put(BigDecimal.class, v -> {
            if (v instanceof JsonNull) return null;
            if (v instanceof JsonNumber n) return BigDecimal.valueOf(n.doubleValue());
            if (v instanceof JsonString s) return new BigDecimal(s.value());
            throw bad(v, BigDecimal.class);
        });

        // UUID
        put(UUID.class, v -> {
            if (v instanceof JsonNull) return null;
            if (v instanceof JsonString s) return UUID.fromString(s.value());
            throw bad(v, UUID.class);
        });

        // Network
        put(URI.class, v -> {
            if (v instanceof JsonNull) return null;
            if (v instanceof JsonString s) return URI.create(s.value());
            throw bad(v, URI.class);
        });
        put(URL.class, v -> {
            if (v instanceof JsonNull) return null;
            if (v instanceof JsonString s) { try { return URI.create(s.value()).toURL(); } catch (Exception e) { throw new RuntimeException(e); } }
            throw bad(v, URL.class);
        });

        // File system
        put(Path.class, v -> {
            if (v instanceof JsonNull) return null;
            if (v instanceof JsonString s) return Path.of(s.value());
            throw bad(v, Path.class);
        });

        // Java Time
        put(LocalDate.class,      v -> v instanceof JsonNull ? null : LocalDate.parse(requireString(v, LocalDate.class)));
        put(LocalTime.class,      v -> v instanceof JsonNull ? null : LocalTime.parse(requireString(v, LocalTime.class)));
        put(LocalDateTime.class,  v -> v instanceof JsonNull ? null : LocalDateTime.parse(requireString(v, LocalDateTime.class)));
        put(ZonedDateTime.class,  v -> v instanceof JsonNull ? null : ZonedDateTime.parse(requireString(v, ZonedDateTime.class)));
        put(OffsetDateTime.class, v -> v instanceof JsonNull ? null : OffsetDateTime.parse(requireString(v, OffsetDateTime.class)));
        put(Instant.class,        v -> {
            if (v instanceof JsonNull) return null;
            if (v instanceof JsonNumber n) return Instant.ofEpochMilli(n.longValue());
            if (v instanceof JsonString s) return Instant.parse(s.value());
            throw bad(v, Instant.class);
        });
        put(Duration.class, v -> {
            if (v instanceof JsonNull) return null;
            if (v instanceof JsonNumber n) return Duration.ofMillis(n.longValue());
            if (v instanceof JsonString s) return Duration.parse(s.value());
            throw bad(v, Duration.class);
        });
        put(Period.class, v -> {
            if (v instanceof JsonNull) return null;
            if (v instanceof JsonString s) return Period.parse(s.value());
            throw bad(v, Period.class);
        });
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Shorthand for anonymous inline resolver registration. */
    private <T> void put(@NotNull Class<T> type, @NotNull CoreTypeResolver<T> resolver) {
        resolvers.put(type, resolver);
    }

    /** Creates an anonymous {@link CoreTypeResolver} from a lambda, binding the type. */
    private <T> void put(@NotNull Class<T> type, @NotNull java.util.function.Function<JsonValue, T> fn) {
        resolvers.put(type, new CoreTypeResolver<T>() {
            @Override public @NotNull Class<T> getType() { return type; }
            @Override public @Nullable T resolve(@NotNull JsonValue value) { return fn.apply(value); }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> @NotNull CoreTypeResolver<T> enumResolver(@NotNull Class<T> type) {
        return new CoreTypeResolver<>() {
            @Override
            public @NotNull Class<T> getType() {
                return type;
            }

            @Override
            public @Nullable T resolve(@NotNull JsonValue value) {
                if (value instanceof JsonNull) return null;
                String name = requireString(value, type);
                for (T constant : type.getEnumConstants()) {
                    if (((Enum<?>) constant).name().equals(name)) return constant;
                }
                throw new IllegalArgumentException("No enum constant " + type.getName() + "." + name);
            }
        };
    }

    public static int coerceToInt(@NotNull JsonValue v) {
        if (v instanceof JsonNumber n) {
            return n.intValue();
        } else if (v instanceof JsonString s) {
            return Integer.parseInt(s.value());
        } else if (v instanceof JsonBoolean b) {
            return b.value() ? 1 : 0;
        }
        return 0;
    }

    public static short coerceToShort(@NotNull JsonValue v) {
        if (v instanceof JsonNumber n) {
            return n.shortValue();
        } else if (v instanceof JsonString s) {
            return Short.parseShort(s.value());
        } else if (v instanceof JsonBoolean b) {
            return (short) (b.value() ? 1 : 0);
        }
        return 0;
    }

    public static byte coerceToByte(@NotNull JsonValue v) {
        if (v instanceof JsonNumber n) {
            return n.byteValue();
        } else if (v instanceof JsonString s) {
            return Byte.parseByte(s.value());
        } else if (v instanceof JsonBoolean b) {
            return (byte) (b.value() ? 1 : 0);
        }
        return 0;
    }

    public static long coerceToLong(@NotNull JsonValue v) {
        if (v instanceof JsonNumber n) {
            return n.longValue();
        } else if (v instanceof JsonString s) {
            return Long.parseLong(s.value());
        } else if (v instanceof JsonBoolean b) {
            return b.value() ? 1L : 0L;
        }
        return 0L;
    }

    public static double coerceToDouble(@NotNull JsonValue v) {
        if (v instanceof JsonNumber n) {
            return n.doubleValue();
        } else if (v instanceof JsonString s) {
            return Double.parseDouble(s.value());
        } else if (v instanceof JsonBoolean b) {
            return b.value() ? 1.0 : 0.0;
        }
        return 0.0;
    }

    public static float coerceToFloat(@NotNull JsonValue v) {
        if (v instanceof JsonNumber n) {
            return n.floatValue();
        } else if (v instanceof JsonString s) {
            return Float.parseFloat(s.value());
        } else if (v instanceof JsonBoolean b) {
            return b.value() ? 1.0f : 0.0f;
        }
        return 0.0f;
    }

    public static boolean coerceToBool(@NotNull JsonValue v) {
        if (v instanceof JsonBoolean b) {
            return b.value();
        } else if (v instanceof JsonNumber n) {
            return n.intValue() != 0;
        } else if (v instanceof JsonString s) {
            return Boolean.parseBoolean(s.value());
        }
        return false;
    }

    public static @NotNull String coerceToString(@NotNull JsonValue v) {
        if (v instanceof JsonString s) {
            return s.value();
        } else if (v instanceof JsonNull) {
            return "";
        }
        return v.toString();
    }

    static char coerceToChar(@NotNull JsonValue v) {
        if (v instanceof JsonString s && !s.value().isEmpty()) return s.value().charAt(0);
        if (v instanceof JsonNumber n) return (char) n.intValue();
        return '\0';
    }

    private static @NotNull String requireString(@NotNull JsonValue v, @NotNull Class<?> target) {
        if (v instanceof JsonString s) return s.value();
        throw bad(v, target);
    }

    private static @NotNull IllegalArgumentException bad(@NotNull JsonValue v, @NotNull Class<?> target) {
        return new IllegalArgumentException(
            "Cannot convert " + v.getClass().getSimpleName() + " to " + target.getName());
    }
}