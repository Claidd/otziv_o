import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const originalName = 'codex-tmp/manual-task-integration-followup.patch';
const argumentIndex = process.argv.findIndex(value => value.replaceAll('\\', '/') === originalName);
if (argumentIndex >= 0) {
  const originalPath = path.resolve(root, originalName);
  const generatedPath = path.resolve(root, 'codex-tmp/manual-task-integration-followup.generated.patch');
  let patch = fs.readFileSync(originalPath, 'utf8').replaceAll('\r\n', '\n');
  const replacements = [
    [
      "@@\n     private final ContractorPaymentTargetAccessPolicy contractorPaymentTargetAccessPolicy;\n+    private final ManualPaymentTaskReceiptIntegrationService taskReceiptIntegrationService;",
      "@@\n     private final ContractorActualPaymentAttributionFlowPolicy actualPaymentAttributionFlowPolicy;\n+    private final ManualPaymentTaskReceiptIntegrationService taskReceiptIntegrationService;"
    ],
    [
      "@@\n         ensureBadReviewTasksForItems(changedItems);\n+        taskReceiptIntegrationService.release(invoice, \"Общий счет переведен в статус Не оплачено\");\n         changedItems.forEach(item -> item.setUnpaid(true));\n",
      ""
    ]
  ];
  for (const [before, after] of replacements) {
    if (!patch.includes(before)) throw new Error(`Expected follow-up fragment was not found: ${before.slice(0, 80)}`);
    patch = patch.replace(before, after);
  }
  fs.writeFileSync(generatedPath, patch, 'utf8');
  process.argv[argumentIndex] = path.relative(root, generatedPath);
}

const source = fs.readFileSync(new URL('./apply-custom-patches-v3.mjs', import.meta.url), 'utf8');
const oldCode = "  if (expected.length === 0) throw new Error(`Empty context: ${label}`);";
const newCode = "  if (expected.length === 0) {\n    if (cursor <= 0) throw new Error(`Unsafe empty context: ${label}`);\n    return cursor;\n  }";
if (!source.includes(oldCode)) throw new Error('Expected v3 source fragment was not found');
await import(`data:text/javascript;base64,${Buffer.from(source.replace(oldCode, newCode)).toString('base64')}`);
