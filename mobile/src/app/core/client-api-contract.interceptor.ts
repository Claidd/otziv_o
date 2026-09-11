import { HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { defer, from, map, switchMap } from 'rxjs';

/** The generated SDK checks JSON within Angular's existing auth/native transport chain. */
export const clientApiContractInterceptor: HttpInterceptorFn = (request, next) => {
  if (request.responseType !== 'json' || !/\/api\/(?:manager|common-billing|admin\/(?:payments|manager-control|manager-daily-summary)|payments\/public|cabinet\/(?:payment-profile|manual-payment-tasks))(?:\/|\?|$)/.test(request.url)) return next(request);
  // Keep the full schema catalog out of startup/authentication chunks. No retry or caching of requests.
  return defer(() => from(import('@otziv/client-common/client-api'))).pipe(switchMap(contract => {
    const match = contract.findClientOperation(request.method, request.url);
    if (!match) return next(request);
    contract.validateClientRequest(match.operation, request.body);
    const urlParams = new URL(request.urlWithParams, 'http://client.invalid').searchParams;
    contract.validateClientParameters(match.operation, match.path, (location, name) => location === 'header'
      ? request.headers.getAll(name) : urlParams.has(name) ? urlParams.getAll(name) : null);
    return next(request).pipe(map(event => {
      if (event instanceof HttpResponse && match.operation.response) contract.validateClientJson(event.body, match.operation.response, 'response');
      return event;
    }));
  }));
};
