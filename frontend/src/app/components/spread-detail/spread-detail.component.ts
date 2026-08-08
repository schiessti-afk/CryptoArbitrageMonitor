import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SpreadOpportunity } from '../../models/spread.model';

@Component({
  selector: 'app-spread-detail',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './spread-detail.component.html',
  styles: []
})
export class SpreadDetailComponent {
  @Input() opportunities: SpreadOpportunity[] = [];
  @Input() notional = 1000;

  estimatedProfit(opp: SpreadOpportunity): number {
    return (this.notional * opp.netSpreadPercent) / 100;
  }
}
