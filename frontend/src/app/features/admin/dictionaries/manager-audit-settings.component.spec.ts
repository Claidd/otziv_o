import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ManagerReportReviewSettings, ManagerReportReviewSettingsApi } from '../../../core/manager-report-review-settings.api';
import { ToastService } from '../../../shared/toast.service';
import { DEFAULT_MANAGER_EVIDENCE_SETTINGS, ManagerAuditSettingsComponent } from './manager-audit-settings.component';

const settings: ManagerReportReviewSettings = {
  enabled: true, managerGroupsEnabled: true, restrictionEnabled: true,
  maxQuestionCount: 8, minimumReadSeconds: 60, testMinimumReadSeconds: 10,
  reminderOneMinutes: 60, reminderThreeMinutes: 180, minimumAnswerScore: 75,
  maxAnswerCharacters: 420, maxPlanCharacters: 600, fastPasteSeconds: 12,
  fastPasteMinCharacters: 140, copyGramSize: 4, copySimilarityPercent: 65, aiTimeoutSeconds: 25,
  questionGenerationMaxTokens: 8000, questionGenerationRetryMaxTokens: 12000,
  evidence: { ...DEFAULT_MANAGER_EVIDENCE_SETTINGS },
  managers: [{ managerId: 2, managerName: 'Менеджер пилота', auditEnabled: true, userActive: true, auditGroupConnected: true }]
};

describe('ManagerAuditSettingsComponent evidence controls', () => {
  let api: { settings: ReturnType<typeof vi.fn>; update: ReturnType<typeof vi.fn> };
  beforeEach(() => {
    api = {
      settings: vi.fn(() => of(structuredClone(settings))),
      update: vi.fn((request) => of({ ...request, managers: settings.managers }))
    };
    TestBed.configureTestingModule({
      imports: [ManagerAuditSettingsComponent],
      providers: [
        { provide: ManagerReportReviewSettingsApi, useValue: api },
        { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn() } }
      ]
    });
  });

  function render() {
    const fixture = TestBed.createComponent(ManagerAuditSettingsComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('exposes separate diagnostic and answer modes, with retired heuristic controls removed', () => {
    const element: HTMLElement = render().nativeElement;
    expect(element.querySelector('[formcontrolname="diagnosisMode"]')).not.toBeNull();
    expect(element.querySelector('[formcontrolname="answerMode"]')).not.toBeNull();
    expect(element.querySelector('[formcontrolname="minimumAnswerScore"]')).toBeNull();
    expect(element.querySelector('[formcontrolname="fastPasteSeconds"]')).toBeNull();
    expect(element.textContent).toContain('автоматическое прохождение и блокировка доступа выключены');
  });

  it('does not save a pilot with no selected manager', () => {
    const fixture = render();
    fixture.componentInstance.form.controls.evidence.controls.answerMode.setValue('PILOT');
    fixture.componentInstance.save();
    expect(api.update).not.toHaveBeenCalled();
    expect(fixture.componentInstance.error()).toContain('Выберите хотя бы одного менеджера');
  });

  it('saves independent pilot choices and model budgets through the existing API', () => {
    const fixture = render();
    fixture.componentInstance.form.controls.evidence.patchValue({
      diagnosisMode: 'PILOT', diagnosisPilotManagerIds: [2], answerMode: 'ENFORCE',
      answerMaxTokens: 7000, answerTimeoutSeconds: 100, questionThinkingEnabled: false
    });
    fixture.componentInstance.save();
    expect(api.update).toHaveBeenCalledOnce();
    const request = api.update.mock.calls[0][0];
    expect(request.evidence).toMatchObject({
      diagnosisMode: 'PILOT', diagnosisPilotManagerIds: [2], answerMode: 'ENFORCE',
      answerMaxTokens: 7000, answerTimeoutSeconds: 100, questionThinkingEnabled: false
    });
    expect(request.enabled).toBe(true);
    expect(request.managers).toBeUndefined();
  });
});
