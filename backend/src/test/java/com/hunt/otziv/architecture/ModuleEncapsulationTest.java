package com.hunt.otziv.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** All mapped business owners participate; package names are never inferred to be separate domains. */
class ModuleEncapsulationTest {
    private static final String ROOT = "com.hunt.otziv.";
    private static final Path POLICY = Path.of("src/test/resources/architecture");
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.hunt.otziv");

    @Test
    void foreignInternalsAndNewModuleCyclesCannotBypassPublicApis() throws Exception {
        Properties ownership = new Properties();
        try (var stream = Files.newInputStream(POLICY.resolve("module-owners.properties"))) { ownership.load(stream); }
        Set<String> unmapped = new TreeSet<>();
        Function<String, String> owner = type -> {
            String relative = type.substring(ROOT.length());
            if (!relative.contains(".")) return "platform";
            String prefix = relative.substring(0, relative.indexOf('.'));
            String value = ownership.getProperty(prefix);
            if (value == null) unmapped.add(prefix);
            return value == null ? prefix : value;
        };
        Set<ModuleDependencyPolicy.Edge> dependencies = new TreeSet<>();
        for (var source : CLASSES) {
            if (!source.getName().startsWith(ROOT)) continue;
            owner.apply(source.getName());
            for (var dependency : source.getDirectDependenciesFromSelf()) {
                String target = dependency.getTargetClass().getName();
                if (target.startsWith(ROOT)) dependencies.add(new ModuleDependencyPolicy.Edge(
                        outer(source.getName()), outer(target)));
            }
        }
        Set<String> publicApi = lines("module-public-api.txt");
        var internal = ModuleDependencyPolicy.internalEdges(dependencies, owner, publicApi);
        var graph = ModuleDependencyPolicy.moduleGraph(dependencies, owner);
        var cyclic = ModuleDependencyPolicy.cyclicEdges(graph);
        writeObserved("cross-module-internals-observed.txt", internal);
        writeObserved("module-graph-observed.txt", graph);
        writeObserved("module-cycles-observed.txt", cyclic);
        assertThat(unmapped).as("New packages require an explicit data owner").isEmpty();
        var allowedInternals = edges("cross-module-internals-baseline.txt");
        var allowedCycles = edges("module-cycles-baseline.txt");
        assertThat(ModuleDependencyPolicy.added(internal, allowedInternals))
                .as("New access to foreign internals: use/export a reviewed application API; see target/architecture")
                .isEmpty();
        assertThat(ModuleDependencyPolicy.added(allowedInternals, internal))
                .as("Remove retired internal-access allowances; they must not permit a later regression")
                .isEmpty();
        assertThat(ModuleDependencyPolicy.added(cyclic, allowedCycles))
                .as("New cyclic module dependencies; public API access does not exempt cycle policy")
                .isEmpty();
        assertThat(ModuleDependencyPolicy.added(allowedCycles, cyclic))
                .as("Remove retired cycle allowances; CI never refreshes the baseline")
                .isEmpty();
    }

    private static String outer(String type) { return type.split("\\$", 2)[0]; }
    private static Set<String> lines(String name) throws Exception {
        return Files.readAllLines(POLICY.resolve(name)).stream().map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#")).collect(Collectors.toSet());
    }
    private static Set<ModuleDependencyPolicy.Edge> edges(String name) throws Exception {
        return lines(name).stream().map(ModuleDependencyPolicy.Edge::parse).collect(Collectors.toSet());
    }
    private static void writeObserved(String name, Set<ModuleDependencyPolicy.Edge> edges) throws Exception {
        Path destination = Path.of("target/architecture", name);
        Files.createDirectories(destination.getParent());
        Files.write(destination, edges.stream().sorted().map(ModuleDependencyPolicy.Edge::line).toList());
    }
}
