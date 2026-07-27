import { describe, expect, it } from 'vitest';
import { shouldImmediatelyCloseRiskControlCard } from './manager-control-risk-close';

describe('shouldImmediatelyCloseRiskControlCard', () => {
  it('closes a verified risk immediately for an admin or owner', () => {
    expect(shouldImmediatelyCloseRiskControlCard(true, 'VERIFIED', 'RESOLVED')).toBe(true);
  });

  it('does not turn non-final or non-privileged actions into a quick close', () => {
    expect(shouldImmediatelyCloseRiskControlCard(false, 'VERIFIED', 'RESOLVED')).toBe(false);
    expect(shouldImmediatelyCloseRiskControlCard(true, 'EXPLANATION_REQUESTED', 'OPEN')).toBe(false);
  });
});
