package com.hunt.build;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.lifecycle.internal.LifecycleExecutionPlanCalculator;
import org.apache.maven.lifecycle.internal.LifecycleTask;
import org.apache.maven.lifecycle.internal.GoalTask;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.owasp.dependencycheck.maven.CheckMojo;
import org.owasp.dependencycheck.Engine;
import org.owasp.dependencycheck.exception.ExceptionCollection;

/** Keeps OWASP's check, resolves effective plugin dependencies, and records one proven inactive binding. */
@Mojo(name="check", defaultPhase=LifecyclePhase.VERIFY, threadSafe=true,
      requiresDependencyResolution=ResolutionScope.COMPILE_PLUS_RUNTIME, requiresOnline=true)
public class EffectiveDependencyCheckMojo extends CheckMojo {
    @Component
    private RepositorySystem effectiveRepositorySystem;

    @Parameter(defaultValue="${session}", readonly=true, required=true)
    private MavenSession effectiveSession;

    @Component
    private LifecycleExecutionPlanCalculator executionPlans;

    @Component
    private RuntimeInformation runtimeInformation;

    private MavenProject pluginScanProject;

    @Override
    protected MavenProject getProject() {
        return pluginScanProject == null ? super.getProject() : pluginScanProject;
    }

    @Override
    protected ExceptionCollection scanPlugins(MavenProject project, Engine engine,
            ExceptionCollection exceptions) {
        var decision = InactiveDefaultSite.inspect(project,
                runtimeInformation == null ? null : runtimeInformation.getMavenVersion(),
                effectiveSession == null ? null : effectiveSession.getGoals(), goals -> {
                    var tasks = new ArrayList<Object>();
                    for (var goal : goals) tasks.add(goal.contains(":") ? new GoalTask(goal) : new LifecycleTask(goal));
                    return executionPlans.calculateExecutionPlan(effectiveSession, project, tasks, true).getMojoExecutions();
                });
        var scoped = pluginScanView(project, decision.exclude());
        writeScopeReceipt(project, scoped, decision);
        try {
            // ODC reads root artifacts through getProject(), before dependency collection.
            // Clone only the scan view; do not mutate Maven's reactor or an extension/report realm.
            pluginScanProject = scoped;
            var result = super.scanPlugins(scoped, engine, exceptions);
            // ODC 13 returns null after scanning plugins. Preserve earlier dependency failures.
            return result == null ? exceptions : result;
        } finally {
            pluginScanProject = null;
        }
    }

    static MavenProject pluginScanView(MavenProject project, boolean exclude) {
        if (!exclude) return project;
        var scoped = project.clone();
        var artifacts = new LinkedHashSet<>(project.getPluginArtifacts());
        artifacts.removeIf(a -> InactiveDefaultSite.KEY.equals(a.getGroupId() + ":" + a.getArtifactId())
                && InactiveDefaultSite.VERSION.equals(a.getVersion()));
        scoped.setPluginArtifacts(artifacts);
        return scoped;
    }

    private void writeScopeReceipt(MavenProject original, MavenProject scoped, InactiveDefaultSite.Decision decision) {
        try {
            var target = Path.of(original.getBuild().getDirectory(), "plugin-audit-scope.json");
            Files.createDirectories(target.getParent());
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var receipt = mapper.createObjectNode();
            receipt.put("schema", "otziv-plugin-audit-scope-v1");
            receipt.put("project", original.getId());
            receipt.put("projectPomSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(original.getFile().toPath()))));
            receipt.put("excludedDefaultSite", decision.exclude());
            receipt.put("reason", decision.reason());
            receipt.put("coordinate", InactiveDefaultSite.KEY + ":" + InactiveDefaultSite.VERSION);
            receipt.put("expectedOrigin", InactiveDefaultSite.ORIGIN);
            var before = receipt.putArray("originalBuildPluginRoots");
            original.getPluginArtifacts().stream().map(a -> a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion())
                    .sorted().forEach(before::add);
            for (var kind : List.of("build", "report", "extension")) {
                var artifacts = switch (kind) {
                    case "build" -> scoped.getPluginArtifacts();
                    case "report" -> scoped.getReportArtifacts();
                    default -> scoped.getExtensionArtifacts();
                };
                var entries = receipt.putArray(kind + "PluginRoots");
                artifacts.stream().map(a -> a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion())
                        .sorted().forEach(entries::add);
            }
            Files.writeString(target, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(receipt) + "\n", StandardCharsets.UTF_8);
            getLog().info("Plugin audit scope: " + decision.reason() + "; receipt=" + target);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot record complete plugin audit scope", failure);
        }
    }

    @Override
    protected Set<Artifact> resolveArtifactDependencies(org.eclipse.aether.artifact.Artifact root,
            MavenProject project) throws DependencyResolutionException {
        var artifacts = new LinkedHashSet<Artifact>();
        for (var plugin : effectivePlugins(root, project)) {
            var request = new CollectRequest();
            request.setRoot(new org.eclipse.aether.graph.Dependency(root, null));
            request.setRepositories(project.getRemotePluginRepositories());
            if (plugin != null) {
                for (var dependency : plugin.getDependencies()) {
                    request.addDependency(RepositoryUtils.toDependency(dependency,
                            effectiveSession.getRepositorySession().getArtifactTypeRegistry()));
                }
            }
            var result = effectiveRepositorySystem.resolveDependencies(effectiveSession.getRepositorySession(),
                    new DependencyRequest(request, null));
            if (result.getArtifactResults().isEmpty()) {
                throw new IllegalStateException("Plugin dependency graph was empty: " + root);
            }
            for (var resolved : result.getArtifactResults()) {
                if (resolved.getArtifact() == null || !resolved.isResolved()
                        || resolved.getArtifact().getFile() == null || !resolved.getArtifact().getFile().isFile()) {
                    throw new IllegalStateException("Plugin dependency was not resolved to a file: " + root);
                }
                artifacts.add(RepositoryUtils.toArtifact(resolved.getArtifact()));
            }
        }
        return artifacts;
    }

    static List<Plugin> effectivePlugins(org.eclipse.aether.artifact.Artifact root, MavenProject project) {
        var key = root.getGroupId() + ":" + root.getArtifactId();
        var build = project.getBuild().getPluginsAsMap().get(key);
        var managed = project.getPluginManagement() == null ? null
                : project.getPluginManagement().getPluginsAsMap().get(key);
        var variants = new ArrayList<Plugin>();
        if (build != null && (build.getVersion() == null || root.getVersion().equals(build.getVersion()))) {
            variants.add(build);
        } else if (build == null && managed != null) {
            variants.add(managed);
        }
        // Maven's report-plugin model takes dependency overrides from pluginManagement first.
        if (contains(project.getReportArtifacts(), root)) {
            variants.add(managed == null ? build : managed);
        }
        // Extensions have no plugin dependency overrides. Retain their original graph as well
        // when one artifact also appears as a plugin; a plugin override must not hide it.
        if (contains(project.getExtensionArtifacts(), root) || variants.isEmpty()) {
            variants.add(null);
        }
        return variants;
    }

    private static boolean contains(Set<Artifact> artifacts, org.eclipse.aether.artifact.Artifact root) {
        return artifacts != null && artifacts.stream().anyMatch(a -> a.getGroupId().equals(root.getGroupId())
                && a.getArtifactId().equals(root.getArtifactId()) && Objects.equals(a.getVersion(), root.getVersion()));
    }
}
