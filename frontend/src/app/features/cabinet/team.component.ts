import { Component, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { appEnvironment } from '../../core/app-environment';
import { CabinetApi, TeamMember, TeamResponse } from '../../core/cabinet.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';

type TeamRole = 'manager' | 'marketolog' | 'worker' | 'operator';

type TeamSection = {
  key: TeamRole;
  title: string;
  icon: string;
};

@Component({
  selector: 'app-team',
  imports: [AdminLayoutComponent, FormsModule, RouterLink],
  templateUrl: './team.component.html',
  styleUrl: './team.component.scss'
})
export class TeamComponent {
  readonly selectedDate = signal(this.todayIso());
  readonly team = signal<TeamResponse | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  readonly sections: TeamSection[] = [
    { key: 'manager', title: 'Менеджеры', icon: 'groups' },
    { key: 'marketolog', title: 'Маркетологи', icon: 'campaign' },
    { key: 'worker', title: 'Работники', icon: 'engineering' },
    { key: 'operator', title: 'Операторы', icon: 'support_agent' }
  ];

  constructor(private readonly cabinetApi: CabinetApi) {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);

    this.cabinetApi.getTeam(this.selectedDate()).subscribe({
      next: (response) => {
        this.team.set(response);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(error?.error?.message ?? error?.message ?? 'Не удалось загрузить команду');
        this.loading.set(false);
      }
    });
  }

  members(section: TeamSection): TeamMember[] {
    const team = this.team();
    if (!team) {
      return [];
    }

    switch (section.key) {
      case 'manager':
        return team.managers;
      case 'marketolog':
        return team.marketologs;
      case 'worker':
        return team.workers;
      case 'operator':
        return team.operators;
    }
  }

  statRows(role: TeamRole, member: TeamMember): Array<{ label: string; value: string }> {
    if (!this.hasStats(member)) {
      return [];
    }

    if (role === 'manager') {
      return [
        { label: 'ЗП', value: this.money(member.sum1Month) },
        { label: 'Выручка', value: this.money(member.payment1Month) },
        { label: 'Заказы', value: this.count(member.order1Month) },
        { label: 'Отзывы', value: this.count(member.review1Month) }
      ];
    }

    if (role === 'worker') {
      return [
        { label: 'ЗП', value: this.money(member.sum1Month) },
        { label: 'Заказы', value: this.count(member.order1Month) },
        { label: 'Отзывы', value: this.count(member.review1Month) },
        { label: 'В работе', value: this.count((member.newOrder || 0) + (member.inCorrect || 0) + (member.intVigul || 0) + (member.publish || 0)) }
      ];
    }

    return [
      { label: 'ЗП', value: this.money(member.sum1Month) },
      { label: 'Новые', value: this.count(member.leadsNew) },
      { label: 'В работе', value: this.count(member.leadsInWork) },
      { label: 'Конверсия', value: `${member.percentInWork || 0}%` }
    ];
  }

  statusRows(member: TeamMember): Array<{ label: string; value: number }> {
    return [
      { label: 'Новые', value: member.newOrder || 0 },
      { label: 'Коррекция', value: member.inCorrect || 0 },
      { label: 'Выгул', value: member.intVigul || 0 },
      { label: 'Публикация', value: member.publish || 0 }
    ].filter((row) => row.value > 0);
  }

  imageUrl(imageId?: number | null): string {
    return this.cabinetApi.imageUrl(imageId);
  }

  editUrl(userId: number): string {
    return `${appEnvironment.legacyBaseUrl}/allUsers/${userId}/edit`;
  }

  addUserUrl(): string {
    return `${appEnvironment.legacyBaseUrl}/allUsers`;
  }

  memberTrack(member: TeamMember): number {
    return member.userId;
  }

  private hasStats(member: TeamMember): boolean {
    return [
      member.sum1Month,
      member.order1Month,
      member.review1Month,
      member.payment1Month,
      member.leadsNew,
      member.leadsInWork,
      member.newOrder,
      member.inCorrect,
      member.intVigul,
      member.publish
    ].some((value) => Number(value || 0) > 0);
  }

  private money(value?: number | null): string {
    return `${new Intl.NumberFormat('ru-RU').format(value || 0)} руб.`;
  }

  private count(value?: number | null): string {
    return `${new Intl.NumberFormat('ru-RU').format(value || 0)} шт.`;
  }

  private todayIso(): string {
    return new Date().toISOString().slice(0, 10);
  }
}
