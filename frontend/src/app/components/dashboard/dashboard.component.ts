import { Component, OnInit, OnDestroy, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
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

  opportunities = computed(() => this.websocket.snapshot()?.bestPerSymbol ?? []);
  matrix = computed(() => this.websocket.snapshot()?.matrix ?? []);

  constructor(public websocket: WebsocketService) {}

  ngOnInit() {
    this.websocket.connect();
  }

  ngOnDestroy() {
    this.websocket.disconnect();
  }
}
