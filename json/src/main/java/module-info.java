module Uniform.json.main {
    requires jdk.incubator.vector;
    requires org.jetbrains.annotations;
    requires TurboScanner.main;
    requires Uniform.core.main;

    exports io.github.flameyossnowy.uniform.json;
    exports io.github.flameyossnowy.uniform.json.exceptions;
    exports io.github.flameyossnowy.uniform.json.mappers;

    exports io.github.flameyossnowy.uniform.json.parser;
    exports io.github.flameyossnowy.uniform.json.parser.lowlevel;

    exports io.github.flameyossnowy.uniform.json.writers;
    exports io.github.flameyossnowy.uniform.json.writers.async;
    exports io.github.flameyossnowy.uniform.json.writers.prettifiers;
    exports io.github.flameyossnowy.uniform.json.writers.prettifiers.async;


    exports io.github.flameyossnowy.uniform.json.resolvers;
    exports io.github.flameyossnowy.uniform.json.dom;
    exports io.github.flameyossnowy.uniform.json.tokenizer;
    exports io.github.flameyossnowy.uniform.json.features;
}