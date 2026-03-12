package io.github.flameyossnowy.uniform.json.writers;

import io.github.flameyossnowy.uniform.json.JsonConfig;
import io.github.flameyossnowy.uniform.json.writers.async.AsyncSimpleJsonWriter;
import io.github.flameyossnowy.uniform.json.writers.prettifiers.DefaultPrettifyEngine;
import io.github.flameyossnowy.uniform.json.writers.prettifiers.async.AsyncDefaultPrettifyEngine;

import java.util.Arrays;
import java.util.EnumSet;

public class JsonWriterFactory {
    public static Builder builder(JsonConfig config) {
        return new Builder(config);
    }

    public static class Builder {
        private final JsonConfig config;
        private final EnumSet<JsonWriterOptions> options;

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

            if (options.contains(JsonWriterOptions.PRETTY)) {
                boolean asyncPrettify = options.contains(JsonWriterOptions.ASYNC_PRETTIFY);
                DefaultPrettifyEngine engine = new DefaultPrettifyEngine(config);
                return new PrettyJsonWriter(simple, asyncPrettify
                    ? new AsyncDefaultPrettifyEngine(engine)
                    : engine);
            }

            return simple;
        }
    }
}