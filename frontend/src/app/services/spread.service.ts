import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { SpreadOpportunity, Pair, Fee, ExchangeStatus } from '../models/spread.model';

@Injectable({
  providedIn: 'root'
})
export class SpreadService {
  constructor(private http: HttpClient) {}

  getPairs(): Observable<Pair[]> {
    return this.http.get<Pair[]>('/api/pairs');
  }

  getExchanges(): Observable<ExchangeStatus[]> {
    return this.http.get<ExchangeStatus[]>('/api/exchanges');
  }

  getFees(): Observable<Fee[]> {
    return this.http.get<Fee[]>('/api/fees');
  }

  getLatest(): Observable<SpreadOpportunity[]> {
    return this.http.get<SpreadOpportunity[]>('/api/spreads/latest');
  }

  getHistory(limit: number, from?: string, to?: string): Observable<SpreadOpportunity[]> {
    let url = `/api/spreads/history?limit=${limit}`;
    if (from) url += `&from=${from}`;
    if (to) url += `&to=${to}`;
    return this.http.get<SpreadOpportunity[]>(url);
  }
}
