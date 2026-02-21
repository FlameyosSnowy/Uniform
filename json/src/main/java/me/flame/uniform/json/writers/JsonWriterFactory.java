package me.flame.uniform.json.writers;

import me.flame.uniform.json.JsonConfig;
import me.flame.uniform.json.writers.async.AsyncSimpleJsonWriter;
import me.flame.uniform.json.writers.prettifiers.DefaultPrettifyEngine;
import me.flame.uniform.json.writers.prettifiers.async.AsyncDefaultPrettifyEngine;

import java.util.Arrays;
import java.util.EnumSet;

public class JsonWriterFactory {
    public static JsonWriterFactory.Builder builder(JsonConfig config) {
        return new JsonWriterFactory.Builder(config);
    }

    public static class Builder {
        private final JsonConfig config;
        private EnumSet<JsonWriterOptions> options;

        public Builder(JsonConfig config) {
            this.config = config;
            this.options = EnumSet.noneOf(JsonWriterOptions.class);
        }

        public Builder addOptions(JsonWriterOptions... options) {
            this.options.addAll(Arrays.asList(options));
            return this;
        }

        public JsonWriter build() {
            boolean asyncWrite = options.contains(JsonWriterOptions.ASYNC_WRITES);
            JsonWriter simple = asyncWrite ? new AsyncSimpleJsonWriter() : new SimpleJsonWriter();

            boolean asyncPrettify = options.contains(JsonWriterOptions.ASYNC_PRETTIFY);
            if (options.contains(JsonWriterOptions.PRETTY)) {
                DefaultPrettifyEngine defaultPrettifyEngine = new DefaultPrettifyEngine(config.indentSize());
                return new PrettyJsonWriter(simple, asyncPrettify
                    ? new AsyncDefaultPrettifyEngine(defaultPrettifyEngine)
                    : defaultPrettifyEngine);
            }

            return simple;
        }
    }
}
