import {
  CONTRACTOR_PAYMENT_SOURCE_FILTER_OPTIONS,
  contractorPaymentSourceLabel
} from './users-admin.component';

describe('contractor payment source presentation', () => {
  it('offers actual payment as an explicit journal filter', () => {
    expect(CONTRACTOR_PAYMENT_SOURCE_FILTER_OPTIONS).toEqual(expect.arrayContaining([
      expect.objectContaining({
        value: 'ACTUAL_PAYMENT',
        label: 'Фактическое поступление'
      })
    ]));
  });

  it('does not label actual payment as a direct settlement', () => {
    expect(contractorPaymentSourceLabel('ACTUAL_PAYMENT')).toBe('Фактическое поступление');
    expect(contractorPaymentSourceLabel('DIRECT_SETTLEMENT')).toBe('Прямой перевод');
  });
});
