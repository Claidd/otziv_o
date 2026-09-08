# C12 Git-history metadata classification

The same three published JSON blobs produced zero directory findings and 55 Git-history findings with pinned Gitleaks 8.30.1. All 55 are public identifiers: eight published OCI revision fields and 47 upstream Git commit references, comprising 11 distinct values. The published revision fields match the retained registry configuration. The remaining values appear in exact upstream reference URLs or their corresponding descriptions in the preserved reports. No new upstream Git object was fetched and no advisory was adjudicated.

The detector's keyword prefilter applies to an entire fragment. The added Git hunk contains a distant public package keyword that enables the detector's legacy bare 40-hex alternative throughout the hunk; directory input uses approximately 100 KB chunks. This explains the difference without changing either scanning mode.

The correction appends three AND blocks for one detector and three exact paths. Their 37 escaped, anchored patterns match complete reviewed UTF-8 lines, including field names and values. All earlier configuration bytes remain intact. There is no whole-file exemption or broad hash pattern.

The actual engine now reports zero in both modes. All 36 causal controls passed across the three paths and both modes: the baseline detects the original field, the exact candidate accepts it, and changed values, other fields, different paths and adjacent independent credentials remain blocked. The first mapper attempt left three findings because Windows default decoding changed Unicode descriptions; that failure is retained and corrected by explicit UTF-8 decoding.

The [structured verification](../infrastructure/runtime-security/C12_HISTORY_METADATA_VERIFICATION_2026-09-08.json) records digests, counts and limits. Raw findings remain in protected local evidence. This result does not grant vulnerability risk acceptance or production approval.
