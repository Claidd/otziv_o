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
}

export interface ReputationReviewDraftRequest {
  productOrService?: string | null;
  realExperiencePoints?: string[];
  tone?: string | null;
  length?: string | null;
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
  openAiResearchReportProfiles: ReputationAiModelProfile[];
  openAiContentPackProfiles: ReputationAiModelProfile[];
  warnings: string[];
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

  latestDeepResearchJob(companyId: number): Observable<DeepCompanyResearchJob> {
    return this.http.get<DeepCompanyResearchJob>(`${this.baseUrl}/companies/${companyId}/deep-research/jobs/latest`);
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
