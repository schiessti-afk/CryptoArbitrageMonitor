import { Component, signal, effect } from '@angular/core';
import { CommonModule } from '@angular/common';
import { WebsocketService } from '../../services/websocket.service';

@Component({
  selector: 'app-connection-status',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './connection-status.component.html',
  styles: []
})
export class ConnectionStatusComponent {
  lastUpdateAge = signal('—');
  constructor(public websocket: WebsocketService) {
    // Update age display every second
    effect(() => {
      const snap = this.websocket.snapshot();
      if (!snap) {
        this.lastUpdateAge.set('—');
        return;
      }
      const age = (Date.now() - new Date(snap.calculatedAt).getTime()) / 1000;
      if (age < 60) {
        this.lastUpdateAge.set(`${Math.floor(age)}s ago`);
      } else {
        this.lastUpdateAge.set(`${Math.floor(age / 60)}m ago`);
      }
    });
  }

  snapshot() {
    return this.websocket.snapshot();
  }

  getExchangeClass(freshness: string): string {
    switch (freshness) {
      case 'FRESH':
        return 'bg-green-100 text-green-800';
      case 'STALE':
        return 'bg-yellow-100 text-yellow-800';
      default:
        return 'bg-gray-100 text-gray-800';
    }
  }
}
