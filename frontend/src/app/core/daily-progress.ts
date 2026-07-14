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
  loadScore: number;
  efficiencyScore: number;
}
