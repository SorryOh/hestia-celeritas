package org.taumc.celeritas.tools.shaders;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.lwjgl.util.shaderc.Shaderc.*;

/**
 * Hestia build (2026-10-18): compiles the Vulkan versions of Celeritas's chunk shaders (src/main/vulkan) to SPIR-V at
 * build time, for Kora's Vulkan renderer (README.md, "Vulkan shaders"). Every variant variants.txt describes is
 * compiled with LWJGL's shaderc binding (one fixed compiler version, so the same SPIR-V on every machine and the jar
 * stays reproducible) as Vulkan 1.3 SPIR-V, optimized for performance, without debug information, into
 * {@code <source name>-<n>.<stage>.spv}; spirv.txt lists each file with its source and macros, sorted, as the renderer
 * looks them up. The tool and shaderc never go into the jar.
 *
 *   CompileVulkanShaders <source folder> <output folder>
 */
public final class CompileVulkanShaders {
    private CompileVulkanShaders() {}

    private record Variant(String source, List<String> macros) {}

    /** -Dshaders.optimization=zero|size|performance (performance unless set: what the jar ships). */
    private static final int OPTIMIZATION = switch (System.getProperty("shaders.optimization", "performance")) {
        case "zero" -> shaderc_optimization_level_zero;
        case "size" -> shaderc_optimization_level_size;
        default -> shaderc_optimization_level_performance;
    };

    public static void main(String[] args) throws IOException {
        Path source = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        // Only this run's files: a removed variant leaves nothing stale behind.
        try (Stream<Path> old = Files.list(output)) {
            for (Path file : old.toList()) {
                Files.delete(file);
            }
        }
        List<Variant> variants = variants(Files.readString(source.resolve("variants.txt"), StandardCharsets.UTF_8));
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0) {
            throw new IllegalStateException("shaderc could not start");
        }
        StringBuilder index = new StringBuilder("# Written by tools/shaders/CompileVulkanShaders.java: <SPIR-V file> <source> <macros, sorted>\n");
        try {
            String current = null;
            int n = 0;
            for (Variant variant : variants) {
                if (!variant.source().equals(current)) {
                    current = variant.source();
                    n = 0;
                }
                String name = variant.source();
                int dot = name.lastIndexOf('.');
                String stage = name.substring(dot + 1);
                int kind = switch (stage) {
                    case "vert" -> shaderc_glsl_vertex_shader;
                    case "frag" -> shaderc_glsl_fragment_shader;
                    default -> throw new IllegalArgumentException("unknown shader stage: " + name);
                };
                String text = Files.readString(source.resolve(name), StandardCharsets.UTF_8).replace("\r\n", "\n");
                String file = name.substring(0, dot) + "-" + n++ + "." + stage + ".spv";
                Files.write(output.resolve(file), compile(compiler, text, kind, name, variant.macros()));
                List<String> sorted = new ArrayList<>(variant.macros());
                sorted.sort(null);
                index.append(file).append(' ').append(name);
                for (String macro : sorted) {
                    index.append(' ').append(macro);
                }
                index.append('\n');
            }
        } finally {
            shaderc_compiler_release(compiler);
        }
        Files.writeString(output.resolve("spirv.txt"), index.toString(), StandardCharsets.UTF_8);
        System.out.println("Compiled " + variants.size() + " Vulkan shader variants into " + output);
    }

    /** Every combination of each "shader" block's "always" macros and one alternative per "choose" line. */
    private static List<Variant> variants(String text) {
        List<Variant> variants = new ArrayList<>();
        String shader = null;
        List<List<String>> combinations = new ArrayList<>();
        for (String raw : (text + "\nshader").split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] words = line.split("\\s+", 2);
            String rest = words.length > 1 ? words[1] : "";
            switch (words[0]) {
                case "shader" -> {
                    if (shader != null) {
                        for (List<String> macros : combinations) {
                            variants.add(new Variant(shader, macros));
                        }
                    }
                    shader = rest;
                    combinations = new ArrayList<>();
                    combinations.add(new ArrayList<>());
                }
                case "always" -> {
                    for (List<String> macros : combinations) {
                        macros.addAll(split(rest));
                    }
                }
                case "choose" -> {
                    List<List<String>> next = new ArrayList<>();
                    for (List<String> macros : combinations) {
                        for (String alternative : rest.split("\\|", -1)) {
                            List<String> combined = new ArrayList<>(macros);
                            combined.addAll(split(alternative));
                            next.add(combined);
                        }
                    }
                    combinations = next;
                }
                default -> throw new IllegalArgumentException("variants.txt: unknown keyword in '" + line + "'");
            }
        }
        return variants;
    }

    private static List<String> split(String macros) {
        String trimmed = macros.strip();
        return trimmed.isEmpty() ? List.of() : Arrays.asList(trimmed.split("\\s+"));
    }

    private static byte[] compile(long compiler, String text, int kind, String name, List<String> macros) {
        long options = shaderc_compile_options_initialize();
        try {
            shaderc_compile_options_set_source_language(options, shaderc_source_language_glsl);
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_3);
            shaderc_compile_options_set_optimization_level(options, OPTIMIZATION);
            for (String macro : macros) {
                int equals = macro.indexOf('=');
                shaderc_compile_options_add_macro_definition(options, equals < 0 ? macro : macro.substring(0, equals),
                    equals < 0 ? "" : macro.substring(equals + 1));
            }
            long result = shaderc_compile_into_spv(compiler, text, kind, name, "main", options);
            try {
                if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                    throw new IllegalStateException(name + " (" + String.join(" ", macros) + "): " + shaderc_result_get_error_message(result));
                }
                ByteBuffer spirv = shaderc_result_get_bytes(result);
                byte[] bytes = new byte[spirv.remaining()];
                spirv.get(bytes);
                return bytes;
            } finally {
                shaderc_result_release(result);
            }
        } finally {
            shaderc_compile_options_release(options);
        }
    }
}
