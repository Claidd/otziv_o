import { Component, EventEmitter, Input, Output } from '@angular/core';

@Component({
  selector: 'app-load-error-card',
  templateUrl: './load-error-card.component.html',
  styleUrl: './load-error-card.component.scss'
})
export class LoadErrorCardComponent {
  @Input() title = 'Данные не загрузились';
  @Input() message = '';
  @Input() icon = 'cloud_off';
  @Input() actionLabel = '';
  @Input() loading = false;
  @Input() disabled = false;

  @Output() retry = new EventEmitter<void>();
}
