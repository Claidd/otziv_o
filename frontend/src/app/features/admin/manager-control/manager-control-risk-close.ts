import { WorkerRiskResolutionAction } from '../../../core/manager.api';

export function shouldImmediatelyCloseRiskControlCard(
  privileged: boolean,
  action: WorkerRiskResolutionAction,
  incidentStatus: string | null | undefined
): boolean {
  return privileged && action === 'VERIFIED' && incidentStatus !== 'OPEN';
}
