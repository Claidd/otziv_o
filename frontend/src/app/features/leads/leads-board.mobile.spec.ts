import { defaultLeadMobileBucket, LEAD_MOBILE_BUCKETS } from './leads-board.mobile';

describe('lead mobile board configuration', () => {
  it('keeps status tiles in the mobile workflow order', () => {
    expect(LEAD_MOBILE_BUCKETS.map((bucket) => bucket.key)).toEqual([
      'newLeads',
      'toWork',
      'inWork',
      'send',
      'archive',
      'all'
    ]);
  });

  it('resets privileged users to all and other users to the first visible queue', () => {
    const nonAdminBuckets = LEAD_MOBILE_BUCKETS.filter((bucket) => bucket.key !== 'all');

    expect(defaultLeadMobileBucket(true, LEAD_MOBILE_BUCKETS)).toBe('all');
    expect(defaultLeadMobileBucket(false, nonAdminBuckets)).toBe('newLeads');
  });
});
