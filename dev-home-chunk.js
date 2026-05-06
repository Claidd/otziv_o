import { injectQuery as __vite__injectQuery } from "/@vite/client";import { createHotContext as __vite__createHotContext } from "/@vite/client";import.meta.hot = __vite__createHotContext("/chunk-UEWSHXOD.js?devcss=5");import {
  CabinetBarChartComponent,
  CabinetLineChartComponent,
  cabinetDailyBarChartFrom,
  cabinetYearlyLineChartFrom
} from "/chunk-4OUHATLO.js";
import {
  ToastService,
  toast_service_exports
} from "/chunk-7A3QCK3N.js";
import {
  AdminLayoutComponent,
  CABINET_NAVIGATION_LINKS,
  CabinetApi,
  cabinet_api_exports,
  visibleCabinetNavigationLinks
} from "/chunk-LF4YTF6J.js";
import {
  AuthService,
  auth_service_exports
} from "/chunk-3JMPKEEV.js";
import {
  __export,
  appEnvironment
} from "/chunk-3YO46ZPR.js";

// src/app/features/home/home.component.ts
import { Component as Component2, computed, signal } from "/@fs/D:/Java/otziv/frontend/.angular/cache/21.2.9/frontend/vite/deps/@angular_core.js?v=99d20ee0";
import { FormsModule } from "/@fs/D:/Java/otziv/frontend/.angular/cache/21.2.9/frontend/vite/deps/@angular_forms.js?v=99d20ee0";
import { RouterLink as RouterLink2 } from "/@fs/D:/Java/otziv/frontend/.angular/cache/21.2.9/frontend/vite/deps/@angular_router.js?v=99d20ee0";

// src/app/shared/cabinet-navigation.component.ts
import { Component, Input } from "/@fs/D:/Java/otziv/frontend/.angular/cache/21.2.9/frontend/vite/deps/@angular_core.js?v=99d20ee0";
import { RouterLink } from "/@fs/D:/Java/otziv/frontend/.angular/cache/21.2.9/frontend/vite/deps/@angular_router.js?v=99d20ee0";
import * as i0 from "/@fs/D:/Java/otziv/frontend/.angular/cache/21.2.9/frontend/vite/deps/@angular_core.js?v=99d20ee0";
var _forTrack0 = ($index, $item) => $item.label;
function CabinetNavigationComponent_Conditional_1_For_2_Conditional_0_Template(rf, ctx) {
  if (rf & 1) {
    i0.\u0275\u0275elementStart(0, "a", 3)(1, "span", 4);
    i0.\u0275\u0275text(2);
    i0.\u0275\u0275elementEnd();
    i0.\u0275\u0275elementStart(3, "strong");
    i0.\u0275\u0275text(4);
    i0.\u0275\u0275elementEnd();
    i0.\u0275\u0275elementStart(5, "small");
    i0.\u0275\u0275text(6);
    i0.\u0275\u0275elementEnd()();
  }
  if (rf & 2) {
    const link_r1 = i0.\u0275\u0275nextContext().$implicit;
    const ctx_r1 = i0.\u0275\u0275nextContext(2);
    i0.\u0275\u0275classProp("active", ctx_r1.active === link_r1.active);
    i0.\u0275\u0275property("routerLink", link_r1.routerLink);
    i0.\u0275\u0275advance(2);
    i0.\u0275\u0275textInterpolate(link_r1.icon);
    i0.\u0275\u0275advance(2);
    i0.\u0275\u0275textInterpolate(link_r1.label);
    i0.\u0275\u0275advance(2);
    i0.\u0275\u0275textInterpolate(link_r1.description);
  }
}
function CabinetNavigationComponent_Conditional_1_For_2_Conditional_1_Template(rf, ctx) {
  if (rf & 1) {
    i0.\u0275\u0275elementStart(0, "a", 5)(1, "span", 4);
    i0.\u0275\u0275text(2);
    i0.\u0275\u0275elementEnd();
    i0.\u0275\u0275elementStart(3, "strong");
    i0.\u0275\u0275text(4);
    i0.\u0275\u0275elementEnd();
    i0.\u0275\u0275elementStart(5, "small");
    i0.\u0275\u0275text(6);
    i0.\u0275\u0275elementEnd()();
  }
  if (rf & 2) {
    const link_r1 = i0.\u0275\u0275nextContext().$implicit;
    const ctx_r1 = i0.\u0275\u0275nextContext(2);
    i0.\u0275\u0275classProp("active", ctx_r1.active === link_r1.active);
    i0.\u0275\u0275property("href", link_r1.href, i0.\u0275\u0275sanitizeUrl);
    i0.\u0275\u0275advance(2);
    i0.\u0275\u0275textInterpolate(link_r1.icon);
    i0.\u0275\u0275advance(2);
    i0.\u0275\u0275textInterpolate(link_r1.label);
    i0.\u0275\u0275advance(2);
    i0.\u0275\u0275textInterpolate(link_r1.description);
  }
}
function CabinetNavigationComponent_Conditional_1_For_2_Template(rf, ctx) {
  if (rf & 1) {
    i0.\u0275\u0275conditionalCreate(0, CabinetNavigationComponent_Conditional_1_For_2_Conditional_0_Template, 7, 6, "a", 1)(1, CabinetNavigationComponent_Conditional_1_For_2_Conditional_1_Template, 7, 6, "a", 2);
  }
  if (rf & 2) {
    const link_r1 = ctx.$implicit;
    i0.\u0275\u0275conditional(link_r1.routerLink ? 0 : 1);
  }
}
function CabinetNavigationComponent_Conditional_1_Template(rf, ctx) {
  if (rf & 1) {
    i0.\u0275\u0275elementStart(0, "nav", 0);
    i0.\u0275\u0275repeaterCreate(1, CabinetNavigationComponent_Conditional_1_For_2_Template, 2, 1, null, null, _forTrack0);
    i0.\u0275\u0275elementEnd();
  }
  if (rf & 2) {
    i0.\u0275\u0275nextContext();
    const navigationLinks_r3 = i0.\u0275\u0275readContextLet(0);
    i0.\u0275\u0275advance();
    i0.\u0275\u0275repeater(navigationLinks_r3);
  }
}
var CabinetNavigationComponent = class _CabinetNavigationComponent {
  roles = [];
  active = "";
  links = CABINET_NAVIGATION_LINKS;
  visibleNavigationLinks() {
    return visibleCabinetNavigationLinks(this.roles, this.links);
  }
  static \u0275fac = function CabinetNavigationComponent_Factory(__ngFactoryType__) {
    return new (__ngFactoryType__ || _CabinetNavigationComponent)();
  };
  static \u0275cmp = /* @__PURE__ */ i0.\u0275\u0275defineComponent({ type: _CabinetNavigationComponent, selectors: [["app-cabinet-navigation"]], inputs: { roles: "roles", active: "active", links: "links" }, decls: 2, vars: 2, consts: [["aria-label", "\u0420\u0430\u0437\u0434\u0435\u043B\u044B \u043A\u0430\u0431\u0438\u043D\u0435\u0442\u0430", 1, "cabinet-nav-block"], [1, "cabinet-nav-card", 3, "routerLink", "active"], [1, "cabinet-nav-card", 3, "href", "active"], [1, "cabinet-nav-card", 3, "routerLink"], [1, "material-icons-sharp"], [1, "cabinet-nav-card", 3, "href"]], template: function CabinetNavigationComponent_Template(rf, ctx) {
    if (rf & 1) {
      i0.\u0275\u0275declareLet(0);
      i0.\u0275\u0275conditionalCreate(1, CabinetNavigationComponent_Conditional_1_Template, 3, 0, "nav", 0);
    }
    if (rf & 2) {
      const navigationLinks_r4 = i0.\u0275\u0275storeLet(ctx.visibleNavigationLinks());
      i0.\u0275\u0275advance();
      i0.\u0275\u0275conditional(navigationLinks_r4.length ? 1 : -1);
    }
  }, dependencies: [RouterLink], styles: ["\n[_nghost-%COMP%] {\n  display: block;\n  margin-top: 1.05rem;\n}\n.cabinet-nav-block[_ngcontent-%COMP%] {\n  display: grid;\n  grid-template-columns: repeat(4, minmax(0, 1fr));\n  gap: 0.9rem;\n}\n.cabinet-nav-card[_ngcontent-%COMP%] {\n  display: grid;\n  min-height: 7.1rem;\n  align-content: start;\n  gap: 0.35rem;\n  border: 1px solid rgba(103, 116, 131, 0.18);\n  border-radius: 0.5rem;\n  padding: 1rem;\n  color: var(--otziv-dark);\n  background: var(--otziv-white);\n  box-shadow: var(--otziv-shadow);\n  text-decoration: none;\n  transition:\n    border-color 0.2s ease,\n    color 0.2s ease,\n    transform 0.2s ease;\n}\n.cabinet-nav-card[_ngcontent-%COMP%]:hover, \n.cabinet-nav-card.active[_ngcontent-%COMP%] {\n  border-color: rgba(108, 155, 207, 0.55);\n  color: var(--otziv-primary);\n  transform: translateY(-1px);\n}\n.cabinet-nav-card.active[_ngcontent-%COMP%] {\n  background: var(--otziv-light);\n}\n.cabinet-nav-card[_ngcontent-%COMP%]    > span[_ngcontent-%COMP%] {\n  color: var(--otziv-primary);\n  font-size: 2rem;\n}\n.cabinet-nav-card[_ngcontent-%COMP%]   strong[_ngcontent-%COMP%], \n.cabinet-nav-card[_ngcontent-%COMP%]   small[_ngcontent-%COMP%] {\n  min-width: 0;\n  overflow-wrap: anywhere;\n  line-height: 1.25;\n}\n.cabinet-nav-card[_ngcontent-%COMP%]   small[_ngcontent-%COMP%] {\n  color: var(--otziv-info);\n  font-weight: 700;\n}\n@media (max-width: 1120px) {\n  .cabinet-nav-block[_ngcontent-%COMP%] {\n    grid-template-columns: repeat(2, minmax(0, 1fr));\n  }\n}\n@media (max-width: 640px) {\n  .cabinet-nav-block[_ngcontent-%COMP%] {\n    grid-template-columns: 1fr;\n  }\n}\n/*# sourceMappingURL=cabinet-navigation.component.css.map */"] });
};
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && i0.\u0275setClassMetadata(CabinetNavigationComponent, [{
    type: Component,
    args: [{ selector: "app-cabinet-navigation", imports: [RouterLink], template: `
    @let navigationLinks = visibleNavigationLinks();
    @if (navigationLinks.length) {
      <nav class="cabinet-nav-block" aria-label="\u0420\u0430\u0437\u0434\u0435\u043B\u044B \u043A\u0430\u0431\u0438\u043D\u0435\u0442\u0430">
        @for (link of navigationLinks; track link.label) {
          @if (link.routerLink) {
            <a class="cabinet-nav-card" [routerLink]="link.routerLink" [class.active]="active === link.active">
              <span class="material-icons-sharp">{{ link.icon }}</span>
              <strong>{{ link.label }}</strong>
              <small>{{ link.description }}</small>
            </a>
          } @else {
            <a class="cabinet-nav-card" [href]="link.href" [class.active]="active === link.active">
              <span class="material-icons-sharp">{{ link.icon }}</span>
              <strong>{{ link.label }}</strong>
              <small>{{ link.description }}</small>
            </a>
          }
        }
      </nav>
    }
  `, styles: ["/* angular:styles/component:scss;2933792da9b1c0495c15ff364dc8a225b586d99628e425e3020d21f572fd7560;D:/Java/otziv/frontend/src/app/shared/cabinet-navigation.component.ts */\n:host {\n  display: block;\n  margin-top: 1.05rem;\n}\n.cabinet-nav-block {\n  display: grid;\n  grid-template-columns: repeat(4, minmax(0, 1fr));\n  gap: 0.9rem;\n}\n.cabinet-nav-card {\n  display: grid;\n  min-height: 7.1rem;\n  align-content: start;\n  gap: 0.35rem;\n  border: 1px solid rgba(103, 116, 131, 0.18);\n  border-radius: 0.5rem;\n  padding: 1rem;\n  color: var(--otziv-dark);\n  background: var(--otziv-white);\n  box-shadow: var(--otziv-shadow);\n  text-decoration: none;\n  transition:\n    border-color 0.2s ease,\n    color 0.2s ease,\n    transform 0.2s ease;\n}\n.cabinet-nav-card:hover,\n.cabinet-nav-card.active {\n  border-color: rgba(108, 155, 207, 0.55);\n  color: var(--otziv-primary);\n  transform: translateY(-1px);\n}\n.cabinet-nav-card.active {\n  background: var(--otziv-light);\n}\n.cabinet-nav-card > span {\n  color: var(--otziv-primary);\n  font-size: 2rem;\n}\n.cabinet-nav-card strong,\n.cabinet-nav-card small {\n  min-width: 0;\n  overflow-wrap: anywhere;\n  line-height: 1.25;\n}\n.cabinet-nav-card small {\n  color: var(--otziv-info);\n  font-weight: 700;\n}\n@media (max-width: 1120px) {\n  .cabinet-nav-block {\n    grid-template-columns: repeat(2, minmax(0, 1fr));\n  }\n}\n@media (max-width: 640px) {\n  .cabinet-nav-block {\n    grid-template-columns: 1fr;\n  }\n}\n/*# sourceMappingURL=cabinet-navigation.component.css.map */\n"] }]
  }], null, { roles: [{
    type: Input
  }], active: [{
    type: Input
  }], links: [{
    type: Input
  }] });
})();
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && i0.\u0275setClassDebugInfo(CabinetNavigationComponent, { className: "CabinetNavigationComponent", filePath: "src/app/shared/cabinet-navigation.component.ts", lineNumber: 102 });
})();
(() => {
  const id = "src%2Fapp%2Fshared%2Fcabinet-navigation.component.ts%40CabinetNavigationComponent";
  function CabinetNavigationComponent_HmrLoad(t) {
    import(
      /* @vite-ignore */
      __vite__injectQuery(i0.\u0275\u0275getReplaceMetadataURL(id, t, import.meta.url), 'import')
    ).then((m) => m.default && i0.\u0275\u0275replaceMetadata(CabinetNavigationComponent, m.default, [i0], [RouterLink, Component, Input], import.meta, id));
  }
  (typeof ngDevMode === "undefined" || ngDevMode) && CabinetNavigationComponent_HmrLoad(Date.now());
  (typeof ngDevMode === "undefined" || ngDevMode) && (import.meta.hot && import.meta.hot.on("angular:component-update", (d) => d.id === id && CabinetNavigationComponent_HmrLoad(d.timestamp)));
})();

// src/app/features/home/home.component.ts
import * as i04 from "/@fs/D:/Java/otziv/frontend/.angular/cache/21.2.9/frontend/vite/deps/@angular_core.js?v=99d20ee0";

// src/app/core/current-user.api.ts
var current_user_api_exports = {};
__export(current_user_api_exports, {
  CurrentUserApi: () => CurrentUserApi
});
import { Injectable } from "/@fs/D:/Java/otziv/frontend/.angular/cache/21.2.9/frontend/vite/deps/@angular_core.js?v=99d20ee0";
import * as i02 from "/@fs/D:/Java/otziv/frontend/.angular/cache/21.2.9/frontend/vite/deps/@angular_core.js?v=99d20ee0";
import * as i1 from "/@fs/D:/Java/otziv/frontend/.angular/cache/21.2.9/frontend/vite/deps/@angular_common_http.js?v=99d20ee0";
var CurrentUserApi = class _CurrentUserApi {
  http;
  constructor(http) {
    this.http = http;
  }
  getMe() {
    return this.http.get(`${appEnvironment.apiBaseUrl}/api/me`);
  }
  static \u0275fac = function CurrentUserApi_Factory(__ngFactoryType__) {
    return new (__ngFactoryType__ || _CurrentUserApi)(i02.\u0275\u0275inject(i1.HttpClient));
  };
  static \u0275prov = /* @__PURE__ */ i02.\u0275\u0275defineInjectable({ token: _CurrentUserApi, factory: _CurrentUserApi.\u0275fac, providedIn: "root" });
};
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && i02.\u0275setClassMetadata(CurrentUserApi, [{
    type: Injectable,
    args: [{ providedIn: "root" }]
  }], () => [{ type: i1.HttpClient }], null);
})();

// src/app/core/system-health.api.ts
var system_health_api_exports = {};
__export(system_health_api_exports, {
  SystemHealthApi: () => SystemHealthApi
});
import { Injectable as Injectable2 } from "/@fs/D:/Java/otziv/frontend/.angular/cache/21.2.9/frontend/vite/deps/@angular_core.js?v=99d20ee0";
import * as i03 from "/@fs/D:/Java/otziv/frontend/.angular/cache/21.2.9/frontend/vite/deps/@angular_core.js?v=99d20ee0";
import * as i12 from "/@fs/D:/Java/otziv/frontend/.angular/cache/21.2.9/frontend/vite/deps/@angular_common_http.js?v=99d20ee0";
var SystemHealthApi = class _SystemHealthApi {
  http;
  constructor(http) {
    this.http = http;
  }
  getHealth() {
    return this.http.get(`${appEnvironment.apiBaseUrl}/actuator/health`);
  }
  static \u0275fac = function SystemHealthApi_Factory(__ngFactoryType__) {
    return new (__ngFactoryType__ || _SystemHealthApi)(i03.\u0275\u0275inject(i12.HttpClient));
  };
  static \u0275prov = /* @__PURE__ */ i03.\u0275\u0275defineInjectable({ token: _SystemHealthApi, factory: _SystemHealthApi.\u0275fac, providedIn: "root" });
};
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && i03.\u0275setClassMetadata(SystemHealthApi, [{
    type: Injectable2,
    args: [{ providedIn: "root" }]
  }], () => [{ type: i12.HttpClient }], null);
})();

// src/app/features/home/home.component.ts
import * as i6 from "/@fs/D:/Java/otziv/frontend/.angular/cache/21.2.9/frontend/vite/deps/@angular_router.js?v=99d20ee0";
import * as i7 from "/@fs/D:/Java/otziv/frontend/.angular/cache/21.2.9/frontend/vite/deps/@angular_forms.js?v=99d20ee0";
var _forTrack02 = ($index, $item) => $item.label;
function HomeComponent_Conditional_1_Template(rf, ctx) {
  if (rf & 1) {
    const _r1 = i04.\u0275\u0275getCurrentView();
    i04.\u0275\u0275elementStart(0, "section", 1)(1, "div", 4)(2, "h2");
    i04.\u0275\u0275text(3);
    i04.\u0275\u0275elementEnd();
    i04.\u0275\u0275elementStart(4, "p");
    i04.\u0275\u0275text(5);
    i04.\u0275\u0275elementEnd()();
    i04.\u0275\u0275elementStart(6, "div", 5)(7, "button", 6);
    i04.\u0275\u0275listener("click", function HomeComponent_Conditional_1_Template_button_click_7_listener() {
      i04.\u0275\u0275restoreView(_r1);
      const ctx_r1 = i04.\u0275\u0275nextContext();
      return i04.\u0275\u0275resetView(ctx_r1.refreshCabinet());
    });
    i04.\u0275\u0275elementStart(8, "span", 7);
    i04.\u0275\u0275text(9, "refresh");
    i04.\u0275\u0275elementEnd();
    i04.\u0275\u0275text(10);
    i04.\u0275\u0275elementEnd();
    i04.\u0275\u0275elementStart(11, "button", 8);
    i04.\u0275\u0275listener("click", function HomeComponent_Conditional_1_Template_button_click_11_listener() {
      i04.\u0275\u0275restoreView(_r1);
      const ctx_r1 = i04.\u0275\u0275nextContext();
      return i04.\u0275\u0275resetView(ctx_r1.loadHealth(true));
    });
    i04.\u0275\u0275elementStart(12, "span", 7);
    i04.\u0275\u0275text(13, "monitor_heart");
    i04.\u0275\u0275elementEnd();
    i04.\u0275\u0275text(14);
    i04.\u0275\u0275elementEnd();
    i04.\u0275\u0275elementStart(15, "label", 9)(16, "input", 10);
    i04.\u0275\u0275listener("ngModelChange", function HomeComponent_Conditional_1_Template_input_ngModelChange_16_listener($event) {
      i04.\u0275\u0275restoreView(_r1);
      const ctx_r1 = i04.\u0275\u0275nextContext();
      return i04.\u0275\u0275resetView(ctx_r1.selectCabinetDate($event));
    });
    i04.\u0275\u0275elementEnd()()()();
  }
  if (rf & 2) {
    let tmp_1_0;
    let tmp_2_0;
    const ctx_r1 = i04.\u0275\u0275nextContext();
    i04.\u0275\u0275advance(3);
    i04.\u0275\u0275textInterpolate(((tmp_1_0 = ctx_r1.cabinet()) == null ? null : tmp_1_0.workerZp == null ? null : tmp_1_0.workerZp.fio) || "\u041B\u0438\u0447\u043D\u044B\u0439 \u043A\u0430\u0431\u0438\u043D\u0435\u0442");
    i04.\u0275\u0275advance(2);
    i04.\u0275\u0275textInterpolate2(" ", ((tmp_2_0 = ctx_r1.cabinet()) == null ? null : tmp_2_0.user == null ? null : tmp_2_0.user.role) || ctx_r1.businessRoles()[0] || "USER", " \xB7 ", ((tmp_2_0 = ctx_r1.cabinet()) == null ? null : tmp_2_0.user == null ? null : tmp_2_0.user.username) || ((tmp_2_0 = ctx_r1.auth.tokenParsed()) == null ? null : tmp_2_0["preferred_username"]) || "user", " ");
    i04.\u0275\u0275advance(2);
    i04.\u0275\u0275property("disabled", ctx_r1.cabinetLoading());
    i04.\u0275\u0275advance(3);
    i04.\u0275\u0275textInterpolate1(" ", ctx_r1.cabinetLoading() ? "\u041E\u0431\u043D\u043E\u0432\u043B\u044F\u044E" : "\u041E\u0431\u043D\u043E\u0432\u0438\u0442\u044C \u043A\u0430\u0431\u0438\u043D\u0435\u0442", " ");
    i04.\u0275\u0275advance();
    i04.\u0275\u0275property("disabled", ctx_r1.healthLoading());
    i04.\u0275\u0275advance(3);
    i04.\u0275\u0275textInterpolate1(" ", ctx_r1.healthLoading() ? "\u041F\u0440\u043E\u0432\u0435\u0440\u044F\u044E" : "\u041F\u0440\u043E\u0432\u0435\u0440\u0438\u0442\u044C backend", " ");
    i04.\u0275\u0275advance(2);
    i04.\u0275\u0275property("ngModel", ctx_r1.cabinetDate());
  }
}
function HomeComponent_Conditional_2_Template(rf, ctx) {
  if (rf & 1) {
    i04.\u0275\u0275elementStart(0, "p", 2);
    i04.\u0275\u0275text(1);
    i04.\u0275\u0275elementEnd();
  }
  if (rf & 2) {
    const ctx_r1 = i04.\u0275\u0275nextContext();
    i04.\u0275\u0275advance();
    i04.\u0275\u0275textInterpolate(ctx_r1.auth.error());
  }
}
function HomeComponent_Conditional_3_Template(rf, ctx) {
  if (rf & 1) {
    i04.\u0275\u0275elementStart(0, "p", 2);
    i04.\u0275\u0275text(1);
    i04.\u0275\u0275elementEnd();
  }
  if (rf & 2) {
    const ctx_r1 = i04.\u0275\u0275nextContext();
    i04.\u0275\u0275advance();
    i04.\u0275\u0275textInterpolate(ctx_r1.error());
  }
}
function HomeComponent_Conditional_4_Template(rf, ctx) {
  if (rf & 1) {
    i04.\u0275\u0275elementStart(0, "p", 2);
    i04.\u0275\u0275text(1);
    i04.\u0275\u0275elementEnd();
  }
  if (rf & 2) {
    const ctx_r1 = i04.\u0275\u0275nextContext();
    i04.\u0275\u0275advance();
    i04.\u0275\u0275textInterpolate(ctx_r1.healthError());
  }
}
function HomeComponent_Conditional_5_Template(rf, ctx) {
  if (rf & 1) {
    i04.\u0275\u0275elementStart(0, "p", 2);
    i04.\u0275\u0275text(1);
    i04.\u0275\u0275elementEnd();
  }
  if (rf & 2) {
    const ctx_r1 = i04.\u0275\u0275nextContext();
    i04.\u0275\u0275advance();
    i04.\u0275\u0275textInterpolate(ctx_r1.cabinetError());
  }
}
function HomeComponent_Conditional_6_Conditional_0_For_2_Template(rf, ctx) {
  if (rf & 1) {
    i04.\u0275\u0275elementStart(0, "article", 27)(1, "div")(2, "h3");
    i04.\u0275\u0275text(3);
    i04.\u0275\u0275elementEnd();
    i04.\u0275\u0275elementStart(4, "h1");
    i04.\u0275\u0275text(5);
    i04.\u0275\u0275elementEnd()();
    i04.\u0275\u0275elementStart(6, "span");
    i04.\u0275\u0275text(7);
    i04.\u0275\u0275elementEnd()();
  }
  if (rf & 2) {
    const metric_r3 = ctx.$implicit;
    const ctx_r1 = i04.\u0275\u0275nextContext(3);
    i04.\u0275\u0275classMap(ctx_r1.tone(metric_r3.percent));
    i04.\u0275\u0275advance(3);
    i04.\u0275\u0275textInterpolate(metric_r3.label);
    i04.\u0275\u0275advance(2);
    i04.\u0275\u0275textInterpolate(metric_r3.value);
    i04.\u0275\u0275advance(2);
    i04.\u0275\u0275textInterpolate1("", metric_r3.percent, "%");
  }
}
function HomeComponent_Conditional_6_Conditional_0_Template(rf, ctx) {
  if (rf & 1) {
    i04.\u0275\u0275elementStart(0, "section", 22);
    i04.\u0275\u0275repeaterCreate(1, HomeComponent_Conditional_6_Conditional_0_For_2_Template, 8, 5, "article", 23, _forTrack02);
    i04.\u0275\u0275elementEnd();
    i04.\u0275\u0275elementStart(3, "section", 24);
    i04.\u0275\u0275element(4, "app-cabinet-bar-chart", 25)(5, "app-cabinet-line-chart", 26);
    i04.\u0275\u0275elementEnd();
  }
  if (rf & 2) {
    let tmp_4_0;
    let tmp_7_0;
    let tmp_11_0;
    const ctx_r1 = i04.\u0275\u0275nextContext(2);
    i04.\u0275\u0275advance();
    i04.\u0275\u0275repeater(ctx_r1.cabinetMetrics());
    i04.\u0275\u0275advance(3);
    i04.\u0275\u0275property("headingLevel", 2)("subtitle", ((tmp_4_0 = ctx_r1.cabinet()) == null ? null : tmp_4_0.date) || "")("legendLabel", ctx_r1.selectedMonthLabel())("legendColor", "#9a7bd9")("chart", ctx_r1.dailyChartFrom((tmp_7_0 = ctx_r1.cabinet()) == null ? null : tmp_7_0.workerZp == null ? null : tmp_7_0.workerZp.zpPayMap))("compact", true)("salary", true);
    i04.\u0275\u0275advance();
    i04.\u0275\u0275property("headingLevel", 2)("chart", ctx_r1.yearlyLineChartFrom((tmp_11_0 = ctx_r1.cabinet()) == null ? null : tmp_11_0.workerZp == null ? null : tmp_11_0.workerZp.zpPayMapMonth))("compact", true);
  }
}
function HomeComponent_Conditional_6_Conditional_1_Template(rf, ctx) {
  if (rf & 1) {
    i04.\u0275\u0275elementStart(0, "section", 11)(1, "span", 7);
    i04.\u0275\u0275text(2, "hourglass_top");
    i04.\u0275\u0275elementEnd();
    i04.\u0275\u0275elementStart(3, "h2");
    i04.\u0275\u0275text(4, "\u0417\u0430\u0433\u0440\u0443\u0436\u0430\u044E \u043B\u0438\u0447\u043D\u044B\u0439 \u043A\u0430\u0431\u0438\u043D\u0435\u0442");
    i04.\u0275\u0275elementEnd()();
  }
}
function HomeComponent_Conditional_6_For_40_Template(rf, ctx) {
  if (rf & 1) {
    i04.\u0275\u0275elementStart(0, "span");
    i04.\u0275\u0275text(1);
    i04.\u0275\u0275elementEnd();
  }
  if (rf & 2) {
    const role_r4 = ctx.$implicit;
    i04.\u0275\u0275advance();
    i04.\u0275\u0275textInterpolate(role_r4);
  }
}
function HomeComponent_Conditional_6_ForEmpty_41_Template(rf, ctx) {
  if (rf & 1) {
    i04.\u0275\u0275elementStart(0, "span");
    i04.\u0275\u0275text(1, "\u041D\u0435\u0442 \u0431\u0438\u0437\u043D\u0435\u0441-\u0440\u043E\u043B\u0435\u0439");
    i04.\u0275\u0275elementEnd();
  }
}
function HomeComponent_Conditional_6_For_53_Conditional_0_Template(rf, ctx) {
  if (rf & 1) {
    i04.\u0275\u0275elementStart(0, "a", 28)(1, "span", 7);
    i04.\u0275\u0275text(2);
    i04.\u0275\u0275elementEnd();
    i04.\u0275\u0275elementStart(3, "strong");
    i04.\u0275\u0275text(4);
    i04.\u0275\u0275elementEnd();
    i04.\u0275\u0275elementStart(5, "small");
    i04.\u0275\u0275text(6);
    i04.\u0275\u0275elementEnd()();
  }
  if (rf & 2) {
    const action_r5 = i04.\u0275\u0275nextContext().$implicit;
    i04.\u0275\u0275property("routerLink", action_r5.routerLink);
    i04.\u0275\u0275advance(2);
    i04.\u0275\u0275textInterpolate(action_r5.icon);
    i04.\u0275\u0275advance(2);
    i04.\u0275\u0275textInterpolate(action_r5.label);
    i04.\u0275\u0275advance(2);
    i04.\u0275\u0275textInterpolate(action_r5.description);
  }
}
function HomeComponent_Conditional_6_For_53_Conditional_1_Template(rf, ctx) {
  if (rf & 1) {
    i04.\u0275\u0275elementStart(0, "a", 29)(1, "span", 7);
    i04.\u0275\u0275text(2);
    i04.\u0275\u0275elementEnd();
    i04.\u0275\u0275elementStart(3, "strong");
    i04.\u0275\u0275text(4);
    i04.\u0275\u0275elementEnd();
    i04.\u0275\u0275elementStart(5, "small");
    i04.\u0275\u0275text(6);
    i04.\u0275\u0275elementEnd()();
  }
  if (rf & 2) {
    const action_r5 = i04.\u0275\u0275nextContext().$implicit;
    i04.\u0275\u0275property("href", action_r5.href, i04.\u0275\u0275sanitizeUrl);
    i04.\u0275\u0275advance(2);
    i04.\u0275\u0275textInterpolate(action_r5.icon);
    i04.\u0275\u0275advance(2);
    i04.\u0275\u0275textInterpolate(action_r5.label);
    i04.\u0275\u0275advance(2);
    i04.\u0275\u0275textInterpolate(action_r5.description);
  }
}
function HomeComponent_Conditional_6_For_53_Template(rf, ctx) {
  if (rf & 1) {
    i04.\u0275\u0275conditionalCreate(0, HomeComponent_Conditional_6_For_53_Conditional_0_Template, 7, 4, "a", 28)(1, HomeComponent_Conditional_6_For_53_Conditional_1_Template, 7, 4, "a", 29);
  }
  if (rf & 2) {
    const action_r5 = ctx.$implicit;
    i04.\u0275\u0275conditional(action_r5.routerLink ? 0 : 1);
  }
}
function HomeComponent_Conditional_6_ForEmpty_54_Template(rf, ctx) {
  if (rf & 1) {
    i04.\u0275\u0275elementStart(0, "div", 21)(1, "span", 7);
    i04.\u0275\u0275text(2, "lock");
    i04.\u0275\u0275elementEnd();
    i04.\u0275\u0275elementStart(3, "p");
    i04.\u0275\u0275text(4, "\u0414\u043B\u044F \u0442\u0432\u043E\u0435\u0439 \u0440\u043E\u043B\u0438 \u043F\u043E\u043A\u0430 \u043D\u0435\u0442 \u0431\u044B\u0441\u0442\u0440\u044B\u0445 \u0434\u0435\u0439\u0441\u0442\u0432\u0438\u0439.");
    i04.\u0275\u0275elementEnd()();
  }
}
function HomeComponent_Conditional_6_Template(rf, ctx) {
  if (rf & 1) {
    i04.\u0275\u0275conditionalCreate(0, HomeComponent_Conditional_6_Conditional_0_Template, 6, 10)(1, HomeComponent_Conditional_6_Conditional_1_Template, 5, 0, "section", 11);
    i04.\u0275\u0275element(2, "app-cabinet-navigation", 12);
    i04.\u0275\u0275elementStart(3, "section", 13)(4, "article", 14)(5, "div", 15)(6, "span", 7);
    i04.\u0275\u0275text(7, "account_circle");
    i04.\u0275\u0275elementEnd();
    i04.\u0275\u0275elementStart(8, "div")(9, "p", 16);
    i04.\u0275\u0275text(10, "SESSION");
    i04.\u0275\u0275elementEnd();
    i04.\u0275\u0275elementStart(11, "h2");
    i04.\u0275\u0275text(12);
    i04.\u0275\u0275elementEnd()()();
    i04.\u0275\u0275elementStart(13, "dl")(14, "div")(15, "dt");
    i04.\u0275\u0275text(16, "Email");
    i04.\u0275\u0275elementEnd();
    i04.\u0275\u0275elementStart(17, "dd");
    i04.\u0275\u0275text(18);
    i04.\u0275\u0275elementEnd()();
    i04.\u0275\u0275elementStart(19, "div")(20, "dt");
    i04.\u0275\u0275text(21, "Backend");
    i04.\u0275\u0275elementEnd();
    i04.\u0275\u0275elementStart(22, "dd");
    i04.\u0275\u0275text(23);
    i04.\u0275\u0275elementEnd()();
    i04.\u0275\u0275elementStart(24, "div")(25, "dt");
    i04.\u0275\u0275text(26, "\u0422\u043E\u043A\u0435\u043D \u0434\u043E");
    i04.\u0275\u0275elementEnd();
    i04.\u0275\u0275elementStart(27, "dd");
    i04.\u0275\u0275text(28);
    i04.\u0275\u0275elementEnd()()()();
    i04.\u0275\u0275elementStart(29, "article", 17)(30, "div", 15)(31, "span", 7);
    i04.\u0275\u0275text(32, "admin_panel_settings");
    i04.\u0275\u0275elementEnd();
    i04.\u0275\u0275elementStart(33, "div")(34, "p", 16);
    i04.\u0275\u0275text(35, "ACCESS");
    i04.\u0275\u0275elementEnd();
    i04.\u0275\u0275elementStart(36, "h2");
    i04.\u0275\u0275text(37, "\u0420\u043E\u043B\u0438");
    i04.\u0275\u0275elementEnd()()();
    i04.\u0275\u0275elementStart(38, "div", 18);
    i04.\u0275\u0275repeaterCreate(39, HomeComponent_Conditional_6_For_40_Template, 2, 1, "span", null, i04.\u0275\u0275repeaterTrackByIdentity, false, HomeComponent_Conditional_6_ForEmpty_41_Template, 2, 0, "span");
    i04.\u0275\u0275elementEnd()()();
    i04.\u0275\u0275elementStart(42, "section", 19)(43, "div", 15)(44, "span", 7);
    i04.\u0275\u0275text(45, "apps");
    i04.\u0275\u0275elementEnd();
    i04.\u0275\u0275elementStart(46, "div")(47, "p", 16);
    i04.\u0275\u0275text(48, "SECTIONS");
    i04.\u0275\u0275elementEnd();
    i04.\u0275\u0275elementStart(49, "h2");
    i04.\u0275\u0275text(50, "\u0411\u044B\u0441\u0442\u0440\u044B\u0435 \u0434\u0435\u0439\u0441\u0442\u0432\u0438\u044F");
    i04.\u0275\u0275elementEnd()()();
    i04.\u0275\u0275elementStart(51, "div", 20);
    i04.\u0275\u0275repeaterCreate(52, HomeComponent_Conditional_6_For_53_Template, 2, 1, null, null, _forTrack02, false, HomeComponent_Conditional_6_ForEmpty_54_Template, 5, 0, "div", 21);
    i04.\u0275\u0275elementEnd()();
  }
  if (rf & 2) {
    let tmp_3_0;
    let tmp_4_0;
    let tmp_5_0;
    let tmp_6_0;
    const ctx_r1 = i04.\u0275\u0275nextContext();
    i04.\u0275\u0275conditional(ctx_r1.cabinet() ? 0 : ctx_r1.cabinetLoading() ? 1 : -1);
    i04.\u0275\u0275advance(2);
    i04.\u0275\u0275property("roles", ctx_r1.realmRoles());
    i04.\u0275\u0275advance(10);
    i04.\u0275\u0275textInterpolate(((tmp_3_0 = ctx_r1.me()) == null ? null : tmp_3_0.preferredUsername) || ((tmp_3_0 = ctx_r1.auth.tokenParsed()) == null ? null : tmp_3_0["preferred_username"]) || "\u041F\u043E\u043B\u044C\u0437\u043E\u0432\u0430\u0442\u0435\u043B\u044C");
    i04.\u0275\u0275advance(6);
    i04.\u0275\u0275textInterpolate(((tmp_4_0 = ctx_r1.me()) == null ? null : tmp_4_0.email) || "-");
    i04.\u0275\u0275advance(5);
    i04.\u0275\u0275textInterpolate(((tmp_5_0 = ctx_r1.health()) == null ? null : tmp_5_0.status) || (ctx_r1.healthLoading() ? "..." : "-"));
    i04.\u0275\u0275advance(5);
    i04.\u0275\u0275textInterpolate(((tmp_6_0 = ctx_r1.auth.expiresAt()) == null ? null : tmp_6_0.toLocaleTimeString()) || "-");
    i04.\u0275\u0275advance(11);
    i04.\u0275\u0275repeater(ctx_r1.businessRoles());
    i04.\u0275\u0275advance(13);
    i04.\u0275\u0275repeater(ctx_r1.visibleActions());
  }
}
function HomeComponent_Conditional_7_Template(rf, ctx) {
  if (rf & 1) {
    i04.\u0275\u0275elementStart(0, "section", 3);
    i04.\u0275\u0275element(1, "img", 30);
    i04.\u0275\u0275elementEnd();
  }
}
var MONTH_NAMES = [
  "\u042F\u043D\u0432\u0430\u0440\u044C",
  "\u0424\u0435\u0432\u0440\u0430\u043B\u044C",
  "\u041C\u0430\u0440\u0442",
  "\u0410\u043F\u0440\u0435\u043B\u044C",
  "\u041C\u0430\u0439",
  "\u0418\u044E\u043D\u044C",
  "\u0418\u044E\u043B\u044C",
  "\u0410\u0432\u0433\u0443\u0441\u0442",
  "\u0421\u0435\u043D\u0442\u044F\u0431\u0440\u044C",
  "\u041E\u043A\u0442\u044F\u0431\u0440\u044C",
  "\u041D\u043E\u044F\u0431\u0440\u044C",
  "\u0414\u0435\u043A\u0430\u0431\u0440\u044C"
];
var HomeComponent = class _HomeComponent {
  auth;
  currentUserApi;
  systemHealthApi;
  cabinetApi;
  toastService;
  router;
  me = signal(null, ...ngDevMode ? [{ debugName: "me" }] : (
    /* istanbul ignore next */
    []
  ));
  health = signal(null, ...ngDevMode ? [{ debugName: "health" }] : (
    /* istanbul ignore next */
    []
  ));
  cabinet = signal(null, ...ngDevMode ? [{ debugName: "cabinet" }] : (
    /* istanbul ignore next */
    []
  ));
  cabinetDate = signal(this.todayIso(), ...ngDevMode ? [{ debugName: "cabinetDate" }] : (
    /* istanbul ignore next */
    []
  ));
  loading = signal(false, ...ngDevMode ? [{ debugName: "loading" }] : (
    /* istanbul ignore next */
    []
  ));
  healthLoading = signal(false, ...ngDevMode ? [{ debugName: "healthLoading" }] : (
    /* istanbul ignore next */
    []
  ));
  cabinetLoading = signal(false, ...ngDevMode ? [{ debugName: "cabinetLoading" }] : (
    /* istanbul ignore next */
    []
  ));
  error = signal(null, ...ngDevMode ? [{ debugName: "error" }] : (
    /* istanbul ignore next */
    []
  ));
  healthError = signal(null, ...ngDevMode ? [{ debugName: "healthError" }] : (
    /* istanbul ignore next */
    []
  ));
  cabinetError = signal(null, ...ngDevMode ? [{ debugName: "cabinetError" }] : (
    /* istanbul ignore next */
    []
  ));
  actions = [
    {
      label: "\u041F\u043E\u043B\u044C\u0437\u043E\u0432\u0430\u0442\u0435\u043B\u0438",
      description: "Keycloak, \u0440\u043E\u043B\u0438 \u0438 \u0441\u0432\u044F\u0437\u0438 \u043A\u043E\u043C\u0430\u043D\u0434\u044B",
      icon: "group_add",
      roles: ["ADMIN", "OWNER"],
      routerLink: "/admin/users"
    },
    {
      label: "\u041B\u0438\u0434\u044B",
      description: "\u041D\u043E\u0432\u0430\u044F \u0440\u0430\u0431\u043E\u0447\u0430\u044F \u0434\u043E\u0441\u043A\u0430",
      icon: "notifications_active",
      roles: ["ADMIN", "OWNER", "MANAGER", "MARKETOLOG"],
      routerLink: "/leads"
    },
    {
      label: "\u041E\u043F\u0435\u0440\u0430\u0442\u043E\u0440",
      description: "\u041E\u043F\u0435\u0440\u0430\u0442\u043E\u0440\u044B \u0438 \u043E\u0431\u0440\u0430\u0431\u043E\u0442\u043A\u0430 \u0437\u0430\u044F\u0432\u043E\u043A",
      icon: "support_agent",
      roles: ["ADMIN", "OWNER", "OPERATOR"],
      routerLink: "/operator"
    },
    {
      label: "\u041C\u0435\u043D\u0435\u0434\u0436\u0435\u0440",
      description: "\u041A\u043E\u043C\u043F\u0430\u043D\u0438\u0438 \u0438 \u0437\u0430\u043A\u0430\u0437\u044B",
      icon: "groups",
      roles: ["ADMIN", "OWNER", "MANAGER"],
      routerLink: "/manager"
    },
    {
      label: "\u0421\u043F\u0435\u0446\u0438\u0430\u043B\u0438\u0441\u0442",
      description: "\u0410\u043A\u043A\u0430\u0443\u043D\u0442\u044B, \u043F\u0443\u0431\u043B\u0438\u043A\u0430\u0446\u0438\u0438 \u0438 \u0437\u0430\u0434\u0430\u0447\u0438",
      icon: "engineering",
      roles: ["ADMIN", "OWNER", "MANAGER", "WORKER"],
      routerLink: "/worker"
    },
    {
      label: "Grafana",
      description: "\u041C\u0435\u0442\u0440\u0438\u043A\u0438 \u0438 \u043D\u0430\u0431\u043B\u044E\u0434\u0430\u0435\u043C\u043E\u0441\u0442\u044C",
      icon: "monitoring",
      roles: ["ADMIN", "OWNER"],
      href: appEnvironment.metricsBaseUrl
    }
  ];
  realmRoles = computed(() => {
    return this.auth.tokenParsed()?.["realm_access"]?.["roles"] ?? [];
  }, ...ngDevMode ? [{ debugName: "realmRoles" }] : (
    /* istanbul ignore next */
    []
  ));
  businessRoles = computed(() => {
    const ignored = /* @__PURE__ */ new Set(["default-roles-otziv", "offline_access", "uma_authorization"]);
    return this.realmRoles().filter((role) => !ignored.has(role));
  }, ...ngDevMode ? [{ debugName: "businessRoles" }] : (
    /* istanbul ignore next */
    []
  ));
  visibleActions = computed(() => {
    if (!this.auth.authenticated()) {
      return [];
    }
    const roles = new Set(this.realmRoles());
    const canSeeAll = roles.has("ADMIN") || roles.has("OWNER");
    return this.actions.filter((action) => canSeeAll || action.roles.some((role) => roles.has(role)));
  }, ...ngDevMode ? [{ debugName: "visibleActions" }] : (
    /* istanbul ignore next */
    []
  ));
  cabinetMetrics = computed(() => {
    const workerZp = this.cabinet()?.workerZp;
    if (!workerZp) {
      return [];
    }
    return [
      { label: "\u0417\u0430 \u0432\u0447\u0435\u0440\u0430", value: this.money(workerZp.sum1Day), percent: workerZp.percent1Day },
      { label: "\u0417\u0430 \u043D\u0435\u0434\u0435\u043B\u044E", value: this.money(workerZp.sum1Week), percent: workerZp.percent1Week },
      { label: "\u0417\u0430 \u043C\u0435\u0441\u044F\u0446", value: this.money(workerZp.sum1Month), percent: workerZp.percent1Month },
      { label: "\u0417\u0430 \u0433\u043E\u0434", value: this.money(workerZp.sum1Year), percent: workerZp.percent1Year },
      { label: "\u0417\u0430\u043A\u0430\u0437\u043E\u0432 \u0437\u0430 \u043C\u0435\u0441\u044F\u0446", value: this.count(workerZp.sumOrders1Month), percent: workerZp.percent1MonthOrders },
      { label: "\u0417\u0430 \u043F\u0440\u043E\u0448\u043B\u044B\u0439 \u043C\u0435\u0441\u044F\u0446", value: this.count(workerZp.sumOrders2Month), percent: workerZp.percent2MonthOrders }
    ];
  }, ...ngDevMode ? [{ debugName: "cabinetMetrics" }] : (
    /* istanbul ignore next */
    []
  ));
  constructor(auth, currentUserApi, systemHealthApi, cabinetApi, toastService, router) {
    this.auth = auth;
    this.currentUserApi = currentUserApi;
    this.systemHealthApi = systemHealthApi;
    this.cabinetApi = cabinetApi;
    this.toastService = toastService;
    this.router = router;
    if (this.shouldOpenAnalyticsHome()) {
      void this.router.navigate(["/admin/analyse"]);
      return;
    }
    if (this.auth.isAuthenticated()) {
      this.loadCurrentUser();
      this.loadCabinet();
    }
    this.loadHealth();
  }
  login() {
    void this.auth.login("/");
  }
  logout() {
    void this.auth.logout();
  }
  loadCurrentUser() {
    this.loading.set(true);
    this.error.set(null);
    this.currentUserApi.getMe().subscribe({
      next: (user) => {
        this.me.set(user);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.message ?? "Request failed");
        this.loading.set(false);
      }
    });
  }
  loadHealth(showToast = false) {
    this.healthLoading.set(true);
    this.healthError.set(null);
    this.systemHealthApi.getHealth().subscribe({
      next: (health) => {
        this.health.set(health);
        this.healthLoading.set(false);
        if (showToast) {
          this.showHealthToast(health);
        }
      },
      error: (err) => {
        const message = err?.message ?? "Health check failed";
        this.healthError.set(message);
        this.healthLoading.set(false);
        if (showToast) {
          this.toastService.error("Backend \u043D\u0435\u0434\u043E\u0441\u0442\u0443\u043F\u0435\u043D", message);
        }
      }
    });
  }
  loadCabinet(forceRefresh = false) {
    this.cabinetLoading.set(true);
    this.cabinetError.set(null);
    this.cabinetApi.getProfile(this.cabinetDate(), { forceRefresh }).subscribe({
      next: (profile) => {
        this.cabinet.set(profile);
        this.cabinetLoading.set(false);
      },
      error: (err) => {
        this.cabinetError.set(err?.error?.message ?? err?.message ?? "Cabinet request failed");
        this.cabinetLoading.set(false);
      }
    });
  }
  refreshCabinet() {
    this.loadCabinet(true);
  }
  selectCabinetDate(date) {
    this.cabinetDate.set(date);
    this.loadCabinet(true);
  }
  hasActionLink(action) {
    return Boolean(action.routerLink || action.href);
  }
  imageUrl(imageId) {
    return this.cabinetApi.imageUrl(imageId);
  }
  profileImageUrl() {
    const imageId = this.cabinet()?.workerZp?.imageId || this.cabinet()?.user?.image;
    return imageId ? this.imageUrl(imageId) : null;
  }
  showHealthToast(health) {
    const status = health.status || "UNKNOWN";
    const message = `Actuator health: ${status}`;
    if (status.toUpperCase() === "UP") {
      this.toastService.success("Backend \u0440\u0430\u0431\u043E\u0442\u0430\u0435\u0442", message);
      return;
    }
    this.toastService.info("Backend \u043E\u0442\u0432\u0435\u0442\u0438\u043B", message);
  }
  shouldOpenAnalyticsHome() {
    return this.auth.isAuthenticated() && this.auth.hasAnyRealmRole(["ADMIN", "OWNER"]);
  }
  dailyChartFrom(map) {
    return cabinetDailyBarChartFrom(map, this.cabinetDate());
  }
  yearlyLineChartFrom(map) {
    return cabinetYearlyLineChartFrom(map, { fallbackYear: new Date(this.cabinetDate()).getFullYear() });
  }
  selectedMonthLabel() {
    const date = new Date(this.cabinetDate());
    return `\u041C\u0435\u0441\u044F\u0446: ${MONTH_NAMES[date.getMonth()] ?? MONTH_NAMES[0]}`;
  }
  tone(percent) {
    if (percent > 25) {
      return "green";
    }
    if (percent >= 0) {
      return "blue";
    }
    if (percent > -25) {
      return "yellow";
    }
    return "red";
  }
  money(value) {
    return `${new Intl.NumberFormat("ru-RU").format(value || 0)} \u0440\u0443\u0431.`;
  }
  count(value) {
    return `${new Intl.NumberFormat("ru-RU").format(value || 0)} \u0448\u0442.`;
  }
  todayIso() {
    return (/* @__PURE__ */ new Date()).toISOString().slice(0, 10);
  }
  static \u0275fac = function HomeComponent_Factory(__ngFactoryType__) {
    return new (__ngFactoryType__ || _HomeComponent)(i04.\u0275\u0275directiveInject(AuthService), i04.\u0275\u0275directiveInject(CurrentUserApi), i04.\u0275\u0275directiveInject(SystemHealthApi), i04.\u0275\u0275directiveInject(CabinetApi), i04.\u0275\u0275directiveInject(ToastService), i04.\u0275\u0275directiveInject(i6.Router));
  };
  static \u0275cmp = /* @__PURE__ */ i04.\u0275\u0275defineComponent({ type: _HomeComponent, selectors: [["app-home"]], decls: 8, vars: 8, consts: [["title", "\u041A\u043E\u043C\u043F\u0430\u043D\u0438\u044F \u041E!", "active", "dashboard", 3, "profileImageUrl", "profileImageAlt"], [1, "hero-card", "hero-card--authenticated"], [1, "error"], ["aria-label", "\u0420\u0430\u0431\u043E\u0447\u0435\u0435 \u043F\u0440\u043E\u0441\u0442\u0440\u0430\u043D\u0441\u0442\u0432\u043E \u041A\u043E\u043C\u043F\u0430\u043D\u0438\u044F \u041E!", 1, "panel", "start-illustration-panel"], [1, "hero-intro"], [1, "actions"], ["type", "button", 1, "secondary", "refresh-button", 3, "click", "disabled"], [1, "material-icons-sharp"], ["type", "button", 1, "secondary", "health-button", 3, "click", "disabled"], [1, "date-control"], ["type", "date", "aria-label", "\u0414\u0430\u0442\u0430", 3, "ngModelChange", "ngModel"], [1, "panel", "empty"], ["active", "dashboard", 3, "roles"], [1, "dashboard-grid"], [1, "panel", "profile-panel"], [1, "panel-title"], [1, "eyebrow"], [1, "panel", "roles-panel"], [1, "chip-list"], [1, "panel"], [1, "action-grid"], [1, "empty-inline"], [1, "cabinet-metrics"], [1, "metric-card", 3, "class"], [1, "chart-grid"], ["heading", "\u0417\u0430\u0440\u043F\u043B\u0430\u0442\u044B \u043F\u043E \u0434\u043D\u044F\u043C", 3, "headingLevel", "subtitle", "legendLabel", "legendColor", "chart", "compact", "salary"], ["heading", "\u0417\u0430\u0440\u043F\u043B\u0430\u0442\u044B \u043F\u043E \u043C\u0435\u0441\u044F\u0446\u0430\u043C", "subtitle", "\u0432\u0441\u0435 \u0433\u043E\u0434\u044B", "ariaLabel", "\u0417\u0430\u0440\u043F\u043B\u0430\u0442\u044B \u043F\u043E \u043C\u0435\u0441\u044F\u0446\u0430\u043C", 3, "headingLevel", "chart", "compact"], [1, "metric-card"], [1, "action-card", 3, "routerLink"], [1, "action-card", 3, "href"], ["src", "/assets/images/TacoCloud.png", "alt", "\u041A\u043E\u043C\u0430\u043D\u0434\u0430 \u0440\u0430\u0431\u043E\u0442\u0430\u0435\u0442 \u0441 \u043E\u0442\u0437\u044B\u0432\u0430\u043C\u0438 \u0438 \u043A\u043B\u0438\u0435\u043D\u0442\u0441\u043A\u0438\u043C\u0438 \u0437\u0430\u0434\u0430\u0447\u0430\u043C\u0438"]], template: function HomeComponent_Template(rf, ctx) {
    if (rf & 1) {
      i04.\u0275\u0275elementStart(0, "app-admin-layout", 0);
      i04.\u0275\u0275conditionalCreate(1, HomeComponent_Conditional_1_Template, 17, 8, "section", 1);
      i04.\u0275\u0275conditionalCreate(2, HomeComponent_Conditional_2_Template, 2, 1, "p", 2);
      i04.\u0275\u0275conditionalCreate(3, HomeComponent_Conditional_3_Template, 2, 1, "p", 2);
      i04.\u0275\u0275conditionalCreate(4, HomeComponent_Conditional_4_Template, 2, 1, "p", 2);
      i04.\u0275\u0275conditionalCreate(5, HomeComponent_Conditional_5_Template, 2, 1, "p", 2);
      i04.\u0275\u0275conditionalCreate(6, HomeComponent_Conditional_6_Template, 55, 8)(7, HomeComponent_Conditional_7_Template, 2, 0, "section", 3);
      i04.\u0275\u0275elementEnd();
    }
    if (rf & 2) {
      let tmp_1_0;
      i04.\u0275\u0275property("profileImageUrl", ctx.profileImageUrl())("profileImageAlt", ((tmp_1_0 = ctx.cabinet()) == null ? null : tmp_1_0.workerZp == null ? null : tmp_1_0.workerZp.fio) || "\u0424\u043E\u0442\u043E \u043F\u0440\u043E\u0444\u0438\u043B\u044F");
      i04.\u0275\u0275advance();
      i04.\u0275\u0275conditional(ctx.auth.authenticated() ? 1 : -1);
      i04.\u0275\u0275advance();
      i04.\u0275\u0275conditional(ctx.auth.error() ? 2 : -1);
      i04.\u0275\u0275advance();
      i04.\u0275\u0275conditional(ctx.error() ? 3 : -1);
      i04.\u0275\u0275advance();
      i04.\u0275\u0275conditional(ctx.healthError() ? 4 : -1);
      i04.\u0275\u0275advance();
      i04.\u0275\u0275conditional(ctx.cabinetError() ? 5 : -1);
      i04.\u0275\u0275advance();
      i04.\u0275\u0275conditional(ctx.auth.authenticated() ? 6 : 7);
    }
  }, dependencies: [
    AdminLayoutComponent,
    CabinetNavigationComponent,
    FormsModule,
    i7.\u0275NgNoValidate,
    i7.NgSelectOption,
    i7.\u0275NgSelectMultipleOption,
    i7.DefaultValueAccessor,
    i7.NumberValueAccessor,
    i7.RangeValueAccessor,
    i7.CheckboxControlValueAccessor,
    i7.SelectControlValueAccessor,
    i7.SelectMultipleControlValueAccessor,
    i7.RadioControlValueAccessor,
    i7.NgControlStatus,
    i7.NgControlStatusGroup,
    i7.RequiredValidator,
    i7.MinLengthValidator,
    i7.MaxLengthValidator,
    i7.PatternValidator,
    i7.CheckboxRequiredValidator,
    i7.EmailValidator,
    i7.MinValidator,
    i7.MaxValidator,
    i7.NgModel,
    i7.NgModelGroup,
    i7.NgForm,
    RouterLink2,
    CabinetBarChartComponent,
    CabinetLineChartComponent
  ], styles: ["\n[_nghost-%COMP%] {\n  display: block;\n}\n.hero-card[_ngcontent-%COMP%], \n.metric-card[_ngcontent-%COMP%], \n.panel[_ngcontent-%COMP%] {\n  border-radius: var(--otziv-card-radius);\n  background: var(--otziv-white);\n  box-shadow: var(--otziv-shadow);\n}\n.hero-card[_ngcontent-%COMP%] {\n  display: flex;\n  align-items: center;\n  justify-content: space-between;\n  gap: 1.4rem;\n  padding: var(--otziv-toolbar-padding);\n}\n.hero-card[_ngcontent-%COMP%]   h2[_ngcontent-%COMP%], \n.hero-card[_ngcontent-%COMP%]   p[_ngcontent-%COMP%], \n.metric-card[_ngcontent-%COMP%]   h1[_ngcontent-%COMP%], \n.metric-card[_ngcontent-%COMP%]   h3[_ngcontent-%COMP%], \n.panel[_ngcontent-%COMP%]   h2[_ngcontent-%COMP%], \n.panel[_ngcontent-%COMP%]   p[_ngcontent-%COMP%], \ndl[_ngcontent-%COMP%] {\n  margin: 0;\n}\n.hero-card[_ngcontent-%COMP%]   p[_ngcontent-%COMP%]:not(.eyebrow), \n.panel[_ngcontent-%COMP%]   p[_ngcontent-%COMP%], \ndt[_ngcontent-%COMP%], \nsmall[_ngcontent-%COMP%] {\n  color: var(--otziv-info);\n}\n.cabinet-metrics[_ngcontent-%COMP%], \n.dashboard-grid[_ngcontent-%COMP%], \n.action-grid[_ngcontent-%COMP%], \n.chart-grid[_ngcontent-%COMP%] {\n  display: grid;\n  gap: 1.05rem;\n  margin-top: 1.05rem;\n}\n.cabinet-metrics[_ngcontent-%COMP%] {\n  grid-template-columns: repeat(3, minmax(0, 1fr));\n}\n.dashboard-grid[_ngcontent-%COMP%] {\n  grid-template-columns: 1.2fr 0.8fr;\n}\n.chart-grid[_ngcontent-%COMP%] {\n  grid-template-columns: repeat(2, minmax(0, 1fr));\n  gap: 1rem;\n}\n.metric-card[_ngcontent-%COMP%] {\n  display: flex;\n  min-height: 7.45rem;\n  align-items: center;\n  justify-content: space-between;\n  padding: 1.25rem;\n}\n.metric-card[_ngcontent-%COMP%]   h3[_ngcontent-%COMP%] {\n  margin-bottom: 0.5rem;\n  font-size: 1rem;\n}\n.metric-card[_ngcontent-%COMP%]   h1[_ngcontent-%COMP%] {\n  overflow-wrap: anywhere;\n  font-size: 1.5rem;\n}\n.metric-card[_ngcontent-%COMP%]    > span[_ngcontent-%COMP%] {\n  display: grid;\n  width: 4.35rem;\n  height: 4.35rem;\n  place-items: center;\n  border: 8px solid currentColor;\n  border-radius: 50%;\n}\n.blue[_ngcontent-%COMP%] {\n  color: var(--otziv-primary);\n}\n.green[_ngcontent-%COMP%] {\n  color: var(--otziv-success);\n}\n.yellow[_ngcontent-%COMP%] {\n  color: #b28405;\n}\n.red[_ngcontent-%COMP%] {\n  color: var(--otziv-danger);\n}\n.date-control[_ngcontent-%COMP%] {\n  display: grid;\n  gap: 0.35rem;\n  min-width: 10.5rem;\n  color: var(--otziv-info);\n  font-weight: 800;\n}\n.date-control[_ngcontent-%COMP%]   input[_ngcontent-%COMP%] {\n  min-height: 2.35rem;\n  border: 1px solid rgba(103, 116, 131, 0.25);\n  border-radius: var(--otziv-small-radius);\n  padding: 0 0.75rem;\n  color: var(--otziv-dark);\n  background: var(--otziv-field-background);\n  font: inherit;\n}\n.hero-card--authenticated[_ngcontent-%COMP%]   .refresh-button[_ngcontent-%COMP%] {\n  order: 1;\n}\n.hero-card--authenticated[_ngcontent-%COMP%]   .health-button[_ngcontent-%COMP%] {\n  order: 2;\n}\n.hero-card--authenticated[_ngcontent-%COMP%]   .date-control[_ngcontent-%COMP%] {\n  order: 3;\n}\n.panel[_ngcontent-%COMP%] {\n  display: grid;\n  gap: 1rem;\n  margin-top: 1.6rem;\n  padding: var(--otziv-card-padding);\n}\n.dashboard-grid[_ngcontent-%COMP%]   .panel[_ngcontent-%COMP%] {\n  margin-top: 0;\n}\n.panel-title[_ngcontent-%COMP%] {\n  display: flex;\n  align-items: center;\n  gap: 0.9rem;\n}\n.panel-title[_ngcontent-%COMP%]    > span[_ngcontent-%COMP%] {\n  color: var(--otziv-primary);\n  font-size: 2.2rem;\n}\ndl[_ngcontent-%COMP%] {\n  display: grid;\n  gap: 0.8rem;\n}\ndd[_ngcontent-%COMP%] {\n  margin: 0.2rem 0 0;\n  overflow-wrap: anywhere;\n  font-weight: 800;\n}\n.chip-list[_ngcontent-%COMP%] {\n  display: flex;\n  flex-wrap: wrap;\n  gap: 0.7rem;\n}\n.chip-list[_ngcontent-%COMP%]   span[_ngcontent-%COMP%] {\n  border-radius: 999px;\n  padding: 0.55rem 0.8rem;\n  color: var(--otziv-primary);\n  background: var(--otziv-light);\n  font-weight: 800;\n}\n.action-grid[_ngcontent-%COMP%] {\n  grid-template-columns: repeat(3, minmax(0, 1fr));\n  margin-top: 0;\n}\n.action-card[_ngcontent-%COMP%] {\n  display: grid;\n  gap: 0.4rem;\n  border: 1px solid rgba(103, 116, 131, 0.18);\n  border-radius: 1.2rem;\n  padding: 1.2rem;\n  color: var(--otziv-dark);\n  background: var(--otziv-muted-surface);\n  text-decoration: none;\n}\n.action-card[_ngcontent-%COMP%]    > span[_ngcontent-%COMP%] {\n  color: var(--otziv-primary);\n  font-size: 2rem;\n}\n.empty[_ngcontent-%COMP%], \n.empty-inline[_ngcontent-%COMP%] {\n  place-items: center;\n  align-content: center;\n  color: var(--otziv-info);\n  text-align: center;\n}\n.empty[_ngcontent-%COMP%] {\n  min-height: 14rem;\n}\n.start-illustration-panel[_ngcontent-%COMP%] {\n  display: grid;\n  height: clamp(24rem, 100svh - 9.25rem, 47rem);\n  min-height: 0;\n  max-width: 92rem;\n  place-items: center;\n  overflow: hidden;\n  margin-inline: auto;\n  padding: clamp(0.65rem, 1.1vw, 1rem);\n}\n.start-illustration-panel[_ngcontent-%COMP%]   img[_ngcontent-%COMP%] {\n  display: block;\n  width: 100%;\n  height: 100%;\n  max-width: min(100%, 72rem);\n  max-height: min(100%, 45rem);\n  border-radius: 1.35rem;\n  object-fit: contain;\n  object-position: center;\n}\n.empty[_ngcontent-%COMP%]    > span[_ngcontent-%COMP%], \n.empty-inline[_ngcontent-%COMP%]    > span[_ngcontent-%COMP%] {\n  font-size: 3rem;\n}\n.error[_ngcontent-%COMP%] {\n  margin-top: 1rem;\n}\n@media (max-width: 1120px) {\n  .cabinet-metrics[_ngcontent-%COMP%], \n   .dashboard-grid[_ngcontent-%COMP%], \n   .action-grid[_ngcontent-%COMP%], \n   .chart-grid[_ngcontent-%COMP%] {\n    grid-template-columns: 1fr;\n  }\n  .hero-card[_ngcontent-%COMP%] {\n    align-items: flex-start;\n    flex-direction: column;\n  }\n  .start-illustration-panel[_ngcontent-%COMP%] {\n    height: clamp(15rem, 44svh, 26rem);\n  }\n}\n@media (min-width: 1121px) and (max-width: 1320px) {\n  .start-illustration-panel[_ngcontent-%COMP%] {\n    height: clamp(17rem, 46svh, 30rem);\n  }\n}\n@media (max-width: 860px) {\n  .start-illustration-panel[_ngcontent-%COMP%] {\n    height: clamp(13rem, 34svh, 19rem);\n    margin-top: 0.55rem;\n    border-radius: 1.35rem;\n    padding: clamp(1rem, 3.5vw, 1.35rem);\n  }\n  .start-illustration-panel[_ngcontent-%COMP%]   img[_ngcontent-%COMP%] {\n    max-width: min(92%, 31rem);\n    max-height: 100%;\n    object-fit: contain;\n    object-position: center;\n  }\n}\n@media (max-width: 760px) {\n  .hero-card--authenticated[_ngcontent-%COMP%]   .hero-intro[_ngcontent-%COMP%], \n   .hero-card--authenticated[_ngcontent-%COMP%]   .health-button[_ngcontent-%COMP%] {\n    display: none;\n  }\n  .hero-card--authenticated[_ngcontent-%COMP%] {\n    display: block;\n  }\n  .hero-card--authenticated[_ngcontent-%COMP%]   .actions[_ngcontent-%COMP%] {\n    display: grid;\n    grid-template-columns: minmax(0, 1fr) 2.75rem;\n    align-items: end;\n    justify-content: stretch;\n    width: 100%;\n  }\n  .hero-card--authenticated[_ngcontent-%COMP%]   .date-control[_ngcontent-%COMP%] {\n    order: 1;\n    width: auto;\n  }\n  .hero-card--authenticated[_ngcontent-%COMP%]   .refresh-button[_ngcontent-%COMP%] {\n    order: 2;\n  }\n  .hero-card--authenticated[_ngcontent-%COMP%]   button.secondary[_ngcontent-%COMP%] {\n    width: 2.75rem;\n    min-width: 2.75rem;\n    padding: 0;\n    font-size: 0;\n  }\n  .hero-card--authenticated[_ngcontent-%COMP%]   button.secondary[_ngcontent-%COMP%]   .material-icons-sharp[_ngcontent-%COMP%] {\n    font-size: 1.35rem;\n  }\n  .start-illustration-panel[_ngcontent-%COMP%] {\n    height: clamp(11.5rem, 28svh, 16rem);\n    padding: clamp(1.05rem, 4vw, 1.45rem);\n  }\n  .start-illustration-panel[_ngcontent-%COMP%]   img[_ngcontent-%COMP%] {\n    max-width: min(88%, 24rem);\n    max-height: calc(100% - 0.35rem);\n    object-fit: contain;\n    object-position: center;\n    transform: none;\n  }\n}\n@media (min-width: 1321px) and (max-height: 820px) {\n  .start-illustration-panel[_ngcontent-%COMP%] {\n    height: clamp(22rem, 100svh - 9rem, 40rem);\n  }\n}\n@media (max-width: 640px) {\n  .admin-layout[_ngcontent-%COMP%]   .actions[_ngcontent-%COMP%]:not(.hero-card--authenticated .actions), \n   .hero-card[_ngcontent-%COMP%]:not(.hero-card--authenticated)   .date-control[_ngcontent-%COMP%], \n   .date-control[_ngcontent-%COMP%]   input[_ngcontent-%COMP%] {\n    width: 100%;\n  }\n}\n@media (max-width: 420px) {\n  .start-illustration-panel[_ngcontent-%COMP%] {\n    height: 14rem;\n    padding: 1.25rem 1rem;\n  }\n  .start-illustration-panel[_ngcontent-%COMP%]   img[_ngcontent-%COMP%] {\n    max-width: min(84%, 21rem);\n    max-height: calc(100% - 0.5rem);\n    transform: none;\n  }\n}\n/*# sourceMappingURL=home.component.css.map */"] });
};
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && i04.\u0275setClassMetadata(HomeComponent, [{
    type: Component2,
    args: [{ selector: "app-home", imports: [
      AdminLayoutComponent,
      CabinetNavigationComponent,
      FormsModule,
      RouterLink2,
      CabinetBarChartComponent,
      CabinetLineChartComponent
    ], template: `<app-admin-layout
  title="\u041A\u043E\u043C\u043F\u0430\u043D\u0438\u044F \u041E!"
  active="dashboard"
  [profileImageUrl]="profileImageUrl()"
  [profileImageAlt]="cabinet()?.workerZp?.fio || '\u0424\u043E\u0442\u043E \u043F\u0440\u043E\u0444\u0438\u043B\u044F'"
>
  @if (auth.authenticated()) {
    <section class="hero-card hero-card--authenticated">
      <div class="hero-intro">
        <h2>{{ cabinet()?.workerZp?.fio || '\u041B\u0438\u0447\u043D\u044B\u0439 \u043A\u0430\u0431\u0438\u043D\u0435\u0442' }}</h2>
        <p>
          {{ cabinet()?.user?.role || businessRoles()[0] || 'USER' }} \xB7 {{ cabinet()?.user?.username || auth.tokenParsed()?.['preferred_username'] || 'user' }}
        </p>
      </div>

      <div class="actions">
        <button type="button" class="secondary refresh-button" (click)="refreshCabinet()" [disabled]="cabinetLoading()">
          <span class="material-icons-sharp">refresh</span>
          {{ cabinetLoading() ? '\u041E\u0431\u043D\u043E\u0432\u043B\u044F\u044E' : '\u041E\u0431\u043D\u043E\u0432\u0438\u0442\u044C \u043A\u0430\u0431\u0438\u043D\u0435\u0442' }}
        </button>
        <button type="button" class="secondary health-button" (click)="loadHealth(true)" [disabled]="healthLoading()">
          <span class="material-icons-sharp">monitor_heart</span>
          {{ healthLoading() ? '\u041F\u0440\u043E\u0432\u0435\u0440\u044F\u044E' : '\u041F\u0440\u043E\u0432\u0435\u0440\u0438\u0442\u044C backend' }}
        </button>
        <label class="date-control">
          <input type="date" aria-label="\u0414\u0430\u0442\u0430" [ngModel]="cabinetDate()" (ngModelChange)="selectCabinetDate($event)">
        </label>
      </div>
    </section>
  }

  @if (auth.error()) {
    <p class="error">{{ auth.error() }}</p>
  }

  @if (error()) {
    <p class="error">{{ error() }}</p>
  }

  @if (healthError()) {
    <p class="error">{{ healthError() }}</p>
  }

  @if (cabinetError()) {
    <p class="error">{{ cabinetError() }}</p>
  }

  @if (auth.authenticated()) {
    @if (cabinet()) {
      <section class="cabinet-metrics">
        @for (metric of cabinetMetrics(); track metric.label) {
          <article class="metric-card" [class]="tone(metric.percent)">
            <div>
              <h3>{{ metric.label }}</h3>
              <h1>{{ metric.value }}</h1>
            </div>
            <span>{{ metric.percent }}%</span>
          </article>
        }
      </section>

      <section class="chart-grid">
        <app-cabinet-bar-chart
          heading="\u0417\u0430\u0440\u043F\u043B\u0430\u0442\u044B \u043F\u043E \u0434\u043D\u044F\u043C"
          [headingLevel]="2"
          [subtitle]="cabinet()?.date || ''"
          [legendLabel]="selectedMonthLabel()"
          [legendColor]="'#9a7bd9'"
          [chart]="dailyChartFrom(cabinet()?.workerZp?.zpPayMap)"
          [compact]="true"
          [salary]="true"
        />

        <app-cabinet-line-chart
          heading="\u0417\u0430\u0440\u043F\u043B\u0430\u0442\u044B \u043F\u043E \u043C\u0435\u0441\u044F\u0446\u0430\u043C"
          [headingLevel]="2"
          subtitle="\u0432\u0441\u0435 \u0433\u043E\u0434\u044B"
          ariaLabel="\u0417\u0430\u0440\u043F\u043B\u0430\u0442\u044B \u043F\u043E \u043C\u0435\u0441\u044F\u0446\u0430\u043C"
          [chart]="yearlyLineChartFrom(cabinet()?.workerZp?.zpPayMapMonth)"
          [compact]="true"
        />
      </section>
    } @else if (cabinetLoading()) {
      <section class="panel empty">
        <span class="material-icons-sharp">hourglass_top</span>
        <h2>\u0417\u0430\u0433\u0440\u0443\u0436\u0430\u044E \u043B\u0438\u0447\u043D\u044B\u0439 \u043A\u0430\u0431\u0438\u043D\u0435\u0442</h2>
      </section>
    }

    <app-cabinet-navigation [roles]="realmRoles()" active="dashboard" />

    <section class="dashboard-grid">
      <article class="panel profile-panel">
        <div class="panel-title">
          <span class="material-icons-sharp">account_circle</span>
          <div>
            <p class="eyebrow">SESSION</p>
            <h2>{{ me()?.preferredUsername || auth.tokenParsed()?.['preferred_username'] || '\u041F\u043E\u043B\u044C\u0437\u043E\u0432\u0430\u0442\u0435\u043B\u044C' }}</h2>
          </div>
        </div>

        <dl>
          <div>
            <dt>Email</dt>
            <dd>{{ me()?.email || '-' }}</dd>
          </div>
          <div>
            <dt>Backend</dt>
            <dd>{{ health()?.status || (healthLoading() ? '...' : '-') }}</dd>
          </div>
          <div>
            <dt>\u0422\u043E\u043A\u0435\u043D \u0434\u043E</dt>
            <dd>{{ auth.expiresAt()?.toLocaleTimeString() || '-' }}</dd>
          </div>
        </dl>
      </article>

      <article class="panel roles-panel">
        <div class="panel-title">
          <span class="material-icons-sharp">admin_panel_settings</span>
          <div>
            <p class="eyebrow">ACCESS</p>
            <h2>\u0420\u043E\u043B\u0438</h2>
          </div>
        </div>

        <div class="chip-list">
          @for (role of businessRoles(); track role) {
            <span>{{ role }}</span>
          } @empty {
            <span>\u041D\u0435\u0442 \u0431\u0438\u0437\u043D\u0435\u0441-\u0440\u043E\u043B\u0435\u0439</span>
          }
        </div>
      </article>
    </section>

    <section class="panel">
      <div class="panel-title">
        <span class="material-icons-sharp">apps</span>
        <div>
          <p class="eyebrow">SECTIONS</p>
          <h2>\u0411\u044B\u0441\u0442\u0440\u044B\u0435 \u0434\u0435\u0439\u0441\u0442\u0432\u0438\u044F</h2>
        </div>
      </div>

      <div class="action-grid">
        @for (action of visibleActions(); track action.label) {
          @if (action.routerLink) {
            <a class="action-card" [routerLink]="action.routerLink">
              <span class="material-icons-sharp">{{ action.icon }}</span>
              <strong>{{ action.label }}</strong>
              <small>{{ action.description }}</small>
            </a>
          } @else {
            <a class="action-card" [href]="action.href">
              <span class="material-icons-sharp">{{ action.icon }}</span>
              <strong>{{ action.label }}</strong>
              <small>{{ action.description }}</small>
            </a>
          }
        } @empty {
          <div class="empty-inline">
            <span class="material-icons-sharp">lock</span>
            <p>\u0414\u043B\u044F \u0442\u0432\u043E\u0435\u0439 \u0440\u043E\u043B\u0438 \u043F\u043E\u043A\u0430 \u043D\u0435\u0442 \u0431\u044B\u0441\u0442\u0440\u044B\u0445 \u0434\u0435\u0439\u0441\u0442\u0432\u0438\u0439.</p>
          </div>
        }
      </div>
    </section>
  } @else {
    <section class="panel start-illustration-panel" aria-label="\u0420\u0430\u0431\u043E\u0447\u0435\u0435 \u043F\u0440\u043E\u0441\u0442\u0440\u0430\u043D\u0441\u0442\u0432\u043E \u041A\u043E\u043C\u043F\u0430\u043D\u0438\u044F \u041E!">
      <img src="/assets/images/TacoCloud.png" alt="\u041A\u043E\u043C\u0430\u043D\u0434\u0430 \u0440\u0430\u0431\u043E\u0442\u0430\u0435\u0442 \u0441 \u043E\u0442\u0437\u044B\u0432\u0430\u043C\u0438 \u0438 \u043A\u043B\u0438\u0435\u043D\u0442\u0441\u043A\u0438\u043C\u0438 \u0437\u0430\u0434\u0430\u0447\u0430\u043C\u0438">
    </section>
  }
</app-admin-layout>
`, styles: ["/* src/app/features/home/home.component.scss */\n:host {\n  display: block;\n}\n.hero-card,\n.metric-card,\n.panel {\n  border-radius: var(--otziv-card-radius);\n  background: var(--otziv-white);\n  box-shadow: var(--otziv-shadow);\n}\n.hero-card {\n  display: flex;\n  align-items: center;\n  justify-content: space-between;\n  gap: 1.4rem;\n  padding: var(--otziv-toolbar-padding);\n}\n.hero-card h2,\n.hero-card p,\n.metric-card h1,\n.metric-card h3,\n.panel h2,\n.panel p,\ndl {\n  margin: 0;\n}\n.hero-card p:not(.eyebrow),\n.panel p,\ndt,\nsmall {\n  color: var(--otziv-info);\n}\n.cabinet-metrics,\n.dashboard-grid,\n.action-grid,\n.chart-grid {\n  display: grid;\n  gap: 1.05rem;\n  margin-top: 1.05rem;\n}\n.cabinet-metrics {\n  grid-template-columns: repeat(3, minmax(0, 1fr));\n}\n.dashboard-grid {\n  grid-template-columns: 1.2fr 0.8fr;\n}\n.chart-grid {\n  grid-template-columns: repeat(2, minmax(0, 1fr));\n  gap: 1rem;\n}\n.metric-card {\n  display: flex;\n  min-height: 7.45rem;\n  align-items: center;\n  justify-content: space-between;\n  padding: 1.25rem;\n}\n.metric-card h3 {\n  margin-bottom: 0.5rem;\n  font-size: 1rem;\n}\n.metric-card h1 {\n  overflow-wrap: anywhere;\n  font-size: 1.5rem;\n}\n.metric-card > span {\n  display: grid;\n  width: 4.35rem;\n  height: 4.35rem;\n  place-items: center;\n  border: 8px solid currentColor;\n  border-radius: 50%;\n}\n.blue {\n  color: var(--otziv-primary);\n}\n.green {\n  color: var(--otziv-success);\n}\n.yellow {\n  color: #b28405;\n}\n.red {\n  color: var(--otziv-danger);\n}\n.date-control {\n  display: grid;\n  gap: 0.35rem;\n  min-width: 10.5rem;\n  color: var(--otziv-info);\n  font-weight: 800;\n}\n.date-control input {\n  min-height: 2.35rem;\n  border: 1px solid rgba(103, 116, 131, 0.25);\n  border-radius: var(--otziv-small-radius);\n  padding: 0 0.75rem;\n  color: var(--otziv-dark);\n  background: var(--otziv-field-background);\n  font: inherit;\n}\n.hero-card--authenticated .refresh-button {\n  order: 1;\n}\n.hero-card--authenticated .health-button {\n  order: 2;\n}\n.hero-card--authenticated .date-control {\n  order: 3;\n}\n.panel {\n  display: grid;\n  gap: 1rem;\n  margin-top: 1.6rem;\n  padding: var(--otziv-card-padding);\n}\n.dashboard-grid .panel {\n  margin-top: 0;\n}\n.panel-title {\n  display: flex;\n  align-items: center;\n  gap: 0.9rem;\n}\n.panel-title > span {\n  color: var(--otziv-primary);\n  font-size: 2.2rem;\n}\ndl {\n  display: grid;\n  gap: 0.8rem;\n}\ndd {\n  margin: 0.2rem 0 0;\n  overflow-wrap: anywhere;\n  font-weight: 800;\n}\n.chip-list {\n  display: flex;\n  flex-wrap: wrap;\n  gap: 0.7rem;\n}\n.chip-list span {\n  border-radius: 999px;\n  padding: 0.55rem 0.8rem;\n  color: var(--otziv-primary);\n  background: var(--otziv-light);\n  font-weight: 800;\n}\n.action-grid {\n  grid-template-columns: repeat(3, minmax(0, 1fr));\n  margin-top: 0;\n}\n.action-card {\n  display: grid;\n  gap: 0.4rem;\n  border: 1px solid rgba(103, 116, 131, 0.18);\n  border-radius: 1.2rem;\n  padding: 1.2rem;\n  color: var(--otziv-dark);\n  background: var(--otziv-muted-surface);\n  text-decoration: none;\n}\n.action-card > span {\n  color: var(--otziv-primary);\n  font-size: 2rem;\n}\n.empty,\n.empty-inline {\n  place-items: center;\n  align-content: center;\n  color: var(--otziv-info);\n  text-align: center;\n}\n.empty {\n  min-height: 14rem;\n}\n.start-illustration-panel {\n  display: grid;\n  height: clamp(24rem, 100svh - 9.25rem, 47rem);\n  min-height: 0;\n  max-width: 92rem;\n  place-items: center;\n  overflow: hidden;\n  margin-inline: auto;\n  padding: clamp(0.65rem, 1.1vw, 1rem);\n}\n.start-illustration-panel img {\n  display: block;\n  width: 100%;\n  height: 100%;\n  max-width: min(100%, 72rem);\n  max-height: min(100%, 45rem);\n  border-radius: 1.35rem;\n  object-fit: contain;\n  object-position: center;\n}\n.empty > span,\n.empty-inline > span {\n  font-size: 3rem;\n}\n.error {\n  margin-top: 1rem;\n}\n@media (max-width: 1120px) {\n  .cabinet-metrics,\n  .dashboard-grid,\n  .action-grid,\n  .chart-grid {\n    grid-template-columns: 1fr;\n  }\n  .hero-card {\n    align-items: flex-start;\n    flex-direction: column;\n  }\n  .start-illustration-panel {\n    height: clamp(15rem, 44svh, 26rem);\n  }\n}\n@media (min-width: 1121px) and (max-width: 1320px) {\n  .start-illustration-panel {\n    height: clamp(17rem, 46svh, 30rem);\n  }\n}\n@media (max-width: 860px) {\n  .start-illustration-panel {\n    height: clamp(13rem, 34svh, 19rem);\n    margin-top: 0.55rem;\n    border-radius: 1.35rem;\n    padding: clamp(1rem, 3.5vw, 1.35rem);\n  }\n  .start-illustration-panel img {\n    max-width: min(92%, 31rem);\n    max-height: 100%;\n    object-fit: contain;\n    object-position: center;\n  }\n}\n@media (max-width: 760px) {\n  .hero-card--authenticated .hero-intro,\n  .hero-card--authenticated .health-button {\n    display: none;\n  }\n  .hero-card--authenticated {\n    display: block;\n  }\n  .hero-card--authenticated .actions {\n    display: grid;\n    grid-template-columns: minmax(0, 1fr) 2.75rem;\n    align-items: end;\n    justify-content: stretch;\n    width: 100%;\n  }\n  .hero-card--authenticated .date-control {\n    order: 1;\n    width: auto;\n  }\n  .hero-card--authenticated .refresh-button {\n    order: 2;\n  }\n  .hero-card--authenticated button.secondary {\n    width: 2.75rem;\n    min-width: 2.75rem;\n    padding: 0;\n    font-size: 0;\n  }\n  .hero-card--authenticated button.secondary .material-icons-sharp {\n    font-size: 1.35rem;\n  }\n  .start-illustration-panel {\n    height: clamp(11.5rem, 28svh, 16rem);\n    padding: clamp(1.05rem, 4vw, 1.45rem);\n  }\n  .start-illustration-panel img {\n    max-width: min(88%, 24rem);\n    max-height: calc(100% - 0.35rem);\n    object-fit: contain;\n    object-position: center;\n    transform: none;\n  }\n}\n@media (min-width: 1321px) and (max-height: 820px) {\n  .start-illustration-panel {\n    height: clamp(22rem, 100svh - 9rem, 40rem);\n  }\n}\n@media (max-width: 640px) {\n  .admin-layout .actions:not(.hero-card--authenticated .actions),\n  .hero-card:not(.hero-card--authenticated) .date-control,\n  .date-control input {\n    width: 100%;\n  }\n}\n@media (max-width: 420px) {\n  .start-illustration-panel {\n    height: 14rem;\n    padding: 1.25rem 1rem;\n  }\n  .start-illustration-panel img {\n    max-width: min(84%, 21rem);\n    max-height: calc(100% - 0.5rem);\n    transform: none;\n  }\n}\n/*# sourceMappingURL=home.component.css.map */\n"] }]
  }], () => [{ type: AuthService }, { type: CurrentUserApi }, { type: SystemHealthApi }, { type: CabinetApi }, { type: ToastService }, { type: i6.Router }], null);
})();
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && i04.\u0275setClassDebugInfo(HomeComponent, { className: "HomeComponent", filePath: "src/app/features/home/home.component.ts", lineNumber: 58 });
})();
(() => {
  const id = "src%2Fapp%2Ffeatures%2Fhome%2Fhome.component.ts%40HomeComponent";
  function HomeComponent_HmrLoad(t) {
    import(
      /* @vite-ignore */
      __vite__injectQuery(i04.\u0275\u0275getReplaceMetadataURL(id, t, import.meta.url), 'import')
    ).then((m) => m.default && i04.\u0275\u0275replaceMetadata(HomeComponent, m.default, [i04, i7, auth_service_exports, current_user_api_exports, system_health_api_exports, cabinet_api_exports, toast_service_exports, i6], [AdminLayoutComponent, CabinetNavigationComponent, FormsModule, RouterLink2, CabinetBarChartComponent, CabinetLineChartComponent, Component2], import.meta, id));
  }
  (typeof ngDevMode === "undefined" || ngDevMode) && HomeComponent_HmrLoad(Date.now());
  (typeof ngDevMode === "undefined" || ngDevMode) && (import.meta.hot && import.meta.hot.on("angular:component-update", (d) => d.id === id && HomeComponent_HmrLoad(d.timestamp)));
})();
export {
  HomeComponent
};


//# sourceMappingURL=data:application/json;base64,eyJ2ZXJzaW9uIjozLCJzb3VyY2VzIjpbInNyYy9hcHAvZmVhdHVyZXMvaG9tZS9ob21lLmNvbXBvbmVudC50cyIsInNyYy9hcHAvZmVhdHVyZXMvaG9tZS9ob21lLmNvbXBvbmVudC5odG1sIiwic3JjL2FwcC9zaGFyZWQvY2FiaW5ldC1uYXZpZ2F0aW9uLmNvbXBvbmVudC50cyIsInNyYy9hcHAvY29yZS9jdXJyZW50LXVzZXIuYXBpLnRzIiwic3JjL2FwcC9jb3JlL3N5c3RlbS1oZWFsdGguYXBpLnRzIl0sInNvdXJjZXNDb250ZW50IjpbImltcG9ydCB7IENvbXBvbmVudCwgY29tcHV0ZWQsIHNpZ25hbCB9IGZyb20gJ0Bhbmd1bGFyL2NvcmUnO1xuaW1wb3J0IHsgRm9ybXNNb2R1bGUgfSBmcm9tICdAYW5ndWxhci9mb3Jtcyc7XG5pbXBvcnQgeyBSb3V0ZXIsIFJvdXRlckxpbmsgfSBmcm9tICdAYW5ndWxhci9yb3V0ZXInO1xuaW1wb3J0IHsgQXV0aFNlcnZpY2UgfSBmcm9tICcuLi8uLi9jb3JlL2F1dGguc2VydmljZSc7XG5pbXBvcnQgeyBDYWJpbmV0QXBpLCBDYWJpbmV0UHJvZmlsZSB9IGZyb20gJy4uLy4uL2NvcmUvY2FiaW5ldC5hcGknO1xuaW1wb3J0IHsgQ3VycmVudFVzZXIsIEN1cnJlbnRVc2VyQXBpIH0gZnJvbSAnLi4vLi4vY29yZS9jdXJyZW50LXVzZXIuYXBpJztcbmltcG9ydCB7IEFkbWluTGF5b3V0Q29tcG9uZW50IH0gZnJvbSAnLi4vLi4vc2hhcmVkL2FkbWluLWxheW91dC5jb21wb25lbnQnO1xuaW1wb3J0IHsgQ2FiaW5ldE5hdmlnYXRpb25Db21wb25lbnQgfSBmcm9tICcuLi8uLi9zaGFyZWQvY2FiaW5ldC1uYXZpZ2F0aW9uLmNvbXBvbmVudCc7XG5pbXBvcnQgeyBTeXN0ZW1IZWFsdGgsIFN5c3RlbUhlYWx0aEFwaSB9IGZyb20gJy4uLy4uL2NvcmUvc3lzdGVtLWhlYWx0aC5hcGknO1xuaW1wb3J0IHsgYXBwRW52aXJvbm1lbnQgfSBmcm9tICcuLi8uLi9jb3JlL2FwcC1lbnZpcm9ubWVudCc7XG5pbXBvcnQgeyBUb2FzdFNlcnZpY2UgfSBmcm9tICcuLi8uLi9zaGFyZWQvdG9hc3Quc2VydmljZSc7XG5pbXBvcnQgeyBDYWJpbmV0QmFyQ2hhcnRDb21wb25lbnQgfSBmcm9tICcuLi9jYWJpbmV0L2NhYmluZXQtYmFyLWNoYXJ0LmNvbXBvbmVudCc7XG5pbXBvcnQge1xuICBjYWJpbmV0RGFpbHlCYXJDaGFydEZyb20sXG4gIGNhYmluZXRZZWFybHlMaW5lQ2hhcnRGcm9tLFxuICB0eXBlIENhYmluZXRCYXJDaGFydCxcbiAgdHlwZSBDYWJpbmV0TGluZUNoYXJ0XG59IGZyb20gJy4uL2NhYmluZXQvY2FiaW5ldC1jaGFydC5oZWxwZXJzJztcbmltcG9ydCB7IENhYmluZXRMaW5lQ2hhcnRDb21wb25lbnQgfSBmcm9tICcuLi9jYWJpbmV0L2NhYmluZXQtbGluZS1jaGFydC5jb21wb25lbnQnO1xuXG50eXBlIERhc2hib2FyZEFjdGlvbiA9IHtcbiAgbGFiZWw6IHN0cmluZztcbiAgZGVzY3JpcHRpb246IHN0cmluZztcbiAgaWNvbjogc3RyaW5nO1xuICByb2xlczogc3RyaW5nW107XG4gIHJvdXRlckxpbms/OiBzdHJpbmc7XG4gIGhyZWY/OiBzdHJpbmc7XG59O1xuXG5jb25zdCBNT05USF9OQU1FUyA9IFtcbiAgJ9Cv0L3QstCw0YDRjCcsXG4gICfQpNC10LLRgNCw0LvRjCcsXG4gICfQnNCw0YDRgicsXG4gICfQkNC/0YDQtdC70YwnLFxuICAn0JzQsNC5JyxcbiAgJ9CY0Y7QvdGMJyxcbiAgJ9CY0Y7Qu9GMJyxcbiAgJ9CQ0LLQs9GD0YHRgicsXG4gICfQodC10L3RgtGP0LHRgNGMJyxcbiAgJ9Ce0LrRgtGP0LHRgNGMJyxcbiAgJ9Cd0L7Rj9Cx0YDRjCcsXG4gICfQlNC10LrQsNCx0YDRjCdcbl07XG5cbkBDb21wb25lbnQoe1xuICBzZWxlY3RvcjogJ2FwcC1ob21lJyxcbiAgaW1wb3J0czogW1xuICAgIEFkbWluTGF5b3V0Q29tcG9uZW50LFxuICAgIENhYmluZXROYXZpZ2F0aW9uQ29tcG9uZW50LFxuICAgIEZvcm1zTW9kdWxlLFxuICAgIFJvdXRlckxpbmssXG4gICAgQ2FiaW5ldEJhckNoYXJ0Q29tcG9uZW50LFxuICAgIENhYmluZXRMaW5lQ2hhcnRDb21wb25lbnRcbiAgXSxcbiAgdGVtcGxhdGVVcmw6ICcuL2hvbWUuY29tcG9uZW50Lmh0bWwnLFxuICBzdHlsZVVybDogJy4vaG9tZS5jb21wb25lbnQuc2Nzcydcbn0pXG5leHBvcnQgY2xhc3MgSG9tZUNvbXBvbmVudCB7XG4gIHJlYWRvbmx5IG1lID0gc2lnbmFsPEN1cnJlbnRVc2VyIHwgbnVsbD4obnVsbCk7XG4gIHJlYWRvbmx5IGhlYWx0aCA9IHNpZ25hbDxTeXN0ZW1IZWFsdGggfCBudWxsPihudWxsKTtcbiAgcmVhZG9ubHkgY2FiaW5ldCA9IHNpZ25hbDxDYWJpbmV0UHJvZmlsZSB8IG51bGw+KG51bGwpO1xuICByZWFkb25seSBjYWJpbmV0RGF0ZSA9IHNpZ25hbCh0aGlzLnRvZGF5SXNvKCkpO1xuICByZWFkb25seSBsb2FkaW5nID0gc2lnbmFsKGZhbHNlKTtcbiAgcmVhZG9ubHkgaGVhbHRoTG9hZGluZyA9IHNpZ25hbChmYWxzZSk7XG4gIHJlYWRvbmx5IGNhYmluZXRMb2FkaW5nID0gc2lnbmFsKGZhbHNlKTtcbiAgcmVhZG9ubHkgZXJyb3IgPSBzaWduYWw8c3RyaW5nIHwgbnVsbD4obnVsbCk7XG4gIHJlYWRvbmx5IGhlYWx0aEVycm9yID0gc2lnbmFsPHN0cmluZyB8IG51bGw+KG51bGwpO1xuICByZWFkb25seSBjYWJpbmV0RXJyb3IgPSBzaWduYWw8c3RyaW5nIHwgbnVsbD4obnVsbCk7XG5cbiAgcmVhZG9ubHkgYWN0aW9uczogRGFzaGJvYXJkQWN0aW9uW10gPSBbXG4gICAge1xuICAgICAgbGFiZWw6ICfQn9C+0LvRjNC30L7QstCw0YLQtdC70LgnLFxuICAgICAgZGVzY3JpcHRpb246ICdLZXljbG9haywg0YDQvtC70Lgg0Lgg0YHQstGP0LfQuCDQutC+0LzQsNC90LTRiycsXG4gICAgICBpY29uOiAnZ3JvdXBfYWRkJyxcbiAgICAgIHJvbGVzOiBbJ0FETUlOJywgJ09XTkVSJ10sXG4gICAgICByb3V0ZXJMaW5rOiAnL2FkbWluL3VzZXJzJ1xuICAgIH0sXG4gICAge1xuICAgICAgbGFiZWw6ICfQm9C40LTRiycsXG4gICAgICBkZXNjcmlwdGlvbjogJ9Cd0L7QstCw0Y8g0YDQsNCx0L7Rh9Cw0Y8g0LTQvtGB0LrQsCcsXG4gICAgICBpY29uOiAnbm90aWZpY2F0aW9uc19hY3RpdmUnLFxuICAgICAgcm9sZXM6IFsnQURNSU4nLCAnT1dORVInLCAnTUFOQUdFUicsICdNQVJLRVRPTE9HJ10sXG4gICAgICByb3V0ZXJMaW5rOiAnL2xlYWRzJ1xuICAgIH0sXG4gICAge1xuICAgICAgbGFiZWw6ICfQntC/0LXRgNCw0YLQvtGAJyxcbiAgICAgIGRlc2NyaXB0aW9uOiAn0J7Qv9C10YDQsNGC0L7RgNGLINC4INC+0LHRgNCw0LHQvtGC0LrQsCDQt9Cw0Y/QstC+0LonLFxuICAgICAgaWNvbjogJ3N1cHBvcnRfYWdlbnQnLFxuICAgICAgcm9sZXM6IFsnQURNSU4nLCAnT1dORVInLCAnT1BFUkFUT1InXSxcbiAgICAgIHJvdXRlckxpbms6ICcvb3BlcmF0b3InXG4gICAgfSxcbiAgICB7XG4gICAgICBsYWJlbDogJ9Cc0LXQvdC10LTQttC10YAnLFxuICAgICAgZGVzY3JpcHRpb246ICfQmtC+0LzQv9Cw0L3QuNC4INC4INC30LDQutCw0LfRiycsXG4gICAgICBpY29uOiAnZ3JvdXBzJyxcbiAgICAgIHJvbGVzOiBbJ0FETUlOJywgJ09XTkVSJywgJ01BTkFHRVInXSxcbiAgICAgIHJvdXRlckxpbms6ICcvbWFuYWdlcidcbiAgICB9LFxuICAgIHtcbiAgICAgIGxhYmVsOiAn0KHQv9C10YbQuNCw0LvQuNGB0YInLFxuICAgICAgZGVzY3JpcHRpb246ICfQkNC60LrQsNGD0L3RgtGLLCDQv9GD0LHQu9C40LrQsNGG0LjQuCDQuCDQt9Cw0LTQsNGH0LgnLFxuICAgICAgaWNvbjogJ2VuZ2luZWVyaW5nJyxcbiAgICAgIHJvbGVzOiBbJ0FETUlOJywgJ09XTkVSJywgJ01BTkFHRVInLCAnV09SS0VSJ10sXG4gICAgICByb3V0ZXJMaW5rOiAnL3dvcmtlcidcbiAgICB9LFxuICAgIHtcbiAgICAgIGxhYmVsOiAnR3JhZmFuYScsXG4gICAgICBkZXNjcmlwdGlvbjogJ9Cc0LXRgtGA0LjQutC4INC4INC90LDQsdC70Y7QtNCw0LXQvNC+0YHRgtGMJyxcbiAgICAgIGljb246ICdtb25pdG9yaW5nJyxcbiAgICAgIHJvbGVzOiBbJ0FETUlOJywgJ09XTkVSJ10sXG4gICAgICBocmVmOiBhcHBFbnZpcm9ubWVudC5tZXRyaWNzQmFzZVVybFxuICAgIH1cbiAgXTtcblxuICByZWFkb25seSByZWFsbVJvbGVzID0gY29tcHV0ZWQoKCkgPT4ge1xuICAgIHJldHVybiB0aGlzLmF1dGgudG9rZW5QYXJzZWQoKT8uWydyZWFsbV9hY2Nlc3MnXT8uWydyb2xlcyddIGFzIHN0cmluZ1tdIHwgdW5kZWZpbmVkID8/IFtdO1xuICB9KTtcblxuICByZWFkb25seSBidXNpbmVzc1JvbGVzID0gY29tcHV0ZWQoKCkgPT4ge1xuICAgIGNvbnN0IGlnbm9yZWQgPSBuZXcgU2V0KFsnZGVmYXVsdC1yb2xlcy1vdHppdicsICdvZmZsaW5lX2FjY2VzcycsICd1bWFfYXV0aG9yaXphdGlvbiddKTtcbiAgICByZXR1cm4gdGhpcy5yZWFsbVJvbGVzKCkuZmlsdGVyKChyb2xlKSA9PiAhaWdub3JlZC5oYXMocm9sZSkpO1xuICB9KTtcblxuICByZWFkb25seSB2aXNpYmxlQWN0aW9ucyA9IGNvbXB1dGVkKCgpID0+IHtcbiAgICBpZiAoIXRoaXMuYXV0aC5hdXRoZW50aWNhdGVkKCkpIHtcbiAgICAgIHJldHVybiBbXTtcbiAgICB9XG5cbiAgICBjb25zdCByb2xlcyA9IG5ldyBTZXQodGhpcy5yZWFsbVJvbGVzKCkpO1xuICAgIGNvbnN0IGNhblNlZUFsbCA9IHJvbGVzLmhhcygnQURNSU4nKSB8fCByb2xlcy5oYXMoJ09XTkVSJyk7XG4gICAgcmV0dXJuIHRoaXMuYWN0aW9ucy5maWx0ZXIoKGFjdGlvbikgPT4gY2FuU2VlQWxsIHx8IGFjdGlvbi5yb2xlcy5zb21lKChyb2xlKSA9PiByb2xlcy5oYXMocm9sZSkpKTtcbiAgfSk7XG5cbiAgcmVhZG9ubHkgY2FiaW5ldE1ldHJpY3MgPSBjb21wdXRlZCgoKSA9PiB7XG4gICAgY29uc3Qgd29ya2VyWnAgPSB0aGlzLmNhYmluZXQoKT8ud29ya2VyWnA7XG4gICAgaWYgKCF3b3JrZXJacCkge1xuICAgICAgcmV0dXJuIFtdO1xuICAgIH1cblxuICAgIHJldHVybiBbXG4gICAgICB7IGxhYmVsOiAn0JfQsCDQstGH0LXRgNCwJywgdmFsdWU6IHRoaXMubW9uZXkod29ya2VyWnAuc3VtMURheSksIHBlcmNlbnQ6IHdvcmtlclpwLnBlcmNlbnQxRGF5IH0sXG4gICAgICB7IGxhYmVsOiAn0JfQsCDQvdC10LTQtdC70Y4nLCB2YWx1ZTogdGhpcy5tb25leSh3b3JrZXJacC5zdW0xV2VlayksIHBlcmNlbnQ6IHdvcmtlclpwLnBlcmNlbnQxV2VlayB9LFxuICAgICAgeyBsYWJlbDogJ9CX0LAg0LzQtdGB0Y/RhicsIHZhbHVlOiB0aGlzLm1vbmV5KHdvcmtlclpwLnN1bTFNb250aCksIHBlcmNlbnQ6IHdvcmtlclpwLnBlcmNlbnQxTW9udGggfSxcbiAgICAgIHsgbGFiZWw6ICfQl9CwINCz0L7QtCcsIHZhbHVlOiB0aGlzLm1vbmV5KHdvcmtlclpwLnN1bTFZZWFyKSwgcGVyY2VudDogd29ya2VyWnAucGVyY2VudDFZZWFyIH0sXG4gICAgICB7IGxhYmVsOiAn0JfQsNC60LDQt9C+0LIg0LfQsCDQvNC10YHRj9GGJywgdmFsdWU6IHRoaXMuY291bnQod29ya2VyWnAuc3VtT3JkZXJzMU1vbnRoKSwgcGVyY2VudDogd29ya2VyWnAucGVyY2VudDFNb250aE9yZGVycyB9LFxuICAgICAgeyBsYWJlbDogJ9CX0LAg0L/RgNC+0YjQu9GL0Lkg0LzQtdGB0Y/RhicsIHZhbHVlOiB0aGlzLmNvdW50KHdvcmtlclpwLnN1bU9yZGVyczJNb250aCksIHBlcmNlbnQ6IHdvcmtlclpwLnBlcmNlbnQyTW9udGhPcmRlcnMgfVxuICAgIF07XG4gIH0pO1xuXG4gIGNvbnN0cnVjdG9yKFxuICAgIHJlYWRvbmx5IGF1dGg6IEF1dGhTZXJ2aWNlLFxuICAgIHByaXZhdGUgcmVhZG9ubHkgY3VycmVudFVzZXJBcGk6IEN1cnJlbnRVc2VyQXBpLFxuICAgIHByaXZhdGUgcmVhZG9ubHkgc3lzdGVtSGVhbHRoQXBpOiBTeXN0ZW1IZWFsdGhBcGksXG4gICAgcHJpdmF0ZSByZWFkb25seSBjYWJpbmV0QXBpOiBDYWJpbmV0QXBpLFxuICAgIHByaXZhdGUgcmVhZG9ubHkgdG9hc3RTZXJ2aWNlOiBUb2FzdFNlcnZpY2UsXG4gICAgcHJpdmF0ZSByZWFkb25seSByb3V0ZXI6IFJvdXRlclxuICApIHtcbiAgICBpZiAodGhpcy5zaG91bGRPcGVuQW5hbHl0aWNzSG9tZSgpKSB7XG4gICAgICB2b2lkIHRoaXMucm91dGVyLm5hdmlnYXRlKFsnL2FkbWluL2FuYWx5c2UnXSk7XG4gICAgICByZXR1cm47XG4gICAgfVxuXG4gICAgaWYgKHRoaXMuYXV0aC5pc0F1dGhlbnRpY2F0ZWQoKSkge1xuICAgICAgdGhpcy5sb2FkQ3VycmVudFVzZXIoKTtcbiAgICAgIHRoaXMubG9hZENhYmluZXQoKTtcbiAgICB9XG5cbiAgICB0aGlzLmxvYWRIZWFsdGgoKTtcbiAgfVxuXG4gIGxvZ2luKCk6IHZvaWQge1xuICAgIHZvaWQgdGhpcy5hdXRoLmxvZ2luKCcvJyk7XG4gIH1cblxuICBsb2dvdXQoKTogdm9pZCB7XG4gICAgdm9pZCB0aGlzLmF1dGgubG9nb3V0KCk7XG4gIH1cblxuICBsb2FkQ3VycmVudFVzZXIoKTogdm9pZCB7XG4gICAgdGhpcy5sb2FkaW5nLnNldCh0cnVlKTtcbiAgICB0aGlzLmVycm9yLnNldChudWxsKTtcblxuICAgIHRoaXMuY3VycmVudFVzZXJBcGkuZ2V0TWUoKS5zdWJzY3JpYmUoe1xuICAgICAgbmV4dDogKHVzZXIpID0+IHtcbiAgICAgICAgdGhpcy5tZS5zZXQodXNlcik7XG4gICAgICAgIHRoaXMubG9hZGluZy5zZXQoZmFsc2UpO1xuICAgICAgfSxcbiAgICAgIGVycm9yOiAoZXJyKSA9PiB7XG4gICAgICAgIHRoaXMuZXJyb3Iuc2V0KGVycj8ubWVzc2FnZSA/PyAnUmVxdWVzdCBmYWlsZWQnKTtcbiAgICAgICAgdGhpcy5sb2FkaW5nLnNldChmYWxzZSk7XG4gICAgICB9XG4gICAgfSk7XG4gIH1cblxuICBsb2FkSGVhbHRoKHNob3dUb2FzdCA9IGZhbHNlKTogdm9pZCB7XG4gICAgdGhpcy5oZWFsdGhMb2FkaW5nLnNldCh0cnVlKTtcbiAgICB0aGlzLmhlYWx0aEVycm9yLnNldChudWxsKTtcblxuICAgIHRoaXMuc3lzdGVtSGVhbHRoQXBpLmdldEhlYWx0aCgpLnN1YnNjcmliZSh7XG4gICAgICBuZXh0OiAoaGVhbHRoKSA9PiB7XG4gICAgICAgIHRoaXMuaGVhbHRoLnNldChoZWFsdGgpO1xuICAgICAgICB0aGlzLmhlYWx0aExvYWRpbmcuc2V0KGZhbHNlKTtcbiAgICAgICAgaWYgKHNob3dUb2FzdCkge1xuICAgICAgICAgIHRoaXMuc2hvd0hlYWx0aFRvYXN0KGhlYWx0aCk7XG4gICAgICAgIH1cbiAgICAgIH0sXG4gICAgICBlcnJvcjogKGVycikgPT4ge1xuICAgICAgICBjb25zdCBtZXNzYWdlID0gZXJyPy5tZXNzYWdlID8/ICdIZWFsdGggY2hlY2sgZmFpbGVkJztcbiAgICAgICAgdGhpcy5oZWFsdGhFcnJvci5zZXQobWVzc2FnZSk7XG4gICAgICAgIHRoaXMuaGVhbHRoTG9hZGluZy5zZXQoZmFsc2UpO1xuICAgICAgICBpZiAoc2hvd1RvYXN0KSB7XG4gICAgICAgICAgdGhpcy50b2FzdFNlcnZpY2UuZXJyb3IoJ0JhY2tlbmQg0L3QtdC00L7RgdGC0YPQv9C10L0nLCBtZXNzYWdlKTtcbiAgICAgICAgfVxuICAgICAgfVxuICAgIH0pO1xuICB9XG5cbiAgbG9hZENhYmluZXQoZm9yY2VSZWZyZXNoID0gZmFsc2UpOiB2b2lkIHtcbiAgICB0aGlzLmNhYmluZXRMb2FkaW5nLnNldCh0cnVlKTtcbiAgICB0aGlzLmNhYmluZXRFcnJvci5zZXQobnVsbCk7XG5cbiAgICB0aGlzLmNhYmluZXRBcGkuZ2V0UHJvZmlsZSh0aGlzLmNhYmluZXREYXRlKCksIHsgZm9yY2VSZWZyZXNoIH0pLnN1YnNjcmliZSh7XG4gICAgICBuZXh0OiAocHJvZmlsZSkgPT4ge1xuICAgICAgICB0aGlzLmNhYmluZXQuc2V0KHByb2ZpbGUpO1xuICAgICAgICB0aGlzLmNhYmluZXRMb2FkaW5nLnNldChmYWxzZSk7XG4gICAgICB9LFxuICAgICAgZXJyb3I6IChlcnIpID0+IHtcbiAgICAgICAgdGhpcy5jYWJpbmV0RXJyb3Iuc2V0KGVycj8uZXJyb3I/Lm1lc3NhZ2UgPz8gZXJyPy5tZXNzYWdlID8/ICdDYWJpbmV0IHJlcXVlc3QgZmFpbGVkJyk7XG4gICAgICAgIHRoaXMuY2FiaW5ldExvYWRpbmcuc2V0KGZhbHNlKTtcbiAgICAgIH1cbiAgICB9KTtcbiAgfVxuXG4gIHJlZnJlc2hDYWJpbmV0KCk6IHZvaWQge1xuICAgIHRoaXMubG9hZENhYmluZXQodHJ1ZSk7XG4gIH1cblxuICBzZWxlY3RDYWJpbmV0RGF0ZShkYXRlOiBzdHJpbmcpOiB2b2lkIHtcbiAgICB0aGlzLmNhYmluZXREYXRlLnNldChkYXRlKTtcbiAgICB0aGlzLmxvYWRDYWJpbmV0KHRydWUpO1xuICB9XG5cbiAgaGFzQWN0aW9uTGluayhhY3Rpb246IERhc2hib2FyZEFjdGlvbik6IGJvb2xlYW4ge1xuICAgIHJldHVybiBCb29sZWFuKGFjdGlvbi5yb3V0ZXJMaW5rIHx8IGFjdGlvbi5ocmVmKTtcbiAgfVxuXG4gIGltYWdlVXJsKGltYWdlSWQ/OiBudW1iZXIgfCBudWxsKTogc3RyaW5nIHtcbiAgICByZXR1cm4gdGhpcy5jYWJpbmV0QXBpLmltYWdlVXJsKGltYWdlSWQpO1xuICB9XG5cbiAgcHJvZmlsZUltYWdlVXJsKCk6IHN0cmluZyB8IG51bGwge1xuICAgIGNvbnN0IGltYWdlSWQgPSB0aGlzLmNhYmluZXQoKT8ud29ya2VyWnA/LmltYWdlSWQgfHwgdGhpcy5jYWJpbmV0KCk/LnVzZXI/LmltYWdlO1xuICAgIHJldHVybiBpbWFnZUlkID8gdGhpcy5pbWFnZVVybChpbWFnZUlkKSA6IG51bGw7XG4gIH1cblxuICBwcml2YXRlIHNob3dIZWFsdGhUb2FzdChoZWFsdGg6IFN5c3RlbUhlYWx0aCk6IHZvaWQge1xuICAgIGNvbnN0IHN0YXR1cyA9IGhlYWx0aC5zdGF0dXMgfHwgJ1VOS05PV04nO1xuICAgIGNvbnN0IG1lc3NhZ2UgPSBgQWN0dWF0b3IgaGVhbHRoOiAke3N0YXR1c31gO1xuXG4gICAgaWYgKHN0YXR1cy50b1VwcGVyQ2FzZSgpID09PSAnVVAnKSB7XG4gICAgICB0aGlzLnRvYXN0U2VydmljZS5zdWNjZXNzKCdCYWNrZW5kINGA0LDQsdC+0YLQsNC10YInLCBtZXNzYWdlKTtcbiAgICAgIHJldHVybjtcbiAgICB9XG5cbiAgICB0aGlzLnRvYXN0U2VydmljZS5pbmZvKCdCYWNrZW5kINC+0YLQstC10YLQuNC7JywgbWVzc2FnZSk7XG4gIH1cblxuICBwcml2YXRlIHNob3VsZE9wZW5BbmFseXRpY3NIb21lKCk6IGJvb2xlYW4ge1xuICAgIHJldHVybiB0aGlzLmF1dGguaXNBdXRoZW50aWNhdGVkKCkgJiYgdGhpcy5hdXRoLmhhc0FueVJlYWxtUm9sZShbJ0FETUlOJywgJ09XTkVSJ10pO1xuICB9XG5cbiAgZGFpbHlDaGFydEZyb20obWFwPzogc3RyaW5nIHwgbnVsbCk6IENhYmluZXRCYXJDaGFydCB7XG4gICAgcmV0dXJuIGNhYmluZXREYWlseUJhckNoYXJ0RnJvbShtYXAsIHRoaXMuY2FiaW5ldERhdGUoKSk7XG4gIH1cblxuICB5ZWFybHlMaW5lQ2hhcnRGcm9tKG1hcD86IHN0cmluZyB8IG51bGwpOiBDYWJpbmV0TGluZUNoYXJ0IHtcbiAgICByZXR1cm4gY2FiaW5ldFllYXJseUxpbmVDaGFydEZyb20obWFwLCB7IGZhbGxiYWNrWWVhcjogbmV3IERhdGUodGhpcy5jYWJpbmV0RGF0ZSgpKS5nZXRGdWxsWWVhcigpIH0pO1xuICB9XG5cbiAgc2VsZWN0ZWRNb250aExhYmVsKCk6IHN0cmluZyB7XG4gICAgY29uc3QgZGF0ZSA9IG5ldyBEYXRlKHRoaXMuY2FiaW5ldERhdGUoKSk7XG4gICAgcmV0dXJuIGDQnNC10YHRj9GGOiAke01PTlRIX05BTUVTW2RhdGUuZ2V0TW9udGgoKV0gPz8gTU9OVEhfTkFNRVNbMF19YDtcbiAgfVxuXG4gIHRvbmUocGVyY2VudDogbnVtYmVyKTogc3RyaW5nIHtcbiAgICBpZiAocGVyY2VudCA+IDI1KSB7XG4gICAgICByZXR1cm4gJ2dyZWVuJztcbiAgICB9XG5cbiAgICBpZiAocGVyY2VudCA+PSAwKSB7XG4gICAgICByZXR1cm4gJ2JsdWUnO1xuICAgIH1cblxuICAgIGlmIChwZXJjZW50ID4gLTI1KSB7XG4gICAgICByZXR1cm4gJ3llbGxvdyc7XG4gICAgfVxuXG4gICAgcmV0dXJuICdyZWQnO1xuICB9XG5cbiAgcHJpdmF0ZSBtb25leSh2YWx1ZT86IG51bWJlciB8IG51bGwpOiBzdHJpbmcge1xuICAgIHJldHVybiBgJHtuZXcgSW50bC5OdW1iZXJGb3JtYXQoJ3J1LVJVJykuZm9ybWF0KHZhbHVlIHx8IDApfSDRgNGD0LEuYDtcbiAgfVxuXG4gIHByaXZhdGUgY291bnQodmFsdWU/OiBudW1iZXIgfCBudWxsKTogc3RyaW5nIHtcbiAgICByZXR1cm4gYCR7bmV3IEludGwuTnVtYmVyRm9ybWF0KCdydS1SVScpLmZvcm1hdCh2YWx1ZSB8fCAwKX0g0YjRgi5gO1xuICB9XG5cbiAgcHJpdmF0ZSB0b2RheUlzbygpOiBzdHJpbmcge1xuICAgIHJldHVybiBuZXcgRGF0ZSgpLnRvSVNPU3RyaW5nKCkuc2xpY2UoMCwgMTApO1xuICB9XG59XG4iLCI8YXBwLWFkbWluLWxheW91dFxuICB0aXRsZT1cItCa0L7QvNC/0LDQvdC40Y8g0J4hXCJcbiAgYWN0aXZlPVwiZGFzaGJvYXJkXCJcbiAgW3Byb2ZpbGVJbWFnZVVybF09XCJwcm9maWxlSW1hZ2VVcmwoKVwiXG4gIFtwcm9maWxlSW1hZ2VBbHRdPVwiY2FiaW5ldCgpPy53b3JrZXJacD8uZmlvIHx8ICfQpNC+0YLQviDQv9GA0L7RhNC40LvRjydcIlxuPlxuICBAaWYgKGF1dGguYXV0aGVudGljYXRlZCgpKSB7XG4gICAgPHNlY3Rpb24gY2xhc3M9XCJoZXJvLWNhcmQgaGVyby1jYXJkLS1hdXRoZW50aWNhdGVkXCI+XG4gICAgICA8ZGl2IGNsYXNzPVwiaGVyby1pbnRyb1wiPlxuICAgICAgICA8aDI+e3sgY2FiaW5ldCgpPy53b3JrZXJacD8uZmlvIHx8ICfQm9C40YfQvdGL0Lkg0LrQsNCx0LjQvdC10YInIH19PC9oMj5cbiAgICAgICAgPHA+XG4gICAgICAgICAge3sgY2FiaW5ldCgpPy51c2VyPy5yb2xlIHx8IGJ1c2luZXNzUm9sZXMoKVswXSB8fCAnVVNFUicgfX0gwrcge3sgY2FiaW5ldCgpPy51c2VyPy51c2VybmFtZSB8fCBhdXRoLnRva2VuUGFyc2VkKCk/LlsncHJlZmVycmVkX3VzZXJuYW1lJ10gfHwgJ3VzZXInIH19XG4gICAgICAgIDwvcD5cbiAgICAgIDwvZGl2PlxuXG4gICAgICA8ZGl2IGNsYXNzPVwiYWN0aW9uc1wiPlxuICAgICAgICA8YnV0dG9uIHR5cGU9XCJidXR0b25cIiBjbGFzcz1cInNlY29uZGFyeSByZWZyZXNoLWJ1dHRvblwiIChjbGljayk9XCJyZWZyZXNoQ2FiaW5ldCgpXCIgW2Rpc2FibGVkXT1cImNhYmluZXRMb2FkaW5nKClcIj5cbiAgICAgICAgICA8c3BhbiBjbGFzcz1cIm1hdGVyaWFsLWljb25zLXNoYXJwXCI+cmVmcmVzaDwvc3Bhbj5cbiAgICAgICAgICB7eyBjYWJpbmV0TG9hZGluZygpID8gJ9Ce0LHQvdC+0LLQu9GP0Y4nIDogJ9Ce0LHQvdC+0LLQuNGC0Ywg0LrQsNCx0LjQvdC10YInIH19XG4gICAgICAgIDwvYnV0dG9uPlxuICAgICAgICA8YnV0dG9uIHR5cGU9XCJidXR0b25cIiBjbGFzcz1cInNlY29uZGFyeSBoZWFsdGgtYnV0dG9uXCIgKGNsaWNrKT1cImxvYWRIZWFsdGgodHJ1ZSlcIiBbZGlzYWJsZWRdPVwiaGVhbHRoTG9hZGluZygpXCI+XG4gICAgICAgICAgPHNwYW4gY2xhc3M9XCJtYXRlcmlhbC1pY29ucy1zaGFycFwiPm1vbml0b3JfaGVhcnQ8L3NwYW4+XG4gICAgICAgICAge3sgaGVhbHRoTG9hZGluZygpID8gJ9Cf0YDQvtCy0LXRgNGP0Y4nIDogJ9Cf0YDQvtCy0LXRgNC40YLRjCBiYWNrZW5kJyB9fVxuICAgICAgICA8L2J1dHRvbj5cbiAgICAgICAgPGxhYmVsIGNsYXNzPVwiZGF0ZS1jb250cm9sXCI+XG4gICAgICAgICAgPGlucHV0IHR5cGU9XCJkYXRlXCIgYXJpYS1sYWJlbD1cItCU0LDRgtCwXCIgW25nTW9kZWxdPVwiY2FiaW5ldERhdGUoKVwiIChuZ01vZGVsQ2hhbmdlKT1cInNlbGVjdENhYmluZXREYXRlKCRldmVudClcIj5cbiAgICAgICAgPC9sYWJlbD5cbiAgICAgIDwvZGl2PlxuICAgIDwvc2VjdGlvbj5cbiAgfVxuXG4gIEBpZiAoYXV0aC5lcnJvcigpKSB7XG4gICAgPHAgY2xhc3M9XCJlcnJvclwiPnt7IGF1dGguZXJyb3IoKSB9fTwvcD5cbiAgfVxuXG4gIEBpZiAoZXJyb3IoKSkge1xuICAgIDxwIGNsYXNzPVwiZXJyb3JcIj57eyBlcnJvcigpIH19PC9wPlxuICB9XG5cbiAgQGlmIChoZWFsdGhFcnJvcigpKSB7XG4gICAgPHAgY2xhc3M9XCJlcnJvclwiPnt7IGhlYWx0aEVycm9yKCkgfX08L3A+XG4gIH1cblxuICBAaWYgKGNhYmluZXRFcnJvcigpKSB7XG4gICAgPHAgY2xhc3M9XCJlcnJvclwiPnt7IGNhYmluZXRFcnJvcigpIH19PC9wPlxuICB9XG5cbiAgQGlmIChhdXRoLmF1dGhlbnRpY2F0ZWQoKSkge1xuICAgIEBpZiAoY2FiaW5ldCgpKSB7XG4gICAgICA8c2VjdGlvbiBjbGFzcz1cImNhYmluZXQtbWV0cmljc1wiPlxuICAgICAgICBAZm9yIChtZXRyaWMgb2YgY2FiaW5ldE1ldHJpY3MoKTsgdHJhY2sgbWV0cmljLmxhYmVsKSB7XG4gICAgICAgICAgPGFydGljbGUgY2xhc3M9XCJtZXRyaWMtY2FyZFwiIFtjbGFzc109XCJ0b25lKG1ldHJpYy5wZXJjZW50KVwiPlxuICAgICAgICAgICAgPGRpdj5cbiAgICAgICAgICAgICAgPGgzPnt7IG1ldHJpYy5sYWJlbCB9fTwvaDM+XG4gICAgICAgICAgICAgIDxoMT57eyBtZXRyaWMudmFsdWUgfX08L2gxPlxuICAgICAgICAgICAgPC9kaXY+XG4gICAgICAgICAgICA8c3Bhbj57eyBtZXRyaWMucGVyY2VudCB9fSU8L3NwYW4+XG4gICAgICAgICAgPC9hcnRpY2xlPlxuICAgICAgICB9XG4gICAgICA8L3NlY3Rpb24+XG5cbiAgICAgIDxzZWN0aW9uIGNsYXNzPVwiY2hhcnQtZ3JpZFwiPlxuICAgICAgICA8YXBwLWNhYmluZXQtYmFyLWNoYXJ0XG4gICAgICAgICAgaGVhZGluZz1cItCX0LDRgNC/0LvQsNGC0Ysg0L/QviDQtNC90Y/QvFwiXG4gICAgICAgICAgW2hlYWRpbmdMZXZlbF09XCIyXCJcbiAgICAgICAgICBbc3VidGl0bGVdPVwiY2FiaW5ldCgpPy5kYXRlIHx8ICcnXCJcbiAgICAgICAgICBbbGVnZW5kTGFiZWxdPVwic2VsZWN0ZWRNb250aExhYmVsKClcIlxuICAgICAgICAgIFtsZWdlbmRDb2xvcl09XCInIzlhN2JkOSdcIlxuICAgICAgICAgIFtjaGFydF09XCJkYWlseUNoYXJ0RnJvbShjYWJpbmV0KCk/LndvcmtlclpwPy56cFBheU1hcClcIlxuICAgICAgICAgIFtjb21wYWN0XT1cInRydWVcIlxuICAgICAgICAgIFtzYWxhcnldPVwidHJ1ZVwiXG4gICAgICAgIC8+XG5cbiAgICAgICAgPGFwcC1jYWJpbmV0LWxpbmUtY2hhcnRcbiAgICAgICAgICBoZWFkaW5nPVwi0JfQsNGA0L/Qu9Cw0YLRiyDQv9C+INC80LXRgdGP0YbQsNC8XCJcbiAgICAgICAgICBbaGVhZGluZ0xldmVsXT1cIjJcIlxuICAgICAgICAgIHN1YnRpdGxlPVwi0LLRgdC1INCz0L7QtNGLXCJcbiAgICAgICAgICBhcmlhTGFiZWw9XCLQl9Cw0YDQv9C70LDRgtGLINC/0L4g0LzQtdGB0Y/RhtCw0LxcIlxuICAgICAgICAgIFtjaGFydF09XCJ5ZWFybHlMaW5lQ2hhcnRGcm9tKGNhYmluZXQoKT8ud29ya2VyWnA/LnpwUGF5TWFwTW9udGgpXCJcbiAgICAgICAgICBbY29tcGFjdF09XCJ0cnVlXCJcbiAgICAgICAgLz5cbiAgICAgIDwvc2VjdGlvbj5cbiAgICB9IEBlbHNlIGlmIChjYWJpbmV0TG9hZGluZygpKSB7XG4gICAgICA8c2VjdGlvbiBjbGFzcz1cInBhbmVsIGVtcHR5XCI+XG4gICAgICAgIDxzcGFuIGNsYXNzPVwibWF0ZXJpYWwtaWNvbnMtc2hhcnBcIj5ob3VyZ2xhc3NfdG9wPC9zcGFuPlxuICAgICAgICA8aDI+0JfQsNCz0YDRg9C20LDRjiDQu9C40YfQvdGL0Lkg0LrQsNCx0LjQvdC10YI8L2gyPlxuICAgICAgPC9zZWN0aW9uPlxuICAgIH1cblxuICAgIDxhcHAtY2FiaW5ldC1uYXZpZ2F0aW9uIFtyb2xlc109XCJyZWFsbVJvbGVzKClcIiBhY3RpdmU9XCJkYXNoYm9hcmRcIiAvPlxuXG4gICAgPHNlY3Rpb24gY2xhc3M9XCJkYXNoYm9hcmQtZ3JpZFwiPlxuICAgICAgPGFydGljbGUgY2xhc3M9XCJwYW5lbCBwcm9maWxlLXBhbmVsXCI+XG4gICAgICAgIDxkaXYgY2xhc3M9XCJwYW5lbC10aXRsZVwiPlxuICAgICAgICAgIDxzcGFuIGNsYXNzPVwibWF0ZXJpYWwtaWNvbnMtc2hhcnBcIj5hY2NvdW50X2NpcmNsZTwvc3Bhbj5cbiAgICAgICAgICA8ZGl2PlxuICAgICAgICAgICAgPHAgY2xhc3M9XCJleWVicm93XCI+U0VTU0lPTjwvcD5cbiAgICAgICAgICAgIDxoMj57eyBtZSgpPy5wcmVmZXJyZWRVc2VybmFtZSB8fCBhdXRoLnRva2VuUGFyc2VkKCk/LlsncHJlZmVycmVkX3VzZXJuYW1lJ10gfHwgJ9Cf0L7Qu9GM0LfQvtCy0LDRgtC10LvRjCcgfX08L2gyPlxuICAgICAgICAgIDwvZGl2PlxuICAgICAgICA8L2Rpdj5cblxuICAgICAgICA8ZGw+XG4gICAgICAgICAgPGRpdj5cbiAgICAgICAgICAgIDxkdD5FbWFpbDwvZHQ+XG4gICAgICAgICAgICA8ZGQ+e3sgbWUoKT8uZW1haWwgfHwgJy0nIH19PC9kZD5cbiAgICAgICAgICA8L2Rpdj5cbiAgICAgICAgICA8ZGl2PlxuICAgICAgICAgICAgPGR0PkJhY2tlbmQ8L2R0PlxuICAgICAgICAgICAgPGRkPnt7IGhlYWx0aCgpPy5zdGF0dXMgfHwgKGhlYWx0aExvYWRpbmcoKSA/ICcuLi4nIDogJy0nKSB9fTwvZGQ+XG4gICAgICAgICAgPC9kaXY+XG4gICAgICAgICAgPGRpdj5cbiAgICAgICAgICAgIDxkdD7QotC+0LrQtdC9INC00L48L2R0PlxuICAgICAgICAgICAgPGRkPnt7IGF1dGguZXhwaXJlc0F0KCk/LnRvTG9jYWxlVGltZVN0cmluZygpIHx8ICctJyB9fTwvZGQ+XG4gICAgICAgICAgPC9kaXY+XG4gICAgICAgIDwvZGw+XG4gICAgICA8L2FydGljbGU+XG5cbiAgICAgIDxhcnRpY2xlIGNsYXNzPVwicGFuZWwgcm9sZXMtcGFuZWxcIj5cbiAgICAgICAgPGRpdiBjbGFzcz1cInBhbmVsLXRpdGxlXCI+XG4gICAgICAgICAgPHNwYW4gY2xhc3M9XCJtYXRlcmlhbC1pY29ucy1zaGFycFwiPmFkbWluX3BhbmVsX3NldHRpbmdzPC9zcGFuPlxuICAgICAgICAgIDxkaXY+XG4gICAgICAgICAgICA8cCBjbGFzcz1cImV5ZWJyb3dcIj5BQ0NFU1M8L3A+XG4gICAgICAgICAgICA8aDI+0KDQvtC70Lg8L2gyPlxuICAgICAgICAgIDwvZGl2PlxuICAgICAgICA8L2Rpdj5cblxuICAgICAgICA8ZGl2IGNsYXNzPVwiY2hpcC1saXN0XCI+XG4gICAgICAgICAgQGZvciAocm9sZSBvZiBidXNpbmVzc1JvbGVzKCk7IHRyYWNrIHJvbGUpIHtcbiAgICAgICAgICAgIDxzcGFuPnt7IHJvbGUgfX08L3NwYW4+XG4gICAgICAgICAgfSBAZW1wdHkge1xuICAgICAgICAgICAgPHNwYW4+0J3QtdGCINCx0LjQt9C90LXRgS3RgNC+0LvQtdC5PC9zcGFuPlxuICAgICAgICAgIH1cbiAgICAgICAgPC9kaXY+XG4gICAgICA8L2FydGljbGU+XG4gICAgPC9zZWN0aW9uPlxuXG4gICAgPHNlY3Rpb24gY2xhc3M9XCJwYW5lbFwiPlxuICAgICAgPGRpdiBjbGFzcz1cInBhbmVsLXRpdGxlXCI+XG4gICAgICAgIDxzcGFuIGNsYXNzPVwibWF0ZXJpYWwtaWNvbnMtc2hhcnBcIj5hcHBzPC9zcGFuPlxuICAgICAgICA8ZGl2PlxuICAgICAgICAgIDxwIGNsYXNzPVwiZXllYnJvd1wiPlNFQ1RJT05TPC9wPlxuICAgICAgICAgIDxoMj7QkdGL0YHRgtGA0YvQtSDQtNC10LnRgdGC0LLQuNGPPC9oMj5cbiAgICAgICAgPC9kaXY+XG4gICAgICA8L2Rpdj5cblxuICAgICAgPGRpdiBjbGFzcz1cImFjdGlvbi1ncmlkXCI+XG4gICAgICAgIEBmb3IgKGFjdGlvbiBvZiB2aXNpYmxlQWN0aW9ucygpOyB0cmFjayBhY3Rpb24ubGFiZWwpIHtcbiAgICAgICAgICBAaWYgKGFjdGlvbi5yb3V0ZXJMaW5rKSB7XG4gICAgICAgICAgICA8YSBjbGFzcz1cImFjdGlvbi1jYXJkXCIgW3JvdXRlckxpbmtdPVwiYWN0aW9uLnJvdXRlckxpbmtcIj5cbiAgICAgICAgICAgICAgPHNwYW4gY2xhc3M9XCJtYXRlcmlhbC1pY29ucy1zaGFycFwiPnt7IGFjdGlvbi5pY29uIH19PC9zcGFuPlxuICAgICAgICAgICAgICA8c3Ryb25nPnt7IGFjdGlvbi5sYWJlbCB9fTwvc3Ryb25nPlxuICAgICAgICAgICAgICA8c21hbGw+e3sgYWN0aW9uLmRlc2NyaXB0aW9uIH19PC9zbWFsbD5cbiAgICAgICAgICAgIDwvYT5cbiAgICAgICAgICB9IEBlbHNlIHtcbiAgICAgICAgICAgIDxhIGNsYXNzPVwiYWN0aW9uLWNhcmRcIiBbaHJlZl09XCJhY3Rpb24uaHJlZlwiPlxuICAgICAgICAgICAgICA8c3BhbiBjbGFzcz1cIm1hdGVyaWFsLWljb25zLXNoYXJwXCI+e3sgYWN0aW9uLmljb24gfX08L3NwYW4+XG4gICAgICAgICAgICAgIDxzdHJvbmc+e3sgYWN0aW9uLmxhYmVsIH19PC9zdHJvbmc+XG4gICAgICAgICAgICAgIDxzbWFsbD57eyBhY3Rpb24uZGVzY3JpcHRpb24gfX08L3NtYWxsPlxuICAgICAgICAgICAgPC9hPlxuICAgICAgICAgIH1cbiAgICAgICAgfSBAZW1wdHkge1xuICAgICAgICAgIDxkaXYgY2xhc3M9XCJlbXB0eS1pbmxpbmVcIj5cbiAgICAgICAgICAgIDxzcGFuIGNsYXNzPVwibWF0ZXJpYWwtaWNvbnMtc2hhcnBcIj5sb2NrPC9zcGFuPlxuICAgICAgICAgICAgPHA+0JTQu9GPINGC0LLQvtC10Lkg0YDQvtC70Lgg0L/QvtC60LAg0L3QtdGCINCx0YvRgdGC0YDRi9GFINC00LXQudGB0YLQstC40LkuPC9wPlxuICAgICAgICAgIDwvZGl2PlxuICAgICAgICB9XG4gICAgICA8L2Rpdj5cbiAgICA8L3NlY3Rpb24+XG4gIH0gQGVsc2Uge1xuICAgIDxzZWN0aW9uIGNsYXNzPVwicGFuZWwgc3RhcnQtaWxsdXN0cmF0aW9uLXBhbmVsXCIgYXJpYS1sYWJlbD1cItCg0LDQsdC+0YfQtdC1INC/0YDQvtGB0YLRgNCw0L3RgdGC0LLQviDQmtC+0LzQv9Cw0L3QuNGPINCeIVwiPlxuICAgICAgPGltZyBzcmM9XCIvYXNzZXRzL2ltYWdlcy9UYWNvQ2xvdWQucG5nXCIgYWx0PVwi0JrQvtC80LDQvdC00LAg0YDQsNCx0L7RgtCw0LXRgiDRgSDQvtGC0LfRi9Cy0LDQvNC4INC4INC60LvQuNC10L3RgtGB0LrQuNC80Lgg0LfQsNC00LDRh9Cw0LzQuFwiPlxuICAgIDwvc2VjdGlvbj5cbiAgfVxuPC9hcHAtYWRtaW4tbGF5b3V0PlxuIiwiaW1wb3J0IHsgQ29tcG9uZW50LCBJbnB1dCB9IGZyb20gJ0Bhbmd1bGFyL2NvcmUnO1xuaW1wb3J0IHsgUm91dGVyTGluayB9IGZyb20gJ0Bhbmd1bGFyL3JvdXRlcic7XG5pbXBvcnQge1xuICBDQUJJTkVUX05BVklHQVRJT05fTElOS1MsXG4gIENhYmluZXROYXZpZ2F0aW9uTGluayxcbiAgdmlzaWJsZUNhYmluZXROYXZpZ2F0aW9uTGlua3Ncbn0gZnJvbSAnLi9jYWJpbmV0LW5hdmlnYXRpb24nO1xuXG5AQ29tcG9uZW50KHtcbiAgc2VsZWN0b3I6ICdhcHAtY2FiaW5ldC1uYXZpZ2F0aW9uJyxcbiAgaW1wb3J0czogW1JvdXRlckxpbmtdLFxuICB0ZW1wbGF0ZTogYFxuICAgIEBsZXQgbmF2aWdhdGlvbkxpbmtzID0gdmlzaWJsZU5hdmlnYXRpb25MaW5rcygpO1xuICAgIEBpZiAobmF2aWdhdGlvbkxpbmtzLmxlbmd0aCkge1xuICAgICAgPG5hdiBjbGFzcz1cImNhYmluZXQtbmF2LWJsb2NrXCIgYXJpYS1sYWJlbD1cItCg0LDQt9C00LXQu9GLINC60LDQsdC40L3QtdGC0LBcIj5cbiAgICAgICAgQGZvciAobGluayBvZiBuYXZpZ2F0aW9uTGlua3M7IHRyYWNrIGxpbmsubGFiZWwpIHtcbiAgICAgICAgICBAaWYgKGxpbmsucm91dGVyTGluaykge1xuICAgICAgICAgICAgPGEgY2xhc3M9XCJjYWJpbmV0LW5hdi1jYXJkXCIgW3JvdXRlckxpbmtdPVwibGluay5yb3V0ZXJMaW5rXCIgW2NsYXNzLmFjdGl2ZV09XCJhY3RpdmUgPT09IGxpbmsuYWN0aXZlXCI+XG4gICAgICAgICAgICAgIDxzcGFuIGNsYXNzPVwibWF0ZXJpYWwtaWNvbnMtc2hhcnBcIj57eyBsaW5rLmljb24gfX08L3NwYW4+XG4gICAgICAgICAgICAgIDxzdHJvbmc+e3sgbGluay5sYWJlbCB9fTwvc3Ryb25nPlxuICAgICAgICAgICAgICA8c21hbGw+e3sgbGluay5kZXNjcmlwdGlvbiB9fTwvc21hbGw+XG4gICAgICAgICAgICA8L2E+XG4gICAgICAgICAgfSBAZWxzZSB7XG4gICAgICAgICAgICA8YSBjbGFzcz1cImNhYmluZXQtbmF2LWNhcmRcIiBbaHJlZl09XCJsaW5rLmhyZWZcIiBbY2xhc3MuYWN0aXZlXT1cImFjdGl2ZSA9PT0gbGluay5hY3RpdmVcIj5cbiAgICAgICAgICAgICAgPHNwYW4gY2xhc3M9XCJtYXRlcmlhbC1pY29ucy1zaGFycFwiPnt7IGxpbmsuaWNvbiB9fTwvc3Bhbj5cbiAgICAgICAgICAgICAgPHN0cm9uZz57eyBsaW5rLmxhYmVsIH19PC9zdHJvbmc+XG4gICAgICAgICAgICAgIDxzbWFsbD57eyBsaW5rLmRlc2NyaXB0aW9uIH19PC9zbWFsbD5cbiAgICAgICAgICAgIDwvYT5cbiAgICAgICAgICB9XG4gICAgICAgIH1cbiAgICAgIDwvbmF2PlxuICAgIH1cbiAgYCxcbiAgc3R5bGVzOiBbYFxuICAgIDpob3N0IHtcbiAgICAgIGRpc3BsYXk6IGJsb2NrO1xuICAgICAgbWFyZ2luLXRvcDogMS4wNXJlbTtcbiAgICB9XG5cbiAgICAuY2FiaW5ldC1uYXYtYmxvY2sge1xuICAgICAgZGlzcGxheTogZ3JpZDtcbiAgICAgIGdyaWQtdGVtcGxhdGUtY29sdW1uczogcmVwZWF0KDQsIG1pbm1heCgwLCAxZnIpKTtcbiAgICAgIGdhcDogMC45cmVtO1xuICAgIH1cblxuICAgIC5jYWJpbmV0LW5hdi1jYXJkIHtcbiAgICAgIGRpc3BsYXk6IGdyaWQ7XG4gICAgICBtaW4taGVpZ2h0OiA3LjFyZW07XG4gICAgICBhbGlnbi1jb250ZW50OiBzdGFydDtcbiAgICAgIGdhcDogMC4zNXJlbTtcbiAgICAgIGJvcmRlcjogMXB4IHNvbGlkIHJnYmEoMTAzLCAxMTYsIDEzMSwgMC4xOCk7XG4gICAgICBib3JkZXItcmFkaXVzOiAwLjVyZW07XG4gICAgICBwYWRkaW5nOiAxcmVtO1xuICAgICAgY29sb3I6IHZhcigtLW90eml2LWRhcmspO1xuICAgICAgYmFja2dyb3VuZDogdmFyKC0tb3R6aXYtd2hpdGUpO1xuICAgICAgYm94LXNoYWRvdzogdmFyKC0tb3R6aXYtc2hhZG93KTtcbiAgICAgIHRleHQtZGVjb3JhdGlvbjogbm9uZTtcbiAgICAgIHRyYW5zaXRpb246IGJvcmRlci1jb2xvciAwLjJzIGVhc2UsIGNvbG9yIDAuMnMgZWFzZSwgdHJhbnNmb3JtIDAuMnMgZWFzZTtcbiAgICB9XG5cbiAgICAuY2FiaW5ldC1uYXYtY2FyZDpob3ZlcixcbiAgICAuY2FiaW5ldC1uYXYtY2FyZC5hY3RpdmUge1xuICAgICAgYm9yZGVyLWNvbG9yOiByZ2JhKDEwOCwgMTU1LCAyMDcsIDAuNTUpO1xuICAgICAgY29sb3I6IHZhcigtLW90eml2LXByaW1hcnkpO1xuICAgICAgdHJhbnNmb3JtOiB0cmFuc2xhdGVZKC0xcHgpO1xuICAgIH1cblxuICAgIC5jYWJpbmV0LW5hdi1jYXJkLmFjdGl2ZSB7XG4gICAgICBiYWNrZ3JvdW5kOiB2YXIoLS1vdHppdi1saWdodCk7XG4gICAgfVxuXG4gICAgLmNhYmluZXQtbmF2LWNhcmQgPiBzcGFuIHtcbiAgICAgIGNvbG9yOiB2YXIoLS1vdHppdi1wcmltYXJ5KTtcbiAgICAgIGZvbnQtc2l6ZTogMnJlbTtcbiAgICB9XG5cbiAgICAuY2FiaW5ldC1uYXYtY2FyZCBzdHJvbmcsXG4gICAgLmNhYmluZXQtbmF2LWNhcmQgc21hbGwge1xuICAgICAgbWluLXdpZHRoOiAwO1xuICAgICAgb3ZlcmZsb3ctd3JhcDogYW55d2hlcmU7XG4gICAgICBsaW5lLWhlaWdodDogMS4yNTtcbiAgICB9XG5cbiAgICAuY2FiaW5ldC1uYXYtY2FyZCBzbWFsbCB7XG4gICAgICBjb2xvcjogdmFyKC0tb3R6aXYtaW5mbyk7XG4gICAgICBmb250LXdlaWdodDogNzAwO1xuICAgIH1cblxuICAgIEBtZWRpYSAobWF4LXdpZHRoOiAxMTIwcHgpIHtcbiAgICAgIC5jYWJpbmV0LW5hdi1ibG9jayB7XG4gICAgICAgIGdyaWQtdGVtcGxhdGUtY29sdW1uczogcmVwZWF0KDIsIG1pbm1heCgwLCAxZnIpKTtcbiAgICAgIH1cbiAgICB9XG5cbiAgICBAbWVkaWEgKG1heC13aWR0aDogNjQwcHgpIHtcbiAgICAgIC5jYWJpbmV0LW5hdi1ibG9jayB7XG4gICAgICAgIGdyaWQtdGVtcGxhdGUtY29sdW1uczogMWZyO1xuICAgICAgfVxuICAgIH1cbiAgYF1cbn0pXG5leHBvcnQgY2xhc3MgQ2FiaW5ldE5hdmlnYXRpb25Db21wb25lbnQge1xuICBASW5wdXQoKSByb2xlczogc3RyaW5nW10gPSBbXTtcbiAgQElucHV0KCkgYWN0aXZlID0gJyc7XG4gIEBJbnB1dCgpIGxpbmtzOiBDYWJpbmV0TmF2aWdhdGlvbkxpbmtbXSA9IENBQklORVRfTkFWSUdBVElPTl9MSU5LUztcblxuICB2aXNpYmxlTmF2aWdhdGlvbkxpbmtzKCk6IENhYmluZXROYXZpZ2F0aW9uTGlua1tdIHtcbiAgICByZXR1cm4gdmlzaWJsZUNhYmluZXROYXZpZ2F0aW9uTGlua3ModGhpcy5yb2xlcywgdGhpcy5saW5rcyk7XG4gIH1cbn1cbiIsImltcG9ydCB7IEh0dHBDbGllbnQgfSBmcm9tICdAYW5ndWxhci9jb21tb24vaHR0cCc7XG5pbXBvcnQgeyBJbmplY3RhYmxlIH0gZnJvbSAnQGFuZ3VsYXIvY29yZSc7XG5pbXBvcnQgeyBPYnNlcnZhYmxlIH0gZnJvbSAncnhqcyc7XG5pbXBvcnQgeyBhcHBFbnZpcm9ubWVudCB9IGZyb20gJy4vYXBwLWVudmlyb25tZW50JztcblxuZXhwb3J0IGludGVyZmFjZSBDdXJyZW50VXNlciB7XG4gIGF1dGhlbnRpY2F0ZWQ6IGJvb2xlYW47XG4gIG5hbWU6IHN0cmluZztcbiAgcHJpbmNpcGFsVHlwZTogc3RyaW5nO1xuICBhdXRob3JpdGllczogc3RyaW5nW107XG4gIHN1YmplY3Q/OiBzdHJpbmc7XG4gIGlzc3Vlcj86IHN0cmluZztcbiAgcHJlZmVycmVkVXNlcm5hbWU/OiBzdHJpbmc7XG4gIGVtYWlsPzogc3RyaW5nO1xuICBjbGllbnRJZD86IHN0cmluZztcbiAgYXV0aG9yaXplZFBhcnR5Pzogc3RyaW5nO1xuICByZWFsbVJvbGVzPzogc3RyaW5nW107XG59XG5cbkBJbmplY3RhYmxlKHsgcHJvdmlkZWRJbjogJ3Jvb3QnIH0pXG5leHBvcnQgY2xhc3MgQ3VycmVudFVzZXJBcGkge1xuICBjb25zdHJ1Y3Rvcihwcml2YXRlIHJlYWRvbmx5IGh0dHA6IEh0dHBDbGllbnQpIHt9XG5cbiAgZ2V0TWUoKTogT2JzZXJ2YWJsZTxDdXJyZW50VXNlcj4ge1xuICAgIHJldHVybiB0aGlzLmh0dHAuZ2V0PEN1cnJlbnRVc2VyPihgJHthcHBFbnZpcm9ubWVudC5hcGlCYXNlVXJsfS9hcGkvbWVgKTtcbiAgfVxufVxuIiwiaW1wb3J0IHsgSHR0cENsaWVudCB9IGZyb20gJ0Bhbmd1bGFyL2NvbW1vbi9odHRwJztcbmltcG9ydCB7IEluamVjdGFibGUgfSBmcm9tICdAYW5ndWxhci9jb3JlJztcbmltcG9ydCB7IE9ic2VydmFibGUgfSBmcm9tICdyeGpzJztcbmltcG9ydCB7IGFwcEVudmlyb25tZW50IH0gZnJvbSAnLi9hcHAtZW52aXJvbm1lbnQnO1xuXG5leHBvcnQgaW50ZXJmYWNlIFN5c3RlbUhlYWx0aCB7XG4gIHN0YXR1czogc3RyaW5nO1xuICBjb21wb25lbnRzPzogUmVjb3JkPHN0cmluZywgeyBzdGF0dXM6IHN0cmluZzsgZGV0YWlscz86IFJlY29yZDxzdHJpbmcsIHVua25vd24+IH0+O1xufVxuXG5ASW5qZWN0YWJsZSh7IHByb3ZpZGVkSW46ICdyb290JyB9KVxuZXhwb3J0IGNsYXNzIFN5c3RlbUhlYWx0aEFwaSB7XG4gIGNvbnN0cnVjdG9yKHByaXZhdGUgcmVhZG9ubHkgaHR0cDogSHR0cENsaWVudCkge31cblxuICBnZXRIZWFsdGgoKTogT2JzZXJ2YWJsZTxTeXN0ZW1IZWFsdGg+IHtcbiAgICByZXR1cm4gdGhpcy5odHRwLmdldDxTeXN0ZW1IZWFsdGg+KGAke2FwcEVu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Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7OztHQUFBLFFBQUEsQ0FBQSwwME1BQUEsRUFBQSxDQUFBOzs7O2lGQUlVLGVBQWEsRUFBQSxXQUFBLGlCQUFBLFVBQUEsMkNBQUEsWUFBQSxHQUFBLENBQUE7QUFBQSxHQUFBOzs7Ozs7OytEQUFiLGVBQWEsRUFBQSxTQUFBLENBQUFFLEtBQUEsSUFBQSxzQkFBQSwwQkFBQSwyQkFBQSxxQkFBQSx1QkFBQSxFQUFBLEdBQUEsQ0FBQSxzQkFBQSw0QkFBQSxhQUFBRixhQUFBLDBCQUFBLDJCQUFBQyxVQUFBLEdBQUEsYUFBQSxFQUFBLENBQUE7RUFBQTtBQUFBLEdBQUEsT0FBQSxjQUFBLGVBQUEsY0FBQSxzQkFBQSxLQUFBLElBQUEsQ0FBQTtBQUFBLEdBQUEsT0FBQSxjQUFBLGVBQUEsZUFBQSxZQUFBLE9BQUEsWUFBQSxJQUFBLEdBQUEsNEJBQUEsT0FBQSxFQUFBLE9BQUEsTUFBQSxzQkFBQSxFQUFBLFNBQUEsQ0FBQTtBQUFBLEdBQUE7IiwibmFtZXMiOlsiQ29tcG9uZW50IiwiUm91dGVyTGluayIsIkluamVjdGFibGUiLCJJbmplY3RhYmxlIiwiX2ZvclRyYWNrMCIsIlJvdXRlckxpbmsiLCJDb21wb25lbnQiLCJpMCJdfQ==