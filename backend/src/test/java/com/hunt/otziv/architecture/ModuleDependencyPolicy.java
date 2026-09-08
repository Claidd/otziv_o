package com.hunt.otziv.architecture;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/** Policy shared by the bytecode gate and causal graph fixtures; no baseline is rewritten here. */
final class ModuleDependencyPolicy {
    record Edge(String source, String target) implements Comparable<Edge> {
        String line() { return source + " -> " + target; }
        @Override public int compareTo(Edge other) { return line().compareTo(other.line()); }
        static Edge parse(String line) {
            String[] parts = line.split(" -> ", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException("Invalid dependency edge: " + line);
            }
            return new Edge(parts[0], parts[1]);
        }
    }

    static Set<Edge> internalEdges(Collection<Edge> dependencies, Function<String, String> owner,
                                   Set<String> publicApi) {
        Set<Edge> result = new TreeSet<>();
        for (Edge edge : dependencies) {
            if (!owner.apply(edge.source()).equals(owner.apply(edge.target()))
                    && !isPublic(edge.target(), publicApi)) result.add(edge);
        }
        return result;
    }

    static boolean isPublic(String type, Set<String> publicApi) {
        return publicApi.stream().anyMatch(value -> value.endsWith(".*")
                ? type.startsWith(value.substring(0, value.length() - 1)) : type.equals(value));
    }

    static Set<Edge> moduleGraph(Collection<Edge> dependencies, Function<String, String> owner) {
        Set<Edge> result = new TreeSet<>();
        for (Edge edge : dependencies) {
            String from = owner.apply(edge.source());
            String to = owner.apply(edge.target());
            if (!from.equals(to)) result.add(new Edge(from, to));
        }
        return result;
    }

    /** Every edge participating in a directed cycle, including newly cyclic pre-existing edges. */
    static Set<Edge> cyclicEdges(Set<Edge> graph) {
        var next = new HashMap<String, Set<String>>();
        for (Edge edge : graph) next.computeIfAbsent(edge.source(), ignored -> new HashSet<>()).add(edge.target());
        Set<Edge> cyclic = new TreeSet<>();
        for (Edge edge : graph) {
            var pending = new ArrayDeque<String>();
            var visited = new HashSet<String>();
            pending.add(edge.target());
            while (!pending.isEmpty()) {
                String current = pending.removeFirst();
                if (current.equals(edge.source())) { cyclic.add(edge); break; }
                if (visited.add(current)) pending.addAll(next.getOrDefault(current, Set.of()));
            }
        }
        return cyclic;
    }

    static <T> Set<T> added(Set<T> actual, Set<T> allowed) {
        Set<T> result = new HashSet<>(actual);
        result.removeAll(allowed);
        return result;
    }

    private ModuleDependencyPolicy() {}
}
