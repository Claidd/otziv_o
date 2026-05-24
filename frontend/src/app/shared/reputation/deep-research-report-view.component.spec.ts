import type { DeepCompanyResearchReport } from '../../core/reputation-ai.api';
import { DeepResearchReportViewComponent } from './deep-research-report-view.component';

describe('DeepResearchReportViewComponent', () => {
  it('does not render review ideas as a regular report block', () => {
    const component = new DeepResearchReportViewComponent();
    component.report = reportWithSections([
      { title: 'Краткая сводка', body: 'Факты о компании.' },
      { title: 'Идеи для отзывов', body: '1. Отзыв про сервис\n2. Отзыв про цену' }
    ], ['Отзыв про сервис', 'Отзыв про цену']);

    expect(component.reviewIdeas()).toEqual(['Отзыв про сервис', 'Отзыв про цену']);
    expect(component.deepReportBlocks().map((block) => block.title)).toEqual(['Краткая сводка']);
    expect(component.deepReportBlocks().map((block) => block.sectionIndex)).toEqual([0]);
  });

  it('extracts review ideas from the hidden report section when explicit ideas are missing', () => {
    const component = new DeepResearchReportViewComponent();
    component.report = reportWithSections([
      { title: 'Идеи для отзывов', body: '1. Отзыв про запись\n2. Отзыв про удобный адрес' },
      { title: 'Идеи для постов и карточки', body: 'Пост про акцию.' }
    ]);

    expect(component.reviewIdeas()).toEqual(['Отзыв про запись', 'Отзыв про удобный адрес']);
    expect(component.deepReportBlocks().map((block) => block.title)).toEqual(['Идеи для постов и карточки']);
    expect(component.deepReportBlocks().map((block) => block.sectionIndex)).toEqual([1]);
  });
});

function reportWithSections(
  sections: DeepCompanyResearchReport['sections'],
  reviewIdeas: string[] = []
): DeepCompanyResearchReport {
  return {
    companyId: 1,
    companyName: 'IQuest',
    city: 'Ангарск',
    provider: 'openai',
    model: 'gpt-5.5',
    responseId: 'resp_1',
    reportMarkdown: '',
    sections,
    sources: [],
    warnings: [],
    qualityChecks: [],
    factSnapshot: null,
    reviewIdeas,
    createdAt: '2026-05-24T10:00:00'
  };
}
