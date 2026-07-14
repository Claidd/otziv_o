export interface DailyWorkProgress {
  visible: boolean;
  roleType: 'MANAGER' | 'WORKER' | string;
  date: string;
  completed: number;
  active: number;
  total: number;
  percent: number;
  checked: boolean;
  firstCompletedAt?: string | null;
  lastCompletedAt?: string | null;
  averageCloseSeconds: number;
  medianCloseSeconds: number;
  p90CloseSeconds: number;
  firstActivityAt?: string | null;
  lastActivityAt?: string | null;
  activeWorkSeconds: number;
  workWindowSeconds: number;
  activityEvents: number;
  loadScore: number;
  efficiencyScore: number;
}
