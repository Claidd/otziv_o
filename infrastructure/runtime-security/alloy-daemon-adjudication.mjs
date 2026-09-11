import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { gunzipSync } from 'node:zlib';
import { exactProduct, labelsOf, inspectReviewedGoBinary } from './go-binary-inspection.mjs';

const C15_CONFIG = 'sha256:8084b7ee093d159c974d88ec56aad2a72f4bb939f974176759ff9233d9b58dfd';
const PROOFS = new Map([
  ['sha256:7f5b0079e8c6b13cd2a767cdeb001430bdcf0db400afbaf925285d1ae29dd084', {
    directory: 'alloy-daemon', binary: '2c21e2c85e1e88c88b3335d39c84ae5414232514aeaaea02457fef54f4c5e3dc' }],
  [C15_CONFIG, { directory: 'alloy-daemon-c15', binary: '6535f200bc605f313eae5deba4e912d35ff7236f3dd066639d8d52d8eb01e07a' }]
]);
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const CVES = ['CVE-2026-41567', 'CVE-2026-42306'];
const DAEMON = ['github.com/docker/docker/daemon', 'github.com/moby/moby/daemon', 'github.com/moby/moby/v2/daemon'];
const isDaemon = name => DAEMON.some(prefix => name === prefix || name.startsWith(prefix + '/') || name.startsWith(prefix + '.'));
const json = bytes => JSON.parse(bytes.toString('utf8'));

export function validateAlloyProof(review, files, now = new Date()) {
  assert.equal(review.schema, 'otziv-alloy-daemon-review-v1', 'alloy_review_schema');
  assert.equal(review.status, 'REVIEWED_NOT_AFFECTED', 'alloy_review_unapproved');
  assert.equal(review.validUntil, '2027-01-01T00:00:00Z', 'alloy_review_expiry_changed');
  assert.ok(Number.isFinite(now.getTime()) && now >= new Date(review.reviewedAt) && now < new Date(review.validUntil), 'alloy_review_expired');
  assert.deepEqual(review.decisions.map(item => item.cve), CVES, 'alloy_review_scope_changed');
  assert.ok(review.decisions.every(item => item.affectedPackage === DAEMON[0]), 'alloy_review_package_changed');
  assert.equal(review.binary.path, 'usr/bin/alloy', 'alloy_review_target_changed');
  const identity = PROOFS.get(review.imageConfigId);
  assert.ok(identity, 'alloy_review_image_changed');
  assert.equal(review.binary.sha256, identity.binary, 'alloy_review_binary_changed');
  assert.equal(review.module.name, 'github.com/docker/docker', 'alloy_review_module_changed');
  assert.equal(review.module.version, 'v28.5.2+incompatible', 'alloy_review_version_changed');
  assert.equal(review.module.purl, 'pkg:golang/github.com/docker/docker@v28.5.2%2Bincompatible', 'alloy_review_purl_changed');
  const checked = name => {
    const entry = review.proofFiles.find(item => item.path === name), bytes = files.get(name);
    assert.ok(entry && bytes && entry.bytes === bytes.length && sha256(bytes) === entry.sha256, 'alloy_proof_hash_mismatch');
    return bytes;
  };
  assert.equal(new Set(review.proofFiles.map(item => item.path)).size, review.proofFiles.length, 'alloy_duplicate_proof');
  for (const entry of review.proofFiles) checked(entry.path);
  const analysis = json(checked('closure-analysis.json'));
  assert.equal(analysis.publishedBinarySha256, review.binary.sha256, 'alloy_closure_binary_mismatch');
  assert.ok(analysis.reproducedBinaryByteIdentical && analysis.moduleProvenanceByteIdentical && analysis.buildInfoByteIdenticalIgnoringFilename, 'alloy_reproduction_missing');
  const actualTarget = json(checked('exact-target.json'));
  assert.equal(actualTarget.target, review.binary.path, 'alloy_target_receipt_mismatch');
  assert.equal(actualTarget.binarySha256, review.binary.sha256, 'alloy_target_binary_mismatch');
  assert.equal(actualTarget.reference, review.reference, 'alloy_target_image_mismatch');
  assert.equal(actualTarget.inspectedBinaryExecuted, false, 'alloy_target_executed');
  const graphBytes = gunzipSync(checked('import-closure.json.gz'), { maxOutputLength: 16 * 1024 * 1024 });
  assert.equal(sha256(graphBytes), analysis.importGraphSha256, 'alloy_graph_hash_mismatch');
  const graph = json(graphBytes), packages = new Set(graph.packages);
  assert.equal(graph.schema, 'otziv-go-import-closure-v1', 'alloy_graph_schema');
  assert.equal(packages.size, 5199, 'alloy_graph_package_count');
  assert.equal(graph.packages.length, packages.size, 'alloy_graph_duplicate_package');
  assert.equal(graph.edges.length, 53420, 'alloy_graph_edge_count');
  assert.equal(graph.entrypoint, 'github.com/grafana/alloy/otel_engine', 'alloy_graph_entrypoint');
  assert.equal(graph.rawGoListSha256, analysis.rawGoListSha256, 'alloy_graph_raw_hash');
  assert.equal(sha256(gunzipSync(checked('packages.raw.jsons.gz'), { maxOutputLength: 128 * 1024 * 1024 })), graph.rawGoListSha256, 'alloy_raw_packages_hash');
  assert.deepEqual(graph.errors, [], 'alloy_graph_errors');
  assert.ok(packages.has('github.com/docker/docker/client') && packages.has('github.com/google/cadvisor/container/docker'), 'alloy_graph_positive_control_missing');
  assert.ok(![...packages].some(isDaemon), 'alloy_daemon_package_present');
  const adjacency = new Map([...packages].map(name => [name, []]));
  for (const edge of graph.edges) {
    assert.ok(Array.isArray(edge) && edge.length === 2 && packages.has(edge[0]) && (packages.has(edge[1]) || edge[1] === 'C'), 'alloy_graph_unresolved_import');
    if (edge[1] !== 'C') adjacency.get(edge[0]).push(edge[1]);
  }
  const reached = new Set(), pending = [graph.entrypoint];
  while (pending.length) {
    const next = pending.pop();
    if (reached.has(next)) continue;
    reached.add(next); pending.push(...adjacency.get(next));
  }
  assert.equal(reached.size, packages.size, 'alloy_graph_incomplete_closure');
  const functionsBytes = gunzipSync(checked('linked-functions.raw.json.gz'), { maxOutputLength: 64 * 1024 * 1024 });
  const functions = json(functionsBytes), published = json(checked('published-binary.json'));
  assert.equal(sha256(functionsBytes), published.linkedFunctionsSha256, 'alloy_functions_raw_hash');
  assert.equal(functions.binarySha256, review.binary.sha256, 'alloy_functions_binary');
  assert.equal(functions.functionCount, 418393, 'alloy_function_count');
  assert.equal(functions.functions.length, functions.functionCount, 'alloy_functions_incomplete');
  assert.equal(functions.functions.filter(name => name.startsWith('github.com/docker/docker/')).length, 474, 'alloy_functions_positive_control');
  assert.ok(!functions.functions.some(isDaemon), 'alloy_daemon_function_present');
  // Source closure covers inlined code; the function table alone would not.
  const go = json(checked('GO-2026-5746.json'));
  assert.equal(go.id, 'GO-2026-5746', 'alloy_primary_go_id');
  assert.ok(go.aliases.includes(CVES[0]), 'alloy_primary_go_cve');
  assert.deepEqual(go.affected.filter(item => item.package.name === review.module.name).map(item => item.ecosystem_specific.imports),
    [[{ path: DAEMON[0], symbols: ['Daemon.containerExtractToDir'] }]], 'alloy_primary_go_scope');
  const advisory = json(checked('GHSA-rg2x-37c3-w2rh.json'));
  assert.equal(advisory.cve_id, CVES[1], 'alloy_primary_advisory_cve');
  assert.equal(advisory.ghsa_id, 'GHSA-rg2x-37c3-w2rh', 'alloy_primary_advisory_id');
  assert.ok(advisory.vulnerabilities.some(item => item.package.name === DAEMON[0]), 'alloy_primary_advisory_scope');
  const independent = json(checked('independent-review.json'));
  assert.equal(independent.schema, 'otziv-independent-alloy-review-v1', 'alloy_independent_review_schema');
  assert.equal(independent.result, 'PASS_EXACT_ARTIFACT_TWO_CVE_NON_AFFECTED_SCOPE', 'alloy_independent_review_missing');
  assert.equal(independent.checks.length, 56, 'alloy_independent_review_incomplete');
  assert.equal(independent.binarySha256, review.binary.sha256, 'alloy_independent_review_binary');
  if (review.imageConfigId === C15_CONFIG) {
    assert.equal(independent.verificationMode, 'AUTOMATED_SECOND_PARSER', 'alloy_independent_review_mode');
    checked('independent-verifier.py');
  }
  checked('independent-review.md');
  return { packageCount: packages.size, importEdges: graph.edges.length, linkedFunctions: functions.functionCount,
    affectedPackagesPresent: 0, affectedFunctionsPresent: 0 };
}

export async function loadAlloyProof(now = new Date(), imageConfigId) {
  const identity = PROOFS.get(imageConfigId) || PROOFS.values().next().value;
  const proofRoot = new URL('./adjudications/' + identity.directory + '/', import.meta.url);
  const bytes = await readFile(new URL('review.json', proofRoot)), review = json(bytes), files = new Map();
  for (const entry of review.proofFiles) {
    assert.match(entry.path, /^[a-zA-Z0-9][a-zA-Z0-9._-]*$/, 'alloy_proof_path_invalid');
    files.set(entry.path, await readFile(new URL(entry.path, proofRoot)));
  }
  const closure = validateAlloyProof(review, files, now);
  return { review, files, closure, reviewSha256: sha256(bytes) };
}

export function matchingAlloyFindings(report, review) {
  if (report.ArtifactType !== 'container_image' || report.Metadata?.ImageID !== review.imageConfigId || !exactProduct(labelsOf(report), review)) return [];
  const decisions = [];
  for (const [resultIndex, result] of (report.Results || []).entries()) {
    if (result.Type !== 'gobinary' || result.Target !== review.binary.path) continue;
    for (const [findingIndex, finding] of (result.Vulnerabilities || []).entries()) {
      if (!CVES.includes(finding.VulnerabilityID) || finding.PkgName !== review.module.name ||
          finding.InstalledVersion !== review.module.version || finding.PkgIdentifier?.PURL !== review.module.purl) continue;
      decisions.push({ resultIndex, findingIndex, cve: finding.VulnerabilityID, target: result.Target,
        package: finding.PkgName, version: finding.InstalledVersion, state: 'not_affected', justification: 'vulnerable_code_not_present',
        fixedHighOrCritical: ['HIGH', 'CRITICAL'].includes(finding.Severity) && Boolean(finding.FixedVersion),
        unfixedHighOrCritical: ['HIGH', 'CRITICAL'].includes(finding.Severity) && !finding.FixedVersion });
    }
  }
  // Duplicate/ambiguous reports are not eligible for a subtraction.
  if (new Set(decisions.map(item => item.cve)).size !== decisions.length) return [];
  return decisions;
}

export async function adjudicateAlloyImage(report, reportBytes, immutableImageId, scratch) {
  const base = { schema: 'otziv-alloy-adjudication-v1', rawReportSha256: sha256(reportBytes),
    imageConfigId: report.Metadata?.ImageID, immutableImageId, rawReportModified: false, decisions: [] };
  if (labelsOf(report)['org.opencontainers.image.source'] !== 'https://github.com/grafana/alloy') return { ...base, status: 'NOT_APPLICABLE' };
  try {
    const { review, reviewSha256, closure } = await loadAlloyProof(new Date(), report.Metadata?.ImageID);
    const decisions = matchingAlloyFindings(report, review);
    if (!decisions.length) return { ...base, status: 'NOT_APPLICABLE' };
    const observed = await inspectReviewedGoBinary(report, immutableImageId, scratch, review);
    return { ...base, status: 'EXACT_BINARY_AFFECTED_CODE_ABSENT', reviewSha256, validUntil: review.validUntil,
      ...observed, closure, module: review.module, decisions };
  } catch (error) {
    return { ...base, status: 'REJECTED', reason: /^(?:alloy|adjudication)_[a-z_]+$/.test(error.message) ? error.message : 'alloy_proof_validation_failed' };
  }
}

export function effectiveAlloySummary(summary, adjudication) {
  const decisions = adjudication?.status === 'EXACT_BINARY_AFFECTED_CODE_ABSENT' ? adjudication.decisions : [];
  assert.ok(Array.isArray(decisions) && decisions.length <= 2 && new Set(decisions.map(item => item.cve)).size === decisions.length &&
    decisions.every(item => CVES.includes(item.cve) && item.state === 'not_affected' && item.justification === 'vulnerable_code_not_present'), 'alloy_summary_invalid_decisions');
  const fixed = decisions.filter(item => item.fixedHighOrCritical).length;
  const unfixed = decisions.filter(item => item.unfixedHighOrCritical).length;
  const effectiveFixed = (summary.effectiveBlockingFixedHighOrCritical ?? summary.blockingFixedHighOrCritical) - fixed;
  const effectiveUnfixed = summary.unfixedHighOrCritical - unfixed;
  assert.ok(effectiveFixed >= 0 && effectiveUnfixed >= 0, 'alloy_summary_count_invalid');
  return { ...summary, adjudicatedAbsentFixedHighOrCritical: fixed, adjudicatedAbsentUnfixedHighOrCritical: unfixed,
    effectiveBlockingFixedHighOrCritical: effectiveFixed, effectiveUnfixedHighOrCritical: effectiveUnfixed,
    unresolvedRiskReview: effectiveUnfixed ? 'REQUIRED' : 'NONE',
    result: effectiveFixed || adjudication?.status === 'REJECTED' ? 'FAIL' : 'PASS' };
}
