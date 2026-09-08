# Effective Maven plugin dependency audit

This small Maven plugin inherits OWASP Dependency-Check 13.0.0's complete `check`
goal. It changes plugin dependency collection to include the effective Maven
model's direct overrides and exclusions. Application, test, provided and system
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

The inherited descriptor preserves all 155 upstream check parameters and their
configuration; one Maven session parameter and one resolver component are added.
The real project comparison covers all 11 C5 plugin roots and 278 resolved
artifact occurrences: coordinates and SHA-256 values match Apache's resolver.
That evidence applies to this tested Maven 3.9.15 project; it is not a claim of
universal compatibility with arbitrary Maven extensions. Seven focused tests
cover error retention, missing coverage, override metadata and colliding
plugin/report/extension roles.

The dependency audit uses the separately named
`com.hunt.build:dependency-check-effective-maven-plugin:1.0.0`; no upstream JAR,
signature or published coordinate is rewritten. The original OWASP engine is a
normal dependency. This module is built from source by the repository's build
support bootstrap. It must never be presented as an upstream OWASP release.

Revisit this adapter when the upstream resolver bug is fixed. Remove it only
after the same graph, descriptor, error-handling and full audit checks pass with
the new upstream release.
