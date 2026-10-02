package com.hunt.build;

import static org.junit.Assert.*;
import java.util.*;
import java.util.function.Consumer;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.model.*;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.junit.Test;

public class InactiveDefaultSiteTest {
    private Plugin site() {
        var plugin = new Plugin(); plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-site-plugin"); plugin.setVersion("3.12.1"); return plugin;
    }
    private Xpp3Dom node(String name, String value, Xpp3Dom... children) {
        var node = new Xpp3Dom(name); node.setValue(value); for(var child:children) node.addChild(child); return node;
    }
    private Xpp3Dom configuration() {
        return node("configuration", null, node("outputDirectory", "/fixture/target/site"),
                node("reportPlugins", null, node("reportPlugin", null,
                        node("groupId", "org.apache.maven.plugins"), node("artifactId", "maven-project-info-reports-plugin"))));
    }
    MavenProject project() {
        var model = new Model(); model.setBuild(new Build());
        var reporting = new Reporting(); reporting.setOutputDirectory("/fixture/target/site"); model.setReporting(reporting);
        var project = new MavenProject(model); project.setOriginalModel(new Model());
        var plugin = site(); plugin.setConfiguration(configuration());
        var origin = new InputSource(); origin.setModelId(InactiveDefaultSite.ORIGIN);
        for(var key:List.of("", "groupId", "artifactId", "version")) plugin.setLocation(key, new InputLocation(-1,-1,origin));
        for(var id:List.of("site", "deploy")) {
            var execution = new PluginExecution(); execution.setId("default-"+id);
            execution.setPhase(id.equals("site") ? "site" : "site-deploy"); execution.addGoal(id);
            execution.setConfiguration(configuration()); plugin.addExecution(execution);
        }
        model.getBuild().addPlugin(plugin);
        project.setPluginArtifacts(new LinkedHashSet<>(List.of(
                RepositoryUtils.toArtifact(new DefaultArtifact(InactiveDefaultSite.KEY+":jar:3.12.1")),
                RepositoryUtils.toArtifact(new DefaultArtifact("example:other-plugin:jar:1")))));
        project.setReportArtifacts(Set.of()); project.setExtensionArtifacts(Set.of()); return project;
    }
    private InactiveDefaultSite.Decision inspect(MavenProject project) {
        return InactiveDefaultSite.inspect(project,"3.9.15",List.of("verify"), goals -> List.of(execution("other-plugin")));
    }
    private MojoExecution execution(String artifact) {
        var plugin = new PluginDescriptor(); plugin.setGroupId(artifact.equals("maven-site-plugin") ? "org.apache.maven.plugins" : "example");
        plugin.setArtifactId(artifact); plugin.setVersion("1");
        var mojo = new MojoDescriptor(); mojo.setPluginDescriptor(plugin); mojo.setGoal("fixture"); return new MojoExecution(mojo);
    }
    private void retained(Consumer<MavenProject> mutate) {
        var project = project(); mutate.accept(project); assertFalse(inspect(project).toString(),inspect(project).exclude());
    }
    private Plugin binding(MavenProject project) { return project.getBuild().getPluginsAsMap().get(InactiveDefaultSite.KEY); }

    @Test public void onlyKnownGeneratedUnusedBindingIsExcludedAndBothPlansAreChecked() {
        var requested = new ArrayList<List<String>>();
        var result=InactiveDefaultSite.inspect(project(),"3.9.15",List.of("verify"), goals -> {requested.add(goals);return List.of(execution("other-plugin"));});
        assertTrue(result.toString(),result.exclude()); assertEquals(List.of(List.of("clean","deploy"),List.of("verify")),requested);
    }
    @Test public void originAndExactVersionMustBeProven() {
        retained(p->binding(p).setVersion("3.22.0"));
        for(var field:List.of("","groupId","artifactId","version")) retained(p->binding(p).setLocation(field,new InputLocation(1,1)));
        retained(p->{var source=new InputSource();source.setModelId("fixture:explicit:1");binding(p).setLocation("",new InputLocation(1,1,source));});
        retained(p->binding(p).getLocation("").getSource().setLocation("pom.xml"));
        assertFalse(InactiveDefaultSite.inspect(project(),"3.9.16",List.of("verify"), goals -> List.of()).exclude());
    }
    @Test public void anyCustomizedBindingRemainsInFullAudit() {
        retained(p->binding(p).addDependency(new Dependency())); retained(p->binding(p).setExtensions(true));
        retained(p->binding(p).setInherited("true")); retained(p->binding(p).getExecutions().remove(0));
        retained(p->binding(p).getExecutions().get(0).setPhase("compile"));
        retained(p->binding(p).getExecutions().get(0).addGoal("run"));
        retained(p->((Xpp3Dom)binding(p).getConfiguration()).addChild(node("port","9999")));
        retained(p->((Xpp3Dom)binding(p).getConfiguration()).getChild("outputDirectory").setValue("/custom"));
        retained(p->((Xpp3Dom)binding(p).getExecutions().get(0).getConfiguration()).setAttribute("combine.self","override"));
        retained(p->binding(p).getExecutions().get(1).setId("default-site"));
    }
    @Test public void explicitSameVersionInProjectOrParentIsNeverOmitted() {
        retained(p->{var build=new Build();build.addPlugin(site());p.getOriginalModel().setBuild(build);});
        retained(p->{var parent=new MavenProject(new Model());var source=new Model();var build=new Build();build.addPlugin(site());source.setBuild(build);parent.setOriginalModel(source);p.setParent(parent);});
        retained(p->{var management=new PluginManagement();management.addPlugin(site());p.getBuild().setPluginManagement(management);});
        retained(p->{var build=new Build();var management=new PluginManagement();management.addPlugin(site());build.setPluginManagement(management);p.getOriginalModel().setBuild(build);});
    }
    @Test public void inactiveProfileAndReportingDeclarationsRemainAudited() {
        retained(p->{var profile=new Profile();var build=new BuildBase();build.addPlugin(site());profile.setBuild(build);p.getOriginalModel().addProfile(profile);});
        retained(p->{var profile=new Profile();var build=new BuildBase();var plugin=site();plugin.setArtifactId("${site.artifact}");build.addPlugin(plugin);profile.setBuild(build);p.getOriginalModel().addProfile(profile);});
        retained(p->{var profile=new Profile();var build=new BuildBase();var managed=new PluginManagement();managed.addPlugin(site());build.setPluginManagement(managed);profile.setBuild(build);p.getOriginalModel().addProfile(profile);});
        for(var profile:List.of(false,true)) retained(p->{
            var reporting=new Reporting();var plugin=new ReportPlugin();plugin.setGroupId("org.apache.maven.plugins");plugin.setArtifactId("maven-site-plugin");reporting.addPlugin(plugin);
            if(profile){var source=new Profile();source.setReporting(reporting);p.getOriginalModel().addProfile(source);}else p.getOriginalModel().setReporting(reporting);
        });
    }
    @Test public void sharedArtifactReportAndExtensionRealmsAreNotFiltered() {
        for(var version:List.of("3.12.1","3.22.0")) {
            var artifact=RepositoryUtils.toArtifact(new DefaultArtifact(InactiveDefaultSite.KEY+":jar:"+version));
            retained(p->p.setReportArtifacts(Set.of(artifact))); retained(p->p.setExtensionArtifacts(Set.of(artifact)));
        }
    }
    @Test public void allSiteInvocationsAndUnknownCommandsRetainCompleteAudit() {
        for(var goal:List.of("site","site:run","org.apache.maven.plugins:maven-site-plugin:3.12.1:run","post-site","site-deploy","unknown:goal"))
            assertFalse(InactiveDefaultSite.inspect(project(),"3.9.15",List.of(goal), goals -> List.of()).exclude());
        assertFalse(InactiveDefaultSite.inspect(project(),"3.9.15",List.of(), goals -> List.of()).exclude());
        retained(p->p.getBuild().setDefaultGoal("site"));
    }
    @Test public void directAndNestedForkedSiteExecutionsRetainCompleteAudit() {
        assertFalse(InactiveDefaultSite.inspect(project(),"3.9.15",List.of("verify"), goals -> List.of(execution("maven-site-plugin"))).exclude());
        var outer=execution("outer");var middle=execution("middle");middle.setForkedExecutions("fixture",List.of(execution("maven-site-plugin")));outer.setForkedExecutions("fixture",List.of(middle));
        assertFalse(InactiveDefaultSite.inspect(project(),"3.9.15",List.of("verify"), goals -> List.of(outer)).exclude());
    }
    @Test public void failedOrIncompletePlanAndSourceMetadataCannotReduceCoverage() {
        assertFalse(InactiveDefaultSite.inspect(project(),"3.9.15",List.of("verify"), goals -> {throw new Exception("unresolved plugin");}).exclude());
        assertFalse(InactiveDefaultSite.inspect(project(),"3.9.15",List.of("verify"), goals -> null).exclude());
        assertFalse(InactiveDefaultSite.inspect(project(),"3.9.15",List.of("verify"), goals -> List.of(new MojoExecution(site(),"site","fixture"))).exclude());
        retained(p->p.setOriginalModel(null)); retained(p->p.setParent(p)); retained(p->p.setReportArtifacts(null));
    }
    @Test public void scanViewRemovesOnlyExactBuildRootAndDoesNotMutateReactor() {
        var original=project();var before=new LinkedHashSet<>(original.getPluginArtifacts());
        var report=RepositoryUtils.toArtifact(new DefaultArtifact("example:report:jar:1")); original.setReportArtifacts(Set.of(report));
        var extension=RepositoryUtils.toArtifact(new DefaultArtifact("example:extension:jar:1")); original.setExtensionArtifacts(Set.of(extension));
        var scoped=EffectiveDependencyCheckMojo.pluginScanView(original,true);
        assertEquals(before,original.getPluginArtifacts());assertEquals(1,scoped.getPluginArtifacts().size());
        assertEquals("other-plugin",scoped.getPluginArtifacts().iterator().next().getArtifactId());
        assertEquals(original.getReportArtifacts(),scoped.getReportArtifacts());assertEquals(original.getExtensionArtifacts(),scoped.getExtensionArtifacts());
        assertNotSame(original,scoped);assertSame(original,EffectiveDependencyCheckMojo.pluginScanView(original,false));
    }
}
