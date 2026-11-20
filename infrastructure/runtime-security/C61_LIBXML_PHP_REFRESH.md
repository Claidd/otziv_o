# C61: libxml2 and PHP package refresh

The 20 November main CI run `44809230872` detected newly published fixed
vulnerabilities in the accepted Nginx C59 and phpMyAdmin C53 images. Nginx needs
Alpine 3.24 `libxml2` 2.13.9-r3 (CVE-2026-86144). phpMyAdmin also needs PHP
8.3.37-r0 and the matching installed PHP extensions (including the critical
CVE-2026-115560, CVE-2026-126714, CVE-2026-129289 and CVE-2026-129496 findings).

The two C61 recipes derive from the exact accepted image digests and install
only those pinned packages from the signed official Alpine repositories. They
compare all other installed package versions and the protected application,
configuration, entrypoint, permission and ownership inventories before/after.
The separately versioned uploadprogress extension remains unchanged.

Build checks load all Nginx modules and check its configuration; PHP checks the
fixed runtime version, Apache configuration and retained application dependency.
Activation also compares the complete scanner package inventory with the
accepted parent, permitting only these replacements. Existing musl protections,
immutable-image/receipt checks and zero raw HIGH/CRITICAL requirements remain.
No advisory suppression or risk acceptance is introduced.

Local builds passed and the targeted activation/manifest regression suite passed
118 tests. Registry publication, anonymous download and exact-digest security
evidence are required before changing deployment pins. Publication alone does
not deploy or activate either image.
