import {
  DEFAULT_OPERATOR_MOBILE_SECTION,
  OPERATOR_MOBILE_SECTIONS
} from './operator-board.mobile';

describe('operator mobile board configuration', () => {
  it('shows queue and sent statuses and resets to queue', () => {
    expect(OPERATOR_MOBILE_SECTIONS.map((section) => section.key)).toEqual(['queue', 'sent']);
    expect(DEFAULT_OPERATOR_MOBILE_SECTION).toBe('queue');
  });
});
