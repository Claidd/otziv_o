package com.hunt.otziv.architecture;

import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static com.hunt.otziv.architecture.ModuleDependencyPolicy.*;

class ModuleDependencyPolicyTest {
    private static Edge edge(String from, String to) { return new Edge(from, to); }

    @Test
    void newForeignServiceAccessFailsEvenWhenNoRepositoryIsReferenced() {
        var oldEdge = edge("orders.Legacy", "billing.internal.Legacy");
        var addedEdge = edge("orders.NewCommand", "billing.internal.Settlement");
        var allowedApi = edge("orders.NewCommand", "billing.api.PaymentOperations");
        var actual = internalEdges(Set.of(oldEdge, addedEdge, allowedApi), type -> type.split("\\.")[0],
                Set.of("billing.api.*"));
        assertThat(added(actual, Set.of(oldEdge))).containsExactly(addedEdge);
    }

    @Test
    void closingAnIndirectCycleIsRejectedIncludingThroughPublicApis() {
        var previous = Set.of(edge("orders", "billing"), edge("billing", "payments"));
        var next = Set.of(edge("orders", "billing"), edge("billing", "payments"), edge("payments", "orders"));
        assertThat(cyclicEdges(previous)).isEmpty();
        assertThat(added(cyclicEdges(next), cyclicEdges(previous))).containsExactlyInAnyOrderElementsOf(next);
    }

    @Test
    void newAcyclicPublicDependencyDoesNotBecomeAFictitiousCycle() {
        var oldCycle = Set.of(edge("billing", "payments"), edge("payments", "billing"));
        var next = Set.of(edge("billing", "payments"), edge("payments", "billing"), edge("orders", "billing"));
        assertThat(added(cyclicEdges(next), oldCycle)).isEmpty();
    }

    @Test
    void retiredExceptionIsReportedRatherThanSilentlyRetained() {
        var retired = edge("orders.Legacy", "billing.internal.OldService");
        assertThat(added(Set.of(retired), Set.of())).containsExactly(retired);
    }

    @Test
    void exactTypeExportDoesNotExportNeighboursOrADeceptivePackagePrefix() {
        var exports = Set.of("billing.api.*", "orders.application.StatusCommands");
        assertThat(isPublic("billing.api.PaymentOperations", exports)).isTrue();
        assertThat(isPublic("billing.api2.Internal", exports)).isFalse();
        assertThat(isPublic("orders.application.StatusCommands", exports)).isTrue();
        assertThat(isPublic("orders.application.InternalPolicy", exports)).isFalse();
    }

    @Test
    void compiledForbiddenServiceAndPublicApiCycleAreBothDetected(@TempDir Path fixture) throws Exception {
        Path service = fixture.resolve("fixture/billing/internal/Settlement.java");
        Path api = fixture.resolve("fixture/billing/api/PaymentApi.java");
        Path command = fixture.resolve("fixture/orders/Command.java");
        Path array = fixture.resolve("fixture/orders/ArrayCommand.java");
        Path generic = fixture.resolve("fixture/orders/GenericCommand.java");
        for (Path file : new Path[]{service, api, command, array, generic}) Files.createDirectories(file.getParent());
        Files.writeString(service, "package fixture.billing.internal; public class Settlement {}");
        Files.writeString(api, "package fixture.billing.api; public interface PaymentApi { fixture.orders.Command observe(); }");
        Files.writeString(command, "package fixture.orders; public class Command { private fixture.billing.internal.Settlement illegal; private fixture.billing.api.PaymentApi allowed; }");
        Files.writeString(array, "package fixture.orders; public class ArrayCommand { private fixture.billing.internal.Settlement[] values; }");
        Files.writeString(generic, "package fixture.orders; public class GenericCommand { private java.util.List<fixture.billing.internal.Settlement> values; }");
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", fixture.toString(),
                service.toString(), api.toString(), command.toString(), array.toString(), generic.toString())).isZero();
        var imported = new com.tngtech.archunit.core.importer.ClassFileImporter().importPath(fixture);
        Set<Edge> actual = new TreeSet<>();
        for (var type : imported) for (var dependency : type.getDirectDependenciesFromSelf()) {
            if (dependency.getTargetClass().getName().startsWith("fixture.")) actual.add(edge(
                    type.getName(), dependency.getTargetClass().getName()));
        }
        java.util.function.Function<String, String> owner = type -> type.split("\\.")[1];
        assertThat(internalEdges(actual, owner, Set.of("fixture.billing.api.*", "fixture.orders.Command")))
                .containsExactlyInAnyOrder(edge("fixture.orders.Command", "fixture.billing.internal.Settlement"),
                        edge("fixture.orders.ArrayCommand", "fixture.billing.internal.Settlement"),
                        edge("fixture.orders.GenericCommand", "fixture.billing.internal.Settlement"));
        assertThat(cyclicEdges(moduleGraph(actual, owner)))
                .containsExactlyInAnyOrder(edge("orders", "billing"), edge("billing", "orders"));
    }
}
