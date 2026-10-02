package com.hunt.build;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.ModelBase;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/** A single Maven-generated, unused lifecycle binding; never an artifact/CVE suppression. */
final class InactiveDefaultSite {
    static final String KEY = "org.apache.maven.plugins:maven-site-plugin";
    static final String VERSION = "3.12.1";
    static final String ORIGIN = "org.apache.maven:maven-core:3.9.15:default-lifecycle-bindings";
    private static final Set<String> GOALS = Set.of("clean", "validate", "compile", "test", "package", "verify", "install",
            "com.hunt.build:dependency-check-effective-maven-plugin:1.0.0:check");

    @FunctionalInterface interface Plans { List<MojoExecution> calculate(List<String> goals) throws Exception; }
    record Decision(boolean exclude, String reason) { }

    static Decision inspect(MavenProject project, String mavenVersion, List<String> goals, Plans plans) {
        if (!"3.9.15".equals(mavenVersion)) return keep("unreviewed-maven-version");
        if (project == null || project.getBuild() == null) return keep("missing-project-model");
        var plugin = project.getBuild().getPluginsAsMap().get(KEY);
        if (!generatedBinding(plugin, project)) return keep("not-exact-default-lifecycle-binding");
        var management = project.getPluginManagement();
        if (management != null && management.getPluginsAsMap().containsKey(KEY)) return keep("managed-site-plugin");
        if (project.getReportArtifacts() == null || project.getExtensionArtifacts() == null
                || project.getPluginArtifacts() == null) return keep("missing-artifact-inventory");
        var siteArtifacts = project.getPluginArtifacts().stream()
                .filter(a -> KEY.equals(a.getGroupId() + ":" + a.getArtifactId())).toList();
        if (siteArtifacts.size() != 1 || !VERSION.equals(siteArtifacts.get(0).getVersion()))
            return keep("unexpected-site-artifact-inventory");
        if (project.getReportArtifacts().stream().anyMatch(a -> KEY.equals(a.getGroupId() + ":" + a.getArtifactId()))
                || project.getExtensionArtifacts().stream().anyMatch(a -> KEY.equals(a.getGroupId() + ":" + a.getArtifactId())))
            return keep("site-report-or-extension");
        var visited = new HashSet<MavenProject>();
        for (var ancestor = project; ancestor != null; ancestor = ancestor.getParent()) {
            if (!visited.add(ancestor) || ancestor.getOriginalModel() == null) return keep("incomplete-source-models");
            var original = ancestor.getOriginalModel();
            if (declaresSite(original, original.getBuild())) return keep("explicit-site-declaration");
            for (var profile : original.getProfiles())
                if (declaresSite(profile, profile.getBuild())) return keep("profile-site-declaration");
        }
        if (project.getBuild().getDefaultGoal() != null && !project.getBuild().getDefaultGoal().isBlank())
            return keep("configured-default-goal");
        if (goals == null || goals.isEmpty() || !GOALS.containsAll(goals)) return keep("unreviewed-invocation");
        try {
            // The audit's own direct goal does not describe the preceding bootstrap/build.
            // Inspect the complete clean/default lifecycle too, including descriptor-created forks.
            if (hasSite(plans.calculate(List.of("clean", "deploy")), new HashSet<>())
                    || hasSite(plans.calculate(goals), new HashSet<>())) return keep("site-in-execution-plan");
        } catch (Exception failure) {
            return keep("execution-plan-unavailable");
        }
        return new Decision(true, "unused-maven-3.9.15-default-site-binding");
    }

    private static Decision keep(String reason) { return new Decision(false, reason); }

    static boolean hasSite(List<MojoExecution> executions, Set<MojoExecution> visited) {
        if (executions == null) throw new IllegalArgumentException("Missing execution plan");
        for (var execution : executions) {
            if (execution == null || execution.getMojoDescriptor() == null)
                throw new IllegalArgumentException("Unresolved execution descriptor");
            if (!visited.add(execution)) continue;
            if (KEY.equals(execution.getGroupId() + ":" + execution.getArtifactId())) return true;
            for (var fork : execution.getForkedExecutions().values()) if (hasSite(fork, visited)) return true;
        }
        return false;
    }

    private static boolean declaresSite(ModelBase model, BuildBase build) {
        if (model.getReporting() != null && model.getReporting().getPlugins().stream()
                .anyMatch(p -> possibleSite(p.getGroupId(), p.getArtifactId()))) return true;
        if (build == null) return false;
        if (build.getPlugins().stream().anyMatch(p -> possibleSite(p.getGroupId(), p.getArtifactId()))) return true;
        return build.getPluginManagement() != null && build.getPluginManagement().getPlugins().stream()
                .anyMatch(p -> possibleSite(p.getGroupId(), p.getArtifactId()));
    }

    private static boolean possibleSite(String group, String artifact) {
        // Raw inactive/parent profiles can contain unresolved coordinate properties.
        // An uncertain source declaration never authorizes reducing audit coverage.
        return group == null || artifact == null || group.contains("${") || artifact.contains("${")
                || KEY.equals(group + ":" + artifact);
    }

    static boolean generatedBinding(Plugin plugin, MavenProject project) {
        if (plugin == null || !VERSION.equals(plugin.getVersion()) || !plugin.getDependencies().isEmpty()
                || plugin.isExtensions() || plugin.getInherited() != null || plugin.getExecutions().size() != 2) return false;
        for (var field : List.of("", "groupId", "artifactId", "version")) {
            var location = plugin.getLocation(field);
            if (location == null || location.getSource() == null
                    || !ORIGIN.equals(location.getSource().getModelId()) || location.getSource().getLocation() != null) return false;
        }
        if (!generatedConfiguration(plugin.getConfiguration(), project)) return false;
        for (var execution : plugin.getExecutions()) {
            String phase, goal;
            if ("default-site".equals(execution.getId())) { phase = "site"; goal = "site"; }
            else if ("default-deploy".equals(execution.getId())) { phase = "site-deploy"; goal = "deploy"; }
            else return false;
            if (!phase.equals(execution.getPhase()) || !List.of(goal).equals(execution.getGoals())
                    || execution.getInherited() != null || !generatedConfiguration(execution.getConfiguration(), project)) return false;
        }
        return plugin.getExecutions().stream().map(e -> e.getId()).distinct().count() == 2;
    }

    private static boolean generatedConfiguration(Object value, MavenProject project) {
        if (!(value instanceof Xpp3Dom config) || config.getAttributeNames().length != 0
                || config.getValue() != null || !"configuration".equals(config.getName()) || config.getChildCount() != 2) return false;
        var output = config.getChild("outputDirectory");
        var reports = config.getChild("reportPlugins");
        if (project.getReporting() == null || output == null || !leaf(output, project.getReporting().getOutputDirectory())
                || reports == null || reports.getValue() != null || reports.getAttributeNames().length != 0
                || reports.getChildCount() != 1) return false;
        var report = reports.getChild("reportPlugin");
        return report != null && report.getValue() == null && report.getAttributeNames().length == 0 && report.getChildCount() == 2
                && leaf(report.getChild("groupId"), "org.apache.maven.plugins")
                && leaf(report.getChild("artifactId"), "maven-project-info-reports-plugin");
    }

    private static boolean leaf(Xpp3Dom node, String expected) {
        return node != null && expected != null && expected.equals(node.getValue())
                && node.getChildCount() == 0 && node.getAttributeNames().length == 0;
    }
}
