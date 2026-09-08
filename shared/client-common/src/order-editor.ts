import { validateWire } from './wire-schema';
import {
  normalizedOrderEditorNumbers,
  orderEditorOptionalFields,
  orderEditorSchemas,
  type OrderEditPayload
} from './order-editor.generated';

export { managerOrderEditPath, ORDER_EDITOR_CONTRACT_VERSION } from './order-editor.generated';
export type { OrderEditPayload, OrderEditResponse, OptionResponse } from './order-editor.generated';

/** Validate the boundary without introducing another HTTP/auth transport. */
export function decodeOrderEditPayload(value: unknown): OrderEditPayload {
  validateWire(value, orderEditorSchemas.OrderEditResponse, orderEditorSchemas, orderEditorOptionalFields, 'OrderEditResponse', 'Invalid order editor response');
  const payload = { ...(value as OrderEditPayload) };
  for (const field of normalizedOrderEditorNumbers) {
    if (payload[field] == null) delete payload[field];
  }
  // Missing optional permission is denied. Unknown status strings are preserved for display;
  // capabilities, never a guessed status, authorize the editor actions.
  payload.canCancelPayment ??= false;
  return payload;
}
