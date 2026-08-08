import { Injectable, signal, NgZone } from '@angular/core';
import { Client, Frame } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { SpreadSnapshot } from '../models/spread.model';

@Injectable({
  providedIn: 'root'
})
export class WebsocketService {
  private client = new Client({
    brokerURL: undefined,
    webSocketFactory: () => new SockJS('/ws'),
    reconnectDelay: 5000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    debug: (msg: string) => console.log('[STOMP]', msg)
  });

  snapshot = signal<SpreadSnapshot | null>(null);
  connection = signal<'connecting' | 'open' | 'closed'>('closed');

  private stalenessTimer: any;
  private readonly STALE_THRESHOLD_MS = 10000;

  constructor(private ngZone: NgZone) {
    this.setupClientHandlers();
  }

  private setupClientHandlers() {
    this.client.onConnect = () => this.onConnect();
    this.client.onDisconnect = () => this.onDisconnect();
    this.client.onStompError = (frame: Frame) => this.onError(frame);
  }

  connect() {
    if (this.connection() !== 'closed') return;
    this.connection.set('connecting');
    this.client.activate();
  }

  disconnect() {
    this.client.deactivate();
    this.connection.set('closed');
    clearTimeout(this.stalenessTimer);
  }

  private onConnect() {
    this.ngZone.run(() => {
      this.connection.set('open');
      this.client.subscribe('/topic/spreads', (message: Frame) => {
        this.ngZone.run(() => {
          const snapshot = JSON.parse(message.body) as SpreadSnapshot;
          this.snapshot.set(snapshot);
          this.resetStalenessTimer();
        });
      });
    });
  }

  private onDisconnect() {
    this.ngZone.run(() => this.connection.set('closed'));
  }

  private onError(frame: Frame) {
    console.error('STOMP error:', frame.body);
    this.ngZone.run(() => this.connection.set('closed'));
  }

  private resetStalenessTimer() {
    clearTimeout(this.stalenessTimer);
    // Simply reset; don't mutate snapshot.live
    // The UI derives STALE state from age (calculated on client), not from this mutation
  }
}
