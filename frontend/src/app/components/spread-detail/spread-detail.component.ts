import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SpreadOpportunity } from '../../models/spread.model';
import { getSpreadState, getStateClasses, getStateLabel, getIndicatorEmoji, getStateTextClass } from '../../utils/spread-state';

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

  getState(netPercent: number) {
    return getSpreadState(netPercent);
  }

  getClasses(netPercent: number) {
    return getStateClasses(this.getState(netPercent));
  }

  getLabel(netPercent: number) {
    return getStateLabel(this.getState(netPercent), netPercent);
  }

  getEmoji(netPercent: number) {
    return getIndicatorEmoji(this.getState(netPercent));
  }

  getTextClass(netPercent: number) {
    return getStateTextClass(this.getState(netPercent));
  }
}
