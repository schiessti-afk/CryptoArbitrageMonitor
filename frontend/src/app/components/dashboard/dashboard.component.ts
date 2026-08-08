import { Component, OnInit, OnDestroy, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { WebsocketService } from '../../services/websocket.service';
import { SpreadDetailComponent } from '../spread-detail/spread-detail.component';
import { SpreadTableComponent } from '../spread-table/spread-table.component';
import { ConnectionStatusComponent } from '../connection-status/connection-status.component';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    SpreadDetailComponent,
    SpreadTableComponent,
    ConnectionStatusComponent
  ],
  templateUrl: './dashboard.component.html',
  styles: []
})
export class DashboardComponent implements OnInit, OnDestroy {
  notional = signal(1000);
  config = signal<any>(null);

  opportunities = computed(() => this.websocket.snapshot()?.bestPerSymbol ?? []);
  matrix = computed(() => this.websocket.snapshot()?.matrix ?? []);

  quickSelectAmounts = [100, 1000, 5000, 10000, 50000];

  constructor(
    public websocket: WebsocketService,
    private http: HttpClient
  ) {}

  ngOnInit() {
    this.http.get<any>('/api/config').subscribe(
      cfg => {
        this.config.set(cfg);
        if (cfg.defaultNotional) {
          this.notional.set(cfg.defaultNotional);
        }
      },
      error => {
        console.warn('Failed to fetch config, using defaults:', error);
        // Use defaults if endpoint fails
        this.config.set({
          defaultNotional: 1000,
          freshnessWindowMs: 10000,
          neutralEpsilonPercent: 0.001,
          fees: []
        });
      }
    );
    this.websocket.connect();
  }

  ngOnDestroy() {
    this.websocket.disconnect();
  }

  setNotional(amount: number) {
    this.notional.set(amount);
  }
}
