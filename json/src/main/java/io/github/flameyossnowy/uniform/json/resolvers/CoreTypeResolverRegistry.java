package io.github.flameyossnowy.uniform.json.resolvers;

import io.github.flameyossnowy.uniform.json.dom.JsonArray;
import io.github.flameyossnowy.uniform.json.dom.JsonBoolean;
import io.github.flameyossnowy.uniform.json.dom.JsonByte;
import io.github.flameyossnowy.uniform.json.dom.JsonDouble;
import io.github.flameyossnowy.uniform.json.dom.JsonFloat;
import io.github.flameyossnowy.uniform.json.dom.JsonInteger;
import io.github.flameyossnowy.uniform.json.dom.JsonLong;
import io.github.flameyossnowy.uniform.json.dom.JsonNull;
import io.github.flameyossnowy.uniform.json.dom.JsonNumber;
import io.github.flameyossnowy.uniform.json.dom.JsonObject;
import io.github.flameyossnowy.uniform.json.dom.JsonShort;
import io.github.flameyossnowy.uniform.json.dom.JsonString;
import io.github.flameyossnowy.uniform.json.dom.JsonValue;
import io.github.flameyossnowy.uniform.json.exceptions.JsonCoercionException;
import org.jetbrains.annotations.Contract;
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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Registry of {@link CoreTypeResolver} instances — the single source of truth for
 * all {@link JsonValue} ↔ Java type conversions outside generated POJO mappers.
 *
 * <h3>Built-in resolvers</h3>
 * <ul>
 *   <li>All primitives and their wrappers (including {@code char}/{@code Character})</li>
 *   <li>{@link String}</li>
 *   <li>{@link BigInteger}, {@link BigDecimal}</li>
 *   <li>{@link UUID}</li>
 *   <li>{@link URI}, {@link URL}</li>
 *   <li>{@link Path}</li>
 *   <li>Java Time: {@link LocalDate}, {@link LocalTime}, {@link LocalDateTime},
 *       {@link ZonedDateTime}, {@link OffsetDateTime}, {@link Instant},
 *       {@link Duration}, {@link Period}</li>
 *   <li>Any {@link Enum} subtype (by name, via assignable scan)</li>
 * </ul>
 *
 * <h3>Custom resolvers</h3>
 * <pre>{@code
 * CoreTypeResolverRegistry.INSTANCE.register(new MyTypeResolver());
 * }</pre>
 * Custom resolvers override built-ins for the same type. Call before first use.
 */
public final class CoreTypeResolverRegistry {

    /** Singleton — shared across all {@code JsonAdapter} instances. */
    public static final CoreTypeResolverRegistry INSTANCE = new CoreTypeResolverRegistry();

    @SuppressWarnings("rawtypes")
    private static final CoreTypeResolver NULL_MARKER = new CoreTypeResolver<>() {
        @Override public @NotNull Class<Object> getType()                      { return Object.class; }
        @Override public @Nullable Object deserialize(@NotNull JsonValue value) { return null; }
        @Override public @NotNull JsonValue serialize(@NotNull Object value)    { return JsonNull.INSTANCE; }
    };

    private final Map<Class<?>, CoreTypeResolver<?>> resolvers       = new ConcurrentHashMap<>(32);
    private final Map<Class<?>, CoreTypeResolver<?>> assignableCache = new ConcurrentHashMap<>(16);

    private CoreTypeResolverRegistry() {
        registerDefaults();
    }

    /**
     * Registers a custom {@link CoreTypeResolver}, replacing any existing one for
     * the same type. Clears the assignable cache so subtype lookups re-evaluate.
     */
    public <T> void register(@NotNull CoreTypeResolver<T> resolver) {
        resolvers.put(resolver.getType(), resolver);
        assignableCache.clear();
    }

    /**
     * Looks up the resolver for {@code type}.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Exact type match</li>
     *   <li>Assignable cache (previously resolved supertypes)</li>
     *   <li>Enum shortcut (any {@link Enum} subtype)</li>
     *   <li>Assignable scan across all registered resolvers</li>
     *   <li>Negative cache (returns {@code null}, avoids re-scanning)</li>
     * </ol>
     *
     * @return the resolver, or {@code null} if none is registered
     */
    @SuppressWarnings("unchecked")
    public <T> @Nullable CoreTypeResolver<T> resolve(@NotNull Class<T> type) {
        CoreTypeResolver<?> direct = resolvers.get(type);
        if (direct == NULL_MARKER) return null;
        if (direct != null)        return (CoreTypeResolver<T>) direct;

        CoreTypeResolver<?> cached = assignableCache.get(type);
        if (cached == NULL_MARKER) return null;
        if (cached != null)        return (CoreTypeResolver<T>) cached;

        if (type.isEnum()) {
            CoreTypeResolver<T> enumResolver = buildEnumResolver(type);
            resolvers.put(type, enumResolver);
            return enumResolver;
        }

        for (Map.Entry<Class<?>, CoreTypeResolver<?>> entry : resolvers.entrySet()) {
            if (entry.getKey().isAssignableFrom(type)) {
                CoreTypeResolver<?> found = entry.getValue();
                assignableCache.put(type, found);
                return (CoreTypeResolver<T>) found;
            }
        }

        assignableCache.put(type, NULL_MARKER);
        return null;
    }

    /** Returns {@code true} if a resolver is registered for {@code type}. */
    public boolean has(@NotNull Class<?> type) {
        return resolve(type) != null;
    }

    private void registerDefaults() {
        register(String.class,
            v -> v instanceof JsonString(String value) ? value : v instanceof JsonNull ? null : v.toString(),
            JsonString::new);

        register(int.class, CoreTypeResolverRegistry::coerceInt, JsonInteger::new);
        register(Integer.class, CoreTypeResolverRegistry::coerceInt, JsonInteger::new);
        register(long.class, CoreTypeResolverRegistry::coerceLong, JsonLong::new);
        register(Long.class, CoreTypeResolverRegistry::coerceLong, JsonLong::new);
        register(double.class, CoreTypeResolverRegistry::coerceDouble, JsonDouble::new);
        register(Double.class, CoreTypeResolverRegistry::coerceDouble, JsonDouble::new);
        register(float.class, CoreTypeResolverRegistry::coerceFloat, JsonFloat::new);
        register(Float.class, CoreTypeResolverRegistry::coerceFloat, JsonFloat::new);
        register(short.class, CoreTypeResolverRegistry::coerceShort, JsonShort::new);
        register(Short.class, CoreTypeResolverRegistry::coerceShort, JsonShort::new);
        register(byte.class, CoreTypeResolverRegistry::coerceByte, JsonByte::new);
        register(Byte.class, CoreTypeResolverRegistry::coerceByte, JsonByte::new);
        register(boolean.class, CoreTypeResolverRegistry::coerceBool,   JsonBoolean::of);
        register(Boolean.class, CoreTypeResolverRegistry::coerceBool,   JsonBoolean::of);
        register(char.class, CoreTypeResolverRegistry::coerceChar, v -> new JsonString(String.valueOf(v)));
        register(Character.class, CoreTypeResolverRegistry::coerceChar, v -> new JsonString(String.valueOf(v)));

        register(BigInteger.class,
            v -> {
                Objects.requireNonNull(v);
                return switch (v) {
                    case JsonNull _ -> null;
                    case JsonNumber n -> BigInteger.valueOf(n.longValue());
                    case JsonString s -> new BigInteger(s.value());
                    default -> bad(v, BigInteger.class);
                };
            },
            v -> new JsonString(v.toString()));

        register(BigDecimal.class,
            v -> {
                Objects.requireNonNull(v);
                return switch (v) {
                    case JsonNull _ -> null;
                    case JsonNumber n -> BigDecimal.valueOf(n.doubleValue());
                    case JsonString s -> new BigDecimal(s.value());
                    default -> bad(v, BigDecimal.class);
                };
            },
            v -> new JsonString(v.toPlainString()));

        register(UUID.class,
            v -> v instanceof JsonNull ? null : UUID.fromString(requireString(v, UUID.class)),
            v -> new JsonString(v.toString()));

        register(URI.class,
            v -> v instanceof JsonNull ? null : URI.create(requireString(v, URI.class)),
            v -> new JsonString(v.toString()));

        register(URL.class,
            v -> {
                if (v instanceof JsonNull) return null;
                try { return URI.create(requireString(v, URL.class)).toURL(); }
                catch (Exception e) { throw new RuntimeException(e); }
            },
            v -> new JsonString(v.toString()));

        register(Path.class,
            v -> v instanceof JsonNull ? null : Path.of(requireString(v, Path.class)),
            v -> new JsonString(v.toString()));

        registerIso(LocalDate.class,     LocalDate::parse,     Object::toString);
        registerIso(LocalTime.class,     LocalTime::parse,     Object::toString);
        registerIso(LocalDateTime.class, LocalDateTime::parse, Object::toString);
        registerIso(ZonedDateTime.class, ZonedDateTime::parse, Object::toString);
        registerIso(OffsetDateTime.class,OffsetDateTime::parse,Object::toString);
        registerIso(Period.class,        Period::parse,        Object::toString);

        register(Instant.class,
            v -> {
                Objects.requireNonNull(v);
                return switch (v) {
                    case JsonNull _ -> null;
                    case JsonNumber n -> Instant.ofEpochMilli(n.longValue());
                    case JsonString s -> Instant.parse(s.value());
                    default -> bad(v, Instant.class);
                };
            },
            v -> new JsonString(v.toString()));

        register(Duration.class,
            v -> {
                Objects.requireNonNull(v);
                return switch (v) {
                    case JsonNull _ -> null;
                    case JsonNumber n -> Duration.ofMillis(n.longValue());
                    case JsonString s -> Duration.parse(s.value());
                    default -> bad(v, Duration.class);
                };
            },
            v -> new JsonLong(v.toMillis()));
    }

    /**
     * Registers a resolver from a deserialized + serialize function pair.
     * Avoids having to write the full interface for every built-in.
     */
    private <T> void register(
        @NotNull Class<T> type,
        @NotNull Function<JsonValue, T> deserialize,
        @NotNull Function<T, JsonValue> serialize) {
        resolvers.put(type, new CoreTypeResolver<T>() {
            @Override public @NotNull Class<T>    getType()                        { return type; }
            @Override public @Nullable T          deserialize(@NotNull JsonValue v) { return deserialize.apply(v); }
            @Override public @NotNull  JsonValue  serialize(@NotNull T v)           { return serialize.apply(v); }
        });
    }

    /** Shorthand for types that round-trip through an ISO string. */
    private <T> void registerIso(
        @NotNull Class<T> type,
        @NotNull Function<String, T> fromString,
        @NotNull Function<T, String> toString) {
        register(type,
            v -> v instanceof JsonNull ? null : fromString.apply(requireString(v, type)),
            v -> new JsonString(toString.apply(v)));
    }

    private static <T> @NotNull CoreTypeResolver<T> buildEnumResolver(@NotNull Class<T> type) {
        return new CoreTypeResolver<>() {
            @Override public @NotNull Class<T> getType() { return type; }

            @Override
            public @Nullable T deserialize(@NotNull JsonValue value) {
                if (value instanceof JsonNull) return null;
                String name = requireString(value, type);
                for (T c : type.getEnumConstants()) {
                    if (((Enum<?>) c).name().equals(name)) return c;
                }
                throw new IllegalArgumentException("No enum constant " + type.getName() + "." + name);
            }

            @Override
            public @NotNull JsonValue serialize(@NotNull T value) {
                return new JsonString(((Enum<?>) value).name());
            }
        };
    }

    public static int coerceInt(@NotNull JsonValue v) {
        return switch (v) {
            case JsonNumber n -> n.intValue();
            case JsonString s -> Integer.parseInt(s.value());
            case JsonBoolean b -> b.value() ? 1 : 0;
            default -> 0;
        };
    }

    public static long coerceLong(@NotNull JsonValue v) {
        return switch (v) {
            case JsonNumber n -> n.longValue();
            case JsonString s -> Long.parseLong(s.value());
            case JsonBoolean b -> b.value() ? 1L : 0L;
            default -> 0L;
        };
    }

    public static double coerceDouble(@NotNull JsonValue v) {
        return switch (v) {
            case JsonNumber n -> n.doubleValue();
            case JsonString s -> Double.parseDouble(s.value());
            case JsonBoolean b -> b.value() ? 1.0 : 0.0;
            default -> 0.0;
        };
    }

    public static float coerceFloat(@NotNull JsonValue v) {
        return switch (v) {
            case JsonNumber n -> n.floatValue();
            case JsonString s -> Float.parseFloat(s.value());
            case JsonBoolean b -> b.value() ? 1.0f : 0.0f;
            default -> 0.0f;
        };
    }

    public static short coerceShort(@NotNull JsonValue v) {
        return switch (v) {
            case JsonNumber n -> n.shortValue();
            case JsonString s -> Short.parseShort(s.value());
            case JsonBoolean b -> (short) (b.value() ? 1 : 0);
            default -> (short) 0;
        };
    }

    public static byte coerceByte(@NotNull JsonValue v) {
        return switch (v) {
            case JsonNumber n -> n.byteValue();
            case JsonString s -> Byte.parseByte(s.value());
            case JsonBoolean b -> (byte) (b.value() ? 1 : 0);
            default -> (byte) 0;
        };
    }

    public static boolean coerceBool(@NotNull JsonValue v) {
        return switch (v) {
            case JsonBoolean b -> b.value();
            case JsonNumber n -> n.intValue() != 0;
            case JsonString s -> Boolean.parseBoolean(s.value());
            default -> false;
        };
    }

    public static char coerceChar(JsonValue v) {
        return switch (v) {
            case JsonString s when s.value().length() == 1 -> s.value().charAt(0);
            case JsonNumber n when n.intValue() >= Character.MIN_VALUE && n.intValue() <= Character.MAX_VALUE -> (char) n.intValue();
            case null, default -> throw new JsonCoercionException("Cannot coerce to char: " + v);
        };
    }

    public static String coerceString(@NotNull JsonValue v) {
        return switch (v) {
            case JsonString s -> s.value();
            case JsonNumber n -> n.toString();
            case JsonBoolean b -> Boolean.toString(b.value());
            case JsonNull _ -> "null";
            case JsonArray a -> a.toString();
            case JsonObject o -> o.toString();
        };
    }

    public static @NotNull String requireString(@NotNull JsonValue v, @NotNull Class<?> target) {
        if (v instanceof JsonString(String value)) return value;
        return bad(v, target);
    }

    /** Always throws — used as an expression in switch arms that need a return type. */
    @Contract("_, _ -> fail")
    private static <T> T bad(@NotNull JsonValue v, @NotNull Class<?> target) {
        throw new IllegalArgumentException(
            "Cannot convert " + v.getClass().getSimpleName() + " to " + target.getName());
    }
}