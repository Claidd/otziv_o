package com.hunt.build;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.owasp.dependencycheck.maven.CheckMojo;
import org.owasp.dependencycheck.Engine;
import org.owasp.dependencycheck.exception.ExceptionCollection;

/** Keeps OWASP's complete check and changes only plugin dependency collection. */
@Mojo(name="check", defaultPhase=LifecyclePhase.VERIFY, threadSafe=true,
      requiresDependencyResolution=ResolutionScope.COMPILE_PLUS_RUNTIME, requiresOnline=true)
public class EffectiveDependencyCheckMojo extends CheckMojo {
    @Component
    private RepositorySystem effectiveRepositorySystem;

    @Parameter(defaultValue="${session}", readonly=true, required=true)
    private MavenSession effectiveSession;

    @Override
    protected ExceptionCollection scanPlugins(MavenProject project, Engine engine,
            ExceptionCollection exceptions) {
        var result = super.scanPlugins(project, engine, exceptions);
        // ODC 13 returns null after scanning plugins. Preserve earlier dependency failures.
        return result == null ? exceptions : result;
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
