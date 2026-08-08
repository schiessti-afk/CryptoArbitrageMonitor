import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SpreadOpportunity } from '../../models/spread.model';

@Component({
  selector: 'app-spread-table',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './spread-table.component.html',
  styles: []
})
export class SpreadTableComponent {
  @Input() matrix: SpreadOpportunity[] = [];
  @Input() notional = 1000;
}
