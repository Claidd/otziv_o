import '@angular/compiler';
import { shouldShowAdminLayoutLoginControls } from './admin-layout.component';

describe('AdminLayout public review capability login controls', () => {
  it('hides login controls on the exact capability route regardless of token transport', () => {
    expect(shouldShowAdminLayoutLoginControls('/review/c')).toBe(false);
    expect(shouldShowAdminLayoutLoginControls('/review/c/')).toBe(false);
    expect(shouldShowAdminLayoutLoginControls('/review/c?capability=secret')).toBe(false);
    expect(shouldShowAdminLayoutLoginControls('/review/c#rc1_secret')).toBe(false);
  });

  it('keeps login controls on every other route', () => {
    expect(shouldShowAdminLayoutLoginControls('/review/editReviews/review-id')).toBe(true);
    expect(shouldShowAdminLayoutLoginControls('/review/c/extra')).toBe(true);
    expect(shouldShowAdminLayoutLoginControls('/pay/payment-token')).toBe(true);
    expect(shouldShowAdminLayoutLoginControls('/')).toBe(true);
  });
});
