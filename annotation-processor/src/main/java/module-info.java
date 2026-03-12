module Uniform.annotation.processor.main {
    requires com.google.auto.service;
    requires com.palantir.javapoet;
    requires java.compiler;
    requires Uniform.core.main;

    exports io.github.flameyossnowy.uniform.processor;
}