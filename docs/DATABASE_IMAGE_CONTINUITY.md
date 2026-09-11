# Ordinary deployment database continuity

The application rollout and production self-heal compare each resolved MySQL and
Keycloak PostgreSQL image with the existing container's immutable Docker image
ID before any database startup. An image default or local tag that resolves to a
different image is rejected and requires a separately reviewed, coordinated
database activation procedure. The guard has no force or skip option.

The same check validates the fixed Compose project/service/container identities
and the named data volume, including stopped containers and overlapping mounts.
Missing images, failed metadata queries, ambiguous containers, changed storage,
and orphaned data volumes fail closed. A missing container is a fresh database
only when its managed ordinary local volume is also absent and no prior labeled
volume remains. Existing or external storage requires coordinated provisioning
or recovery. The ordinary production rollout still has its existing mandatory
MySQL backup/running-database prerequisite; this guard does not turn that rollout
into a first-installation or recovery procedure.

After checking identities, an owned mode-0600 temporary Compose override sets
`pull_policy: never` for both database services. It is retained for every later
rollout Compose call, including dependency startup and retries; self-heal creates
its own checked override before reconciliation. Success and failure cleanup
remove only the invocation's temporary file. No image pull or database write is
performed by the Python guard.

The original image expression is preserved. Replacing it with the config ID
would change Compose's service hash and could unnecessarily recreate an unchanged
database. Compose excludes `pull_policy` from that hash; the regression suite
verifies the actual CLI hashes and resolved configuration. The override prevents
automatic pulls, including `latest` and an inherited `always` policy, while using
the locally inspected image. See the [Docker pull-policy documentation](https://docs.docker.com/reference/compose-file/services/#pull_policy)
and [Compose service-hash implementation](https://github.com/docker/compose/blob/main/pkg/compose/hash.go).

These checks operate inside the existing deployment lock and stopped self-heal
protocol. They do not coordinate external concurrent Docker tag, container, or
storage mutations. They also do not authorize an engine upgrade, accept data
compatibility, create backups, or perform rollback. The separate database change
procedure must establish those conditions; major-version rollback uses a restored
compatible backup and a fresh compatible volume.

Run the causal checks with:

```sh
python3 -m unittest discover -s infrastructure/scripts/prod -p test_database_image_guard.py -v
```

The suite exercises changed and unchanged image identities, stopped and absent
containers, fresh and orphaned volumes, malformed/mismatched metadata, actual Bash
rollout/self-heal paths and retries, temporary-file cleanup, and the real Compose
configuration hash. Docker operations in the shell fixtures are simulated; the
only real Docker commands in this suite evaluate Compose configuration. No
production SSH, container startup, or data-volume change is part of these tests.
