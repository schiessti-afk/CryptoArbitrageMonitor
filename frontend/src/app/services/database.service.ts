import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface DatabaseStats {
  sizeBytes: number;
  sizePretty: string;
  spreadLogRows: number;
  spreadLogBytes: number;
  spreadLogSizePretty: string;
}

export interface DatabaseFlushResult {
  deletedRows: number;
  stats: DatabaseStats;
}

@Injectable({
  providedIn: 'root',
})
export class DatabaseService {
  private readonly http = inject(HttpClient);

  getStats(): Observable<DatabaseStats> {
    return this.http.get<DatabaseStats>('/api/database/stats');
  }

  flushSpreadLog(): Observable<DatabaseFlushResult> {
    return this.http.delete<DatabaseFlushResult>('/api/database/spread-log');
  }
}
