import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface OrderBookLevel {
  price: number;
  size: number;
}

export interface OrderBookSnapshot {
  exchange: string;
  symbol: string;
  nativeSymbol: string;
  bids: OrderBookLevel[];
  asks: OrderBookLevel[];
  receivedAt: string;
}

export interface RouteOrderBookResponse {
  symbol: string;
  buyExchange: string;
  sellExchange: string;
  buyBook: OrderBookSnapshot | null;
  sellBook: OrderBookSnapshot | null;
  buyBookError: string | null;
  sellBookError: string | null;
}

@Injectable({ providedIn: 'root' })
export class OrderBookService {
  constructor(private http: HttpClient) {}

  fetchRouteBooks(
    symbol: string,
    buyExchange: string,
    sellExchange: string,
    depth = 20
  ): Observable<RouteOrderBookResponse> {
    const params = new HttpParams()
      .set('symbol', symbol)
      .set('buyExchange', buyExchange)
      .set('sellExchange', sellExchange)
      .set('depth', depth.toString());

    return this.http.get<RouteOrderBookResponse>('/api/orderbook/route', { params });
  }
}
