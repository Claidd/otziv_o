# Effective Maven plugin dependency audit

This small Maven plugin inherits OWASP Dependency-Check 13.0.0's complete `check`
goal. It changes plugin dependency collection to include the effective Maven
model's direct overrides and exclusions, with the one narrowly proven inactive
default binding described below. Application, test, provided and system
scope scanning, analyzers, report generation, suppression rules and the CVSS gate
remain in the upstream implementation. `scanPlugins` remains enabled in the
application audit profile.

The upstream bug is tracked in [DependencyCheck #8570](https://github.com/dependency-check/DependencyCheck/issues/8570).
The collection uses the same Maven Resolver request semantics as
[Apache Maven Dependency Plugin 3.11.0](https://github.com/apache/maven-dependency-plugin/blob/maven-dependency-plugin-3.11.0/src/main/java/org/apache/maven/plugins/dependency/utils/ResolverUtil.java).
It does not apply application dependency management to plugin realms.

When one artifact is both a build/report plugin or a build extension, all
applicable graphs are retained. In particular, a plugin override cannot hide an
extension's unmodified dependency graph. Empty graphs and missing artifact files
fail the audit. An additional override preserves incoming dependency-resolution
errors that upstream 13.0.0's plugin scan otherwise replaces with `null`.

The inherited descriptor preserves upstream check parameters and their
configuration. The adapter adds a Maven session parameter, the resolver, Maven
runtime information and Maven's execution-plan calculator.
The real project comparison covers all 11 C5 plugin roots and 278 resolved
artifact occurrences: coordinates and SHA-256 values match Apache's resolver.
That evidence applies to this tested Maven 3.9.15 project; it is not a claim of
universal compatibility with arbitrary Maven extensions. Tests cover error
retention, missing coverage, override metadata and colliding plugin/report/extension
roles. The upstream scan bridge also verifies restoration of Maven's original
project on success/failure and continued scanning of transitive Site dependencies.

## Unused default Site lifecycle binding

The repository does not execute Site goals. Removing its explicit pin alone would
reintroduce Maven 3.9.15's implicit Site 3.12.1, so this adapter classifies that one
build root before OWASP scans it. `InactiveDefaultSite` requires the exact Maven
version and `InputSource` origin, unmodified generated configuration and both
default executions. Explicit declarations in the project, parents, inactive or
active profiles, plugin management, reports or extensions prevent omission.

For a supported invocation (`clean`, `validate`, `compile`, `test`, `package`,
`verify`, `install` or this adapter's fully qualified check goal), Maven calculates
the entire clean/default lifecycle through deploy and the current command plan.
All nested forked executions are inspected. No planned goal is executed by this
inspection. Site invocations, a configured default goal, other commands, unknown
origins/versions and planning errors retain the complete plugin scan. A missing
descriptor is not treated as an empty plan.

Only a scoped clone's exact build-root artifact is removed. Maven's reactor,
report/extension roots, other plugin roots and dependency graphs remain intact.
A `plugin-audit-scope.json` receipt records the decision, reason, POM hash, GAV and
before/after root inventories. The Python runner and the five-report OSV gate
require fresh valid receipts; arbitrary removed roots fail validation. This is
an explicit audit-scope rule, not a CVE suppression or a claim that stock Site is
safe to run. Manual Site use requires a separately reviewed plugin and full audit.

Regression fixtures cover source/profile/parent/management declarations, property
origins, reports/extensions, changed configuration/executions, unknown commands,
default goals, nested forks, missing metadata and planner errors. Real Maven 3.9.15
model/plan probes additionally verified all five repository projects and a
descriptor-only plugin that forks Site, without executing Site or opening a server.

The dependency audit uses the separately named
`com.hunt.build:dependency-check-effective-maven-plugin:1.0.0`; no upstream JAR,
signature or published coordinate is rewritten. The original OWASP engine is a
normal dependency. This module is built from source by the repository's build
support bootstrap. It must never be presented as an upstream OWASP release.

Revisit this adapter when the upstream resolver bug is fixed. Remove it only
after the same graph, descriptor, error-handling and full audit checks pass with
the new upstream release.
