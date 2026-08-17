import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const originalName = 'codex-tmp/manual-task-integration-followup.patch';
const argumentIndex = process.argv.findIndex(value => value.replaceAll('\\', '/') === originalName);
if (argumentIndex >= 0) {
  const originalPath = path.resolve(root, originalName);
  const generatedPath = path.resolve(root, 'codex-tmp/manual-task-integration-followup.generated.patch');
  const oldBlock = "@@\n     private final ContractorPaymentTargetAccessPolicy contractorPaymentTargetAccessPolicy;\n+    private final ManualPaymentTaskReceiptIntegrationService taskReceiptIntegrationService;";
  const newBlock = "@@\n     private final ContractorActualPaymentAttributionFlowPolicy actualPaymentAttributionFlowPolicy;\n+    private final ManualPaymentTaskReceiptIntegrationService taskReceiptIntegrationService;";
  const sourcePatch = fs.readFileSync(originalPath, 'utf8').replaceAll('\r\n', '\n');
  if (!sourcePatch.includes(oldBlock)) throw new Error('Expected CommonBillingService dependency hunk was not found');
  fs.writeFileSync(generatedPath, sourcePatch.replace(oldBlock, newBlock), 'utf8');
  process.argv[argumentIndex] = path.relative(root, generatedPath);
}

const source = fs.readFileSync(new URL('./apply-custom-patches-v3.mjs', import.meta.url), 'utf8');
const oldCode = "  if (expected.length === 0) throw new Error(`Empty context: ${label}`);";
const newCode = "  if (expected.length === 0) {\n    if (cursor <= 0) throw new Error(`Unsafe empty context: ${label}`);\n    return cursor;\n  }";
if (!source.includes(oldCode)) throw new Error('Expected v3 source fragment was not found');
await import(`data:text/javascript;base64,${Buffer.from(source.replace(oldCode, newCode)).toString('base64')}`);
