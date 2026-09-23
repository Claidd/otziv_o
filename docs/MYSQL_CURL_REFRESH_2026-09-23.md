# MySQL curl security refresh, 23 September 2026

The existing C18 MySQL container fails the required runtime scan with four HIGH findings: CVE-2026-8458 and CVE-2026-8927 in curl and libcurl. Oracle advisory [ELSA-2026-69126](https://linux.oracle.com/errata/ELSA-2026-69126.html) fixes these in 7.76.1-40.el9_8.7.

C21 updates only these two packages. MySQL remains 9.7.3, its server binary is byte-identical, and every other installed package is unchanged. Publication run 35879814748 retains the immutable image, SBOM, provenance, a raw scan with zero HIGH/CRITICAL findings and independent anonymous-download evidence. No suppression is added.

The isolated rehearsal restores a fresh encrypted production backup, then checks all table counts/checksums, schema, migration history, accounts and settings across the image change. It verifies existing login, writes across restart, rollback on the same disposable volume, and a fresh restore. It never writes to production or publishes private rows. Only hashes and counts are retained under infrastructure/runtime-security/proofs/c21-mysql.

The owner explicitly authorized this package update and a brief database restart to complete the contextual Zheka meme release. Activation uses an owned deployment lock, pauses self-heal, stops application writers, disables database events, takes a fresh authenticated encrypted backup and verifies it off host. Data fingerprints must match before writers resume. The previous image, Compose configuration and database guard remain available for rollback. The ordinary deployment guard still forbids replacing a running database image or storage; it recognizes C21 only after this coordinated activation.

The application is released separately through protected main CI and deploy.ps1. The media manifest contains 78 assets, including one disabled reserve asset; see ZHEKA_NOTIFICATION_MEDIA_2026-09-23.md.
