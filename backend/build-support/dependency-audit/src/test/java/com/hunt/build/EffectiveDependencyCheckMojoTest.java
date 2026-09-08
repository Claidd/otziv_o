package com.hunt.build;

import static org.junit.Assert.*;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.*;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.execution.*;
import org.apache.maven.model.*;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.*;
import org.eclipse.aether.artifact.*;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.*;
import org.eclipse.aether.util.artifact.DefaultArtifactTypeRegistry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.owasp.dependencycheck.exception.ExceptionCollection;
import org.owasp.dependencycheck.maven.CheckMojo;

public class EffectiveDependencyCheckMojoTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Artifact root = new DefaultArtifact("example:fixture:jar:1.0");
    private final List<DependencyRequest> requests = new ArrayList<>();

    private MavenProject project() {
        var model = new Model(); model.setBuild(new Build());
        var project = new MavenProject(model) {
            @Override public List<RemoteRepository> getRemotePluginRepositories() {
                return List.of(new RemoteRepository.Builder("fixture", "default", "file:///fixture-only").build());
            }
        };
        project.setPluginArtifacts(new HashSet<>());
        project.setReportArtifacts(new HashSet<>());
        project.setExtensionArtifacts(new HashSet<>());
        return project;
    }

    private Plugin plugin(String version, String libraryVersion) {
        var plugin = new Plugin(); plugin.setGroupId("example"); plugin.setArtifactId("fixture"); plugin.setVersion(version);
        if (libraryVersion != null) {
            var dependency = new org.apache.maven.model.Dependency();
            dependency.setGroupId("example"); dependency.setArtifactId("library"); dependency.setVersion(libraryVersion);
            plugin.addDependency(dependency);
        }
        return plugin;
    }

    private void field(Object object, String name, Object value) throws Exception {
        for (Class<?> type=object.getClass(); type!=null; type=type.getSuperclass()) {
            try { var field=type.getDeclaredField(name); field.setAccessible(true); field.set(object,value); return; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new AssertionError("Field not found: " + name);
    }

    private EffectiveDependencyCheckMojo mojo(MavenProject project, File resolvedFile, boolean empty) throws Exception {
        var repositorySession = new DefaultRepositorySystemSession();
        repositorySession.setArtifactTypeRegistry(new DefaultArtifactTypeRegistry()
                .add(new DefaultArtifactType("jar")).add(new DefaultArtifactType("test-jar", "jar", "tests", "java")));
        var session = new MavenSession(null, repositorySession, new DefaultMavenExecutionRequest(), new DefaultMavenExecutionResult());
        var repository = (RepositorySystem) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{RepositorySystem.class},
                (proxy, method, arguments) -> {
                    if (!method.getName().equals("resolveDependencies")) throw new AssertionError(method.getName());
                    var request=(DependencyRequest)arguments[1]; requests.add(request);
                    var result=new DependencyResult(request);
                    if (empty) return result.setArtifactResults(List.of());
                    var artifacts=new ArrayList<ArtifactResult>();
                    artifacts.add(new ArtifactResult(new ArtifactRequest()).setArtifact(root.setFile(resolvedFile)));
                    for (var dependency:request.getCollectRequest().getDependencies()) {
                        artifacts.add(new ArtifactResult(new ArtifactRequest()).setArtifact(dependency.getArtifact().setFile(resolvedFile)));
                    }
                    return result.setArtifactResults(artifacts);
                });
        var mojo=new EffectiveDependencyCheckMojo();
        field(mojo,"project",project);field(mojo,"effectiveSession",session);field(mojo,"effectiveRepositorySystem",repository);
        return mojo;
    }

    @Test public void explicitOverridesPreserveClassifierScopeAndExclusionsWithoutApplicationManagement() throws Exception {
        var project=project(); var plugin=plugin("1.0","2.0");
        var dependency=plugin.getDependencies().get(0);dependency.setType("test-jar");dependency.setScope("runtime");dependency.setOptional(true);
        var exclusion=new Exclusion();exclusion.setGroupId("example");exclusion.setArtifactId("excluded");dependency.addExclusion(exclusion);
        project.getBuild().addPlugin(plugin);
        var management=new DependencyManagement();
        var foreign=dependency.clone();foreign.setVersion("999-unrelated-application-version");management.addDependency(foreign);
        project.getModel().setDependencyManagement(management);
        var artifacts=mojo(project,temporary.newFile(),false).resolveArtifactDependencies(root,project);
        assertEquals(2,artifacts.size());assertEquals(1,requests.size());
        var request=requests.get(0).getCollectRequest();assertEquals("fixture",request.getRepositories().get(0).getId());
        var selected=request.getDependencies().get(0);
        assertEquals("2.0",selected.getArtifact().getVersion());assertEquals("jar",selected.getArtifact().getExtension());
        assertEquals("tests",selected.getArtifact().getClassifier());assertEquals("runtime",selected.getScope());assertTrue(selected.isOptional());
        assertEquals("excluded",selected.getExclusions().iterator().next().getArtifactId());
    }

    @Test public void reportsRetainManagedGraphWhenSameArtifactAlsoHasBuildOverrides() throws Exception {
        var project=project(); project.getBuild().addPlugin(plugin("1.0","2.0"));
        var management=new PluginManagement();management.addPlugin(plugin("1.0","3.0"));project.getBuild().setPluginManagement(management);
        project.setReportArtifacts(Set.of(RepositoryUtils.toArtifact(root)));
        var resolved=mojo(project,temporary.newFile(),false).resolveArtifactDependencies(root,project);
        assertEquals(2,requests.size());assertEquals(3,resolved.size());
        assertEquals(Set.of("2.0","3.0"),new HashSet<>(requests.stream().map(r->r.getCollectRequest().getDependencies().get(0).getArtifact().getVersion()).toList()));
    }

    @Test public void pluginOverrideDoesNotReplaceTheSameArtifactsExtensionGraph() throws Exception {
        var project=project();project.getBuild().addPlugin(plugin("1.0","2.0"));
        project.setExtensionArtifacts(Set.of(RepositoryUtils.toArtifact(root)));
        mojo(project,temporary.newFile(),false).resolveArtifactDependencies(root,project);
        assertEquals(2,requests.size());assertEquals(1,requests.get(0).getCollectRequest().getDependencies().size());
        assertTrue(requests.get(1).getCollectRequest().getDependencies().isEmpty());
    }

    @Test public void anUnconfiguredPluginOrExtensionStillResolvesItsCompleteOriginalGraph() throws Exception {
        var project=project();var resolved=mojo(project,temporary.newFile(),false).resolveArtifactDependencies(root,project);
        assertEquals(1,resolved.size());assertEquals(1,requests.size());
        assertTrue(requests.get(0).getCollectRequest().getDependencies().isEmpty());assertEquals(root,requests.get(0).getCollectRequest().getRoot().getArtifact());
    }

    @Test public void missingFilesFailInsteadOfSilentlyDisappearingFromTheAudit() throws Exception {
        var project=project();var mojo=mojo(project,new File(temporary.getRoot(),"does-not-exist.jar"),false);
        assertThrows(IllegalStateException.class,()->mojo.resolveArtifactDependencies(root,project));
    }

    @Test public void emptyResolvedGraphsFailInsteadOfReportingSuccessfulCoverage() throws Exception {
        var project=project();var mojo=mojo(project,temporary.newFile(),true);
        assertThrows(IllegalStateException.class,()->mojo.resolveArtifactDependencies(root,project));
    }

    @Test public void pluginScanPreservesActualEarlierDependencyErrorsLostByOdc13() throws Exception {
        var project=project();var failures=new ExceptionCollection(new IllegalStateException("unresolved-project-dependency"));
        var original=new OriginalProbe();field(original,"project",project);
        assertNull(original.scan(project,failures));
        var candidate=mojo(project,temporary.newFile(),false);
        assertSame(failures,candidate.scanPlugins(project,null,failures));
        assertNull(candidate.scanPlugins(project,null,null));
    }

    private static class OriginalProbe extends CheckMojo {
        ExceptionCollection scan(MavenProject project,ExceptionCollection failures) { return scanPlugins(project,null,failures); }
    }
}
