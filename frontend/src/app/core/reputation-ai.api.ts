import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';

export interface ReputationResearchRequest {
  websiteOverride?: string | null;
  manualDescription?: string | null;
  productsOrServices?: string[];
  publicUrls?: string[];
  includeCompanyWebsite?: boolean;
  deepResearchProfile?: string | null;
  deepResearchMode?: 'full_report' | 'refresh_sources' | 'rebuild_text' | 'rebuild_section' | string | null;
  baseReportJobId?: number | null;
  sectionTitle?: string | null;
  sectionIndex?: number | null;
  enrichCollectionGaps?: boolean | null;
}

export interface ReputationContentPackRequest {
  productOrService?: string | null;
  manualDescription?: string | null;
  productsOrServices?: string[];
  publicUrls?: string[];
  includeCompanyWebsite?: boolean;
  adTextsCount?: number | null;
  socialPostsCount?: number | null;
  positiveReplyCount?: number | null;
  negativeReplyCount?: number | null;
  contentPackProfile?: string | null;
  deepReportJobId?: number | null;
}

export interface ReputationReviewDraftRequest {
  productOrService?: string | null;
  realExperiencePoints?: string[];
  tone?: string | null;
  length?: string | null;
}

export interface ReputationReviewTemplatesRequest {
  deepReportJobId?: number | null;
  contentPackJobId?: number | null;
  manualNotes?: string | null;
  topicsCount?: number | null;
  draftsCount?: number | null;
  tone?: string | null;
  contentPackProfile?: string | null;
}

export interface ReputationReviewTemplatesApplyRequest {
  contentPackJobId?: number | null;
  honestReviewTopics: string[];
  reviewDraftTemplates: string[];
}

export interface ReputationSingleReviewDraftRequest {
  deepReportJobId?: number | null;
  contentPackJobId?: number | null;
  idea?: string | null;
  style?: string | null;
  authorType?: string | null;
  emojiMode?: string | null;
  manualNotes?: string | null;
  length?: string | null;
  contentPackProfile?: string | null;
  targetReviewId?: number | null;
  previousDraft?: string | null;
  orderContext?: string | null;
}

export interface ReputationReviewCheckRequest {
  text: string;
  allowedFacts?: string[];
}

export interface ReputationReviewRewriteRequest {
  text: string;
  tone?: string | null;
}

export interface ReputationReviewReplyRequest {
  reviewText?: string | null;
  tone?: string | null;
  count?: number | null;
}

export interface CompanySource {
  type: string;
  title: string;
  url: string;
  excerpt: string;
}

export interface CompanyResearchAnswer {
  key: string;
  question: string;
  answer: string;
  evidence: string[];
  sourceUrls: string[];
  confidence: number;
  status: 'found' | 'partial' | 'missing' | string;
}

export interface ResearchSnapshot {
  companyId: number;
  companyName: string;
  city: string;
  website: string;
  category: string;
  subCategory: string;
  companyNotes: string;
  products: string[];
  advantages: string[];
  commonPositiveTopics: string[];
  commonNegativeTopics: string[];
  researchAnswers?: CompanyResearchAnswer[];
  sources: CompanySource[];
  searchProvider: string;
  searchAvailable: boolean;
  searchQueries: string[];
  searchResultsCount: number;
  websitePagesRead: number;
  warnings: string[];
  createdAt: string;
}

export interface DeepResearchSection {
  title: string;
  body: string;
}

export interface DeepResearchSource {
  title: string;
  url: string;
  note: string;
  type?: 'official_site' | 'map_card' | 'directory' | 'review_platform' | 'social' | 'legal' | 'aggregator' | 'media' | 'other' | string;
  usedFor?: string[];
  confidence?: 'high' | 'medium' | 'low' | string;
}

export interface DeepResearchQualityCheck {
  key: string;
  label: string;
  status: 'pass' | 'warn' | 'fail' | 'info' | string;
  detail: string;
}

export interface DeepResearchFactItem {
  label: string;
  value: string;
  evidence: string;
  confidence: 'high' | 'medium' | 'low' | string;
}

export interface DeepResearchSourceReview {
  title: string;
  url: string;
  status: 'trusted_public' | 'needs_review' | string;
  reason: string;
}

export interface DeepResearchFactSnapshot {
  confirmedFacts: DeepResearchFactItem[];
  uncertainFacts: DeepResearchFactItem[];
  sourceReviews: DeepResearchSourceReview[];
}

export interface DeepCompanyResearchReport {
  companyId: number;
  companyName: string;
  city: string;
  provider: string;
  model: string;
  responseId: string;
  reportMarkdown: string;
  sections: DeepResearchSection[];
  sources: DeepResearchSource[];
  warnings: string[];
  qualityChecks?: DeepResearchQualityCheck[];
  factSnapshot?: DeepResearchFactSnapshot | null;
  reviewIdeas?: string[];
  createdAt: string;
}

export type DeepResearchJobState = 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED' | string;

export interface DeepCompanyResearchJob {
  jobId: number;
  companyId: number;
  companyName: string;
  status: DeepResearchJobState;
  provider: string;
  model: string;
  responseId: string;
  errorMessage: string;
  report: DeepCompanyResearchReport | null;
  operation: 'full_report' | 'refresh_sources' | 'rebuild_text' | 'rebuild_section' | string;
  createdAt: string;
  updatedAt: string;
  startedAt: string | null;
  completedAt: string | null;
}

export interface ReputationAiStatus {
  aiProvider: string;
  aiAvailable: boolean;
  searchProvider: string;
  searchAvailable: boolean;
  yandexGptConfigured: boolean;
  yandexSearchConfigured: boolean;
  openAiConfigured: boolean;
  openAiProxyEnabled: boolean;
  yandexModel: string;
  openAiModel: string;
  openAiResearchReportModel: string;
  openAiContentPackModel: string;
  openAiDiagnostics: OpenAiProviderDiagnostics | null;
  openAiResearchReportProfiles: ReputationAiModelProfile[];
  openAiContentPackProfiles: ReputationAiModelProfile[];
  warnings: string[];
}

export interface OpenAiProviderDiagnostics {
  baseUrl: string;
  configured: boolean;
  proxyEnabled: boolean;
  proxyConfigured: boolean;
  proxyAuthConfigured: boolean;
  proxyHost: string;
  proxyPort: number;
  requestGoesThroughProxy: boolean;
  route: string;
  lastCheckStatus: string;
  lastHttpStatus: number | null;
  lastMessage: string;
  lastCheckedAt: string | null;
}

export interface ReputationAiModelProfile {
  key: string;
  label: string;
  model: string;
  description: string;
  maxToolCalls: number;
  maxOutputTokens: number;
  reasoningEffort: string;
  searchContextSize: string;
}

export interface ReputationAiPrompt {
  key: string;
  title: string;
  description: string;
  content: string;
  defaultContent: string;
  customized: boolean;
  updatedAt: string | null;
  requiredPlaceholders: string[];
  presets: ReputationAiPromptPreset[];
}

export interface ReputationAiPromptPreset {
  key: string;
  title: string;
  description: string;
}

export interface ReputationAiPromptValidation {
  key: string;
  valid: boolean;
  missingPlaceholders: string[];
  warnings: string[];
}

export interface ReputationAiPromptPreview {
  key: string;
  sampleName: string;
  renderedContent: string;
  replacedPlaceholders: string[];
  unresolvedPlaceholders: string[];
  warnings: string[];
}

export interface ReputationAiPromptVersion {
  id: number;
  key: string;
  action: string;
  actor: string;
  previousContent: string;
  content: string;
  createdAt: string;
}

export interface CompanyAiProfile {
  shortDescription: string;
  category: string;
  products: string[];
  advantages: string[];
  positiveReviewTopics: string[];
  negativeReviewTopics: string[];
  factualWarnings: string[];
}

export interface ReputationContentPack {
  researchSnapshot: ResearchSnapshot;
  companyProfile: CompanyAiProfile;
  utp: string[];
  adTexts: string[];
  socialPostTopics: string[];
  socialPosts: string[];
  honestReviewTopics: string[];
  reviewDraftTemplates: string[];
  positiveReviewReplies: string[];
  negativeReviewReplies: string[];
  sourceUrls: string[];
  safetyNotes: string[];
}

export type ContentPackJobState = 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED' | string;

export interface ReputationContentPackJob {
  jobId: number;
  companyId: number;
  companyName: string;
  status: ContentPackJobState;
  provider: string;
  model: string;
  errorMessage: string;
  pack: ReputationContentPack | null;
  createdAt: string;
  updatedAt: string;
  startedAt: string | null;
  completedAt: string | null;
}

export interface ReputationReviewTemplatesResult {
  companyId: number;
  companyName: string;
  deepReportJobId: number | null;
  contentPackJobId: number | null;
  provider: string;
  model: string;
  honestReviewTopics: string[];
  reviewDraftTemplates: string[];
  safetyNotes: string[];
  generatedAt: string;
}

export interface ReputationSingleReviewDraftResult {
  companyId: number;
  companyName: string;
  deepReportJobId: number | null;
  contentPackJobId: number | null;
  provider: string;
  model: string;
  idea: string;
  style: string;
  draft: string;
  sourceFacts: string[];
  safetyNotes: string[];
  safetyReport: ReviewSafetyReport;
  generatedAt: string;
}

export interface ReviewSafetyReport {
  safeToUseAsDraft: boolean;
  riskScore: number;
  warnings: string[];
  suggestions: string[];
}

export interface ReviewDraftResult {
  draft: string;
  warning: string;
  usedExperiencePoints: string[];
  safetyReport: ReviewSafetyReport;
}

export interface ReputationReviewRewriteResponse {
  rewrittenText: string;
  safetyReport: ReviewSafetyReport;
}

export interface ReputationReviewReplyResponse {
  replies: string[];
  warning: string;
}

@Injectable({ providedIn: 'root' })
export class ReputationAiApi {
  private readonly baseUrl = `${appEnvironment.apiBaseUrl}/api/ai/reputation`;

  constructor(private readonly http: HttpClient) {}

  status(): Observable<ReputationAiStatus> {
    return this.http.get<ReputationAiStatus>(`${this.baseUrl}/status`);
  }

  checkOpenAiRoute(): Observable<OpenAiProviderDiagnostics> {
    return this.http.post<OpenAiProviderDiagnostics>(`${this.baseUrl}/status/openai-check`, {});
  }

  prompts(): Observable<ReputationAiPrompt[]> {
    return this.http.get<ReputationAiPrompt[]>(`${this.baseUrl}/prompts`);
  }

  promptHistory(key: string, limit = 8): Observable<ReputationAiPromptVersion[]> {
    return this.http.get<ReputationAiPromptVersion[]>(
      `${this.baseUrl}/prompts/${encodeURIComponent(key)}/history`,
      { params: { limit } }
    );
  }

  validatePrompt(key: string, content: string): Observable<ReputationAiPromptValidation> {
    return this.http.post<ReputationAiPromptValidation>(
      `${this.baseUrl}/prompts/${encodeURIComponent(key)}/validate`,
      { content }
    );
  }

  previewPrompt(key: string, content: string): Observable<ReputationAiPromptPreview> {
    return this.http.post<ReputationAiPromptPreview>(
      `${this.baseUrl}/prompts/${encodeURIComponent(key)}/preview`,
      { content }
    );
  }

  applyPromptPreset(key: string, presetKey: string): Observable<ReputationAiPrompt> {
    return this.http.post<ReputationAiPrompt>(
      `${this.baseUrl}/prompts/${encodeURIComponent(key)}/presets/${encodeURIComponent(presetKey)}`,
      {}
    );
  }

  updatePrompt(key: string, content: string): Observable<ReputationAiPrompt> {
    return this.http.put<ReputationAiPrompt>(`${this.baseUrl}/prompts/${encodeURIComponent(key)}`, { content });
  }

  resetPrompt(key: string): Observable<ReputationAiPrompt> {
    return this.http.delete<ReputationAiPrompt>(`${this.baseUrl}/prompts/${encodeURIComponent(key)}`);
  }

  createResearch(companyId: number, request: ReputationResearchRequest): Observable<ResearchSnapshot> {
    return this.http.post<ResearchSnapshot>(`${this.baseUrl}/companies/${companyId}/research`, request);
  }

  latestResearch(companyId: number): Observable<ResearchSnapshot> {
    return this.http.get<ResearchSnapshot>(`${this.baseUrl}/companies/${companyId}/research/latest`);
  }

  createDeepResearch(companyId: number, request: ReputationResearchRequest): Observable<DeepCompanyResearchReport> {
    return this.http.post<DeepCompanyResearchReport>(`${this.baseUrl}/companies/${companyId}/deep-research`, request);
  }

  startDeepResearchJob(companyId: number, request: ReputationResearchRequest): Observable<DeepCompanyResearchJob> {
    return this.http.post<DeepCompanyResearchJob>(`${this.baseUrl}/companies/${companyId}/deep-research/jobs`, request);
  }

  refreshDeepResearchSourcesJob(companyId: number, request: ReputationResearchRequest): Observable<DeepCompanyResearchJob> {
    return this.http.post<DeepCompanyResearchJob>(
      `${this.baseUrl}/companies/${companyId}/deep-research/jobs/refresh-sources`,
      request
    );
  }

  rebuildDeepResearchTextJob(companyId: number, request: ReputationResearchRequest): Observable<DeepCompanyResearchJob> {
    return this.http.post<DeepCompanyResearchJob>(
      `${this.baseUrl}/companies/${companyId}/deep-research/jobs/rebuild-text`,
      request
    );
  }

  rebuildDeepResearchSectionJob(companyId: number, request: ReputationResearchRequest): Observable<DeepCompanyResearchJob> {
    return this.http.post<DeepCompanyResearchJob>(
      `${this.baseUrl}/companies/${companyId}/deep-research/jobs/rebuild-section`,
      request
    );
  }

  latestDeepResearchJob(companyId: number): Observable<DeepCompanyResearchJob> {
    return this.http.get<DeepCompanyResearchJob>(`${this.baseUrl}/companies/${companyId}/deep-research/jobs/latest`);
  }

  exportDeepResearchMarkdown(companyId: number, jobId?: number | null): Observable<string> {
    const jobPath = jobId ? `${jobId}` : 'latest';
    return this.http.get(`${this.baseUrl}/companies/${companyId}/deep-research/jobs/${jobPath}/export`, {
      responseType: 'text'
    });
  }

  exportDeepResearchPdf(companyId: number, jobId?: number | null): Observable<Blob> {
    const jobPath = jobId ? `${jobId}` : 'latest';
    return this.http.get(`${this.baseUrl}/companies/${companyId}/deep-research/jobs/${jobPath}/export/pdf`, {
      responseType: 'blob'
    });
  }

  deepResearchJobHistory(companyId: number, limit = 10): Observable<DeepCompanyResearchJob[]> {
    return this.http.get<DeepCompanyResearchJob[]>(
      `${this.baseUrl}/companies/${companyId}/deep-research/jobs/history`,
      { params: { limit } }
    );
  }

  updateDeepResearchReviewIdeas(companyId: number, jobId: number, reviewIdeas: string[]): Observable<DeepCompanyResearchJob> {
    return this.http.put<DeepCompanyResearchJob>(
      `${this.baseUrl}/companies/${companyId}/deep-research/jobs/${jobId}/review-ideas`,
      { reviewIdeas }
    );
  }

  createContentPack(companyId: number, request: ReputationContentPackRequest): Observable<ReputationContentPack> {
    return this.http.post<ReputationContentPack>(`${this.baseUrl}/companies/${companyId}/content-pack`, request);
  }

  startContentPackJob(companyId: number, request: ReputationContentPackRequest): Observable<ReputationContentPackJob> {
    return this.http.post<ReputationContentPackJob>(`${this.baseUrl}/companies/${companyId}/content-pack/jobs`, request);
  }

  latestContentPackJob(companyId: number): Observable<ReputationContentPackJob> {
    return this.http.get<ReputationContentPackJob>(`${this.baseUrl}/companies/${companyId}/content-pack/jobs/latest`);
  }

  exportContentPackMarkdown(companyId: number): Observable<string> {
    return this.http.get(`${this.baseUrl}/companies/${companyId}/content-pack/jobs/latest/export`, {
      responseType: 'text'
    });
  }

  exportContentPackPdf(companyId: number): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/companies/${companyId}/content-pack/jobs/latest/export/pdf`, {
      responseType: 'blob'
    });
  }

  improveReviewTemplates(companyId: number, request: ReputationReviewTemplatesRequest): Observable<ReputationReviewTemplatesResult> {
    return this.http.post<ReputationReviewTemplatesResult>(
      `${this.baseUrl}/companies/${companyId}/content-pack/review-templates`,
      request
    );
  }

  applyReviewTemplates(companyId: number, request: ReputationReviewTemplatesApplyRequest): Observable<ReputationContentPack> {
    return this.http.post<ReputationContentPack>(
      `${this.baseUrl}/companies/${companyId}/content-pack/review-templates/apply`,
      request
    );
  }

  createSingleReviewDraft(companyId: number, request: ReputationSingleReviewDraftRequest): Observable<ReputationSingleReviewDraftResult> {
    return this.http.post<ReputationSingleReviewDraftResult>(
      `${this.baseUrl}/companies/${companyId}/content-pack/review-draft`,
      request
    );
  }

  createReviewDraft(companyId: number, request: ReputationReviewDraftRequest): Observable<ReviewDraftResult> {
    return this.http.post<ReviewDraftResult>(`${this.baseUrl}/companies/${companyId}/review-draft`, request);
  }

  checkReview(request: ReputationReviewCheckRequest): Observable<ReviewSafetyReport> {
    return this.http.post<ReviewSafetyReport>(`${this.baseUrl}/review/check`, request);
  }

  rewriteReview(request: ReputationReviewRewriteRequest): Observable<ReputationReviewRewriteResponse> {
    return this.http.post<ReputationReviewRewriteResponse>(`${this.baseUrl}/review/rewrite`, request);
  }

  positiveReply(companyId: number, request: ReputationReviewReplyRequest): Observable<ReputationReviewReplyResponse> {
    return this.http.post<ReputationReviewReplyResponse>(`${this.baseUrl}/companies/${companyId}/reply/positive`, request);
  }

  negativeReply(companyId: number, request: ReputationReviewReplyRequest): Observable<ReputationReviewReplyResponse> {
    return this.http.post<ReputationReviewReplyResponse>(`${this.baseUrl}/companies/${companyId}/reply/negative`, request);
  }
}
