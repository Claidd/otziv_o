import { safeHttpsExternalUrl } from './external-navigation';

describe('safeHttpsExternalUrl', () => {
  it('allows credential-free HTTPS links', () => {
    expect(safeHttpsExternalUrl(' https://2gis.ru/irkutsk/firm/1 ')).toBe('https://2gis.ru/irkutsk/firm/1');
  });

  it('rejects active, insecure and credential-bearing targets', () => {
    expect(safeHttpsExternalUrl('javascript:alert(1)')).toBeNull();
    expect(safeHttpsExternalUrl('http://2gis.ru/irkutsk')).toBeNull();
    expect(safeHttpsExternalUrl('https://user:pass@example.com/')).toBeNull();
    expect(safeHttpsExternalUrl('https://example.com/%0d%0aLocation:https://evil.example')).toBeNull();
    expect(safeHttpsExternalUrl('/manager/orders/1/2')).toBeNull();
  });
});
