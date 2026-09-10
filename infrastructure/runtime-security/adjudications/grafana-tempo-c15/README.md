# Grafana C15 exact executable review

The C15 rebuild updates gRPC to 1.83.2 and its required x/net module to 0.58.0.
The embedded Tempo pseudo-version and its Go module checksum remain identical to
the previously reviewed source. The two retained primary-source ancestry proofs,
patches and installed source files establish that both fixes are present.

`inspection.json` binds the executable copied from the stopped, digest-selected
publication container to its OCI index, config and rootfs. The binary was not run.
The pinned Go build-info reader verified the complete dependency list, including
the unchanged Tempo checksum and the new gRPC version. `review.json` authorizes
only this new executable hash and complete canonical build-info hash.

The original review remains immutable in `../grafana-tempo`. Raw HIGH findings
remain in the scanner report; the two narrowly identified fixed-code decisions
are evaluated separately. Other binaries, modules, CVEs and dependency changes
do not inherit this review.
