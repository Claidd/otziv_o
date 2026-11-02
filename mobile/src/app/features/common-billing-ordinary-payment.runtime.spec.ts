import { of } from 'rxjs';
import { CommonBillingPage } from './common-billing.page';
import { CommonBillingAdminPage } from './common-billing-admin.page';
import type { OrderItem } from '../core/api.service';

describe('ordinary payment for a selected cancelled paper part',()=>{
  const order={id:28293} as OrderItem;
  for(const ordinary of [true,false]) {
    it(`manager page uses ${ordinary?'individual':'common'} payment instructions`,async()=>{
      const prepare=vi.fn(()=>of({copyText:'ordinary instructions',url:'https://example.test/pay'}));
      const copyText=vi.fn();
      const page={invoiceOrderForCard:()=>({ordinaryAfterPaperCancel:ordinary}),summary:()=>({publicUrl:'https://example.test/common'}),
        orderPaymentApi:{createManagerOrderPaymentLink:prepare},copyText} as unknown as CommonBillingPage;
      await CommonBillingPage.prototype.copyOrderText.call(page,order,'payment');
      expect(copyText.mock.calls[0][0]).toBe(ordinary?'ordinary instructions':'https://example.test/common');
      expect(prepare).toHaveBeenCalledTimes(ordinary?1:0);
    });
    it(`owner page uses ${ordinary?'individual':'common'} payment instructions`,async()=>{
      const prepare=vi.fn(()=>of({copyText:'ordinary instructions',url:'https://example.test/pay'}));
      const copyText=vi.fn();
      const page={details:()=>({orders:[{orderId:28293,ordinaryAfterPaperCancel:ordinary}]}),currentInvoice:()=>({publicUrl:'https://example.test/common'}),
        orderPaymentApi:{createManagerOrderPaymentLink:prepare},copyText} as unknown as CommonBillingAdminPage;
      await CommonBillingAdminPage.prototype.copyOrderText.call(page,order,'payment');
      expect(copyText.mock.calls[0][0]).toBe(ordinary?'ordinary instructions':'https://example.test/common');
      expect(prepare).toHaveBeenCalledTimes(ordinary?1:0);
    });
  }
});
