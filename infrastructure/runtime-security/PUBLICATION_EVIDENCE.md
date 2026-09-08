# Registry evidence and public download proof

`publish-reviewed-images.mjs` reads the registry's raw index, amd64 manifest,
image configuration, attestation manifests and in-toto payload blobs by digest.
It checks SHA-256 over the received bytes, descriptor sizes, the actual image
configuration's platform, both statement subjects, SLSA v1 source metadata and
Dockerfile bytes, and a populated SPDX 2.3 document. The original response bytes
are retained in the publication artifact before validation, including failures.
`publication.json.attestationEvidence` records their names, hashes and lengths.
Registry authentication uses an in-memory pull-scoped exchange for this package;
the publication step supplies its existing `GITHUB_TOKEN` as `GHCR_TOKEN`.
The verifier captures that token in memory and removes it from the process
environment before any Git, Maven, Docker, or scanning subprocess starts.

The source check requires the checkout's origin and HEAD to match the owner
repository and Actions commit. The pinned builder's local-context statements
carry Git hints under `runDetails.metadata.buildkit_metadata.vcs`, with matching
`vcs:*` values in `buildDefinition.externalParameters.request.root.request.args`.
The context and Dockerfile directory must match the reviewed paths, and the
embedded Dockerfile must match its reviewed normalized SHA-256. BuildKit does
not authenticate local VCS hints itself. This is a consistency check against the
publication job's clean checkout and reviewed inputs, not an independent source
signature, a complete SLSA-level claim, or proof that arbitrary generated inputs
match Git. Prepared provider JAR bytes have their own publication record hash.
[BuildKit's pinned format definition](https://github.com/moby/buildkit/blob/v0.32.2/docs/attestations/slsa-definitions.md)
describes this limitation; [Buildx Git handling](https://github.com/docker/buildx/blob/v0.36.1/build/git.go)
shows how local Git information is supplied. The retained fixture uses the
[OCI attestation storage format](https://docs.docker.com/build/metadata/attestations/attestation-storage/).

## What is verified locally

`fixtures/provenance-scratch/fixture.json` identifies a real clean, synthetic Git
build using the pinned BuildKit and Syft scanner. Its Dockerfile is `FROM scratch`
followed by `COPY source.txt /source.txt`; its benign input is recorded in the
fixture. The output was exported to a local OCI directory with `--provenance=mode=max`
and `--sbom=generator=<pinned scanner>`, without a registry push. The six retained
JSON files are the original OCI bytes, not reserialized test doubles. The
`fixtures/provenance-nested/` sample repeats the build with a nested context and
a Dockerfile outside that context, exercising Windows Git path separators.
Only directory separators are normalized when comparing those relative paths.
Scoped `.gitattributes` entries disable text conversion for these fixture JSON
files, preserving their bytes even in a Windows `core.autocrlf=true` checkout.
The negative tests recalculate the dependent descriptors after altering a subject,
source commit, source repository, context, Dockerfile, request, or SBOM, so those
tests fail on content checks even when the digest graph is internally consistent.

## Required proof after package visibility is public

The same manually dispatched quality workflow now has a downstream
`reviewed-image-anonymous-download` matrix. It starts only after every publication
job succeeds and downloads each matching `reviewed-publication-<component>`
artifact from that workflow run with the SHA-pinned
[official download action](https://github.com/actions/download-artifact/releases/tag/v4.3.0).
Each matrix job uses a separate standard GitHub-hosted Ubuntu runner, a checkout
without persisted credentials, and no registry login or package permission.

`verify-anonymous-download.mjs` requires the artifact's successful security result,
source commit, run ID, attempt, component, reviewed source pins, Dockerfile hash,
manifest hash and immutable reference to match the running job. It checks the
saved evidence graph before anonymously refetching that same graph. Before the
pull, it requires the published image configuration ID to be absent from all
local images and the exact registry reference to be absent from local inspect.
Docker runs through its absolute executable with a new owned `auths: {}` config,
an empty PATH directory that prevents credential-helper discovery, and the local
Unix socket. Its environment contains no inherited account credentials.

The separate `anonymous-download-<component>` artifact retains the raw refetched
evidence, pre-pull absence records, both pull output streams, inspected image and
`anonymous-download.json`. A `PASS` requires the exact image ID, RepoDigest,
linux/amd64 platform and all three publication labels to match. Package visibility
must already permit anonymous access; denied access or a failed pull fails the
job and retains an honest failure record. This job never changes package
visibility, deletes preexisting images, starts a container or deploys anything.
The earlier publication artifact remains unchanged.
The run-attempt match is strict: retrying only this failed job against an artifact
from a previous attempt is rejected. A retry requires publication and proof from
the same new attempt.

This workflow implementation still requires a real successful run; its local
tests use injected OCI fixtures and Docker responses and do not demonstrate
network access or an actual anonymous pull.

A successful authenticated publication is not a successful public download.
Keep `publicDownloadReadiness` at
`NOT_VERIFIED_REQUIRES_ANONYMOUS_PULL_AFTER_VISIBILITY_CONFIGURATION` until every
published component's exact immutable reference passes both checks below on a
fresh runner with no GHCR login, Docker credentials, or preloaded candidate image.
Do not substitute a mutable tag, image configuration ID, authenticated `docker pull`,
or a web package page for this proof.

1. Read and validate the entire descriptor and attestation graph anonymously.
   Use `createRegistryReader()` without `token` or `actor`; it never consults the
   process environment or Docker credentials. GHCR may issue an anonymous bearer
   token from its fixed public token endpoint, requested with only the package's
   `pull` scope. Feed the successful publication's manifest digest to
   `verifyRegistryEvidence`, and use the expected repository, commit, context,
   Dockerfile and reviewed Dockerfile hash from that same publication/source
   revision. Retain the returned raw evidence in a separate `anonymous/`
   directory and its complete verification result. Every body must be fetched
   from GHCR again; local publication artifacts are comparison inputs only.
2. Run the supplied verifier on that fresh Actions runner after downloading the
   matching publication artifact. It performs both checks and derives the exact
   reference only from the validated artifact. `COMPONENT` comes from the reviewed
   inventory matrix. The script creates its own isolated Docker configuration,
   disables credential-helper discovery, pins the local socket, and pulls without
   starting a container or changing production state.

   ```bash
   OTZIV_VERIFY_ANONYMOUS_DOWNLOAD=true node infrastructure/runtime-security/verify-anonymous-download.mjs \
     "$COMPONENT" "$RUNNER_TEMP/reviewed-publication-input" "$RUNNER_TEMP/anonymous-download"
   ```

   Preserve the separate artifact, including the command exit status and both
   pull output streams. The verifier checks the image ID, `linux/amd64`, exact
   RepoDigest and source/revision/component labels, and records pre-pull absence.
   An empty config alone is insufficient because Docker may discover helpers
   through inherited PATH. Keep the verifier's complete isolation; no credential
   environment dump or deletion of preexisting images is needed. It deletes only
   its own temporary client configuration after retaining the proof.

Record the UTC time, source commit, Actions run/attempt (or runner identity), exact
reference, anonymously verified amd64 manifest digest, retained evidence hashes,
pull exit code, inspected image ID and matching labels in a separate public
download record. Mark that record `PASS` only when both checks pass. Missing or
denied anonymous access leaves public download readiness unverified. Existing
publication records should retain their original authenticated-only readiness
status; link the later anonymous proof rather than retroactively claiming the
original run had tested it.
