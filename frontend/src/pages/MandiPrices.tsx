import React, { useState, useMemo } from 'react';
import { useQuery } from 'react-query';
import { priceApi, useAuthStore, useUIStore } from '../services/api';
import { useDevice } from '../hooks/useDevice';
import type { LivePrice } from '../services/api';

const COMMODITIES = ['Wheat','Soybean','Onion','Tomato','Potato','Cotton','Maize','Gram','Mustard','Rice'];
const STATES_LIST = ['All States','Madhya Pradesh','Maharashtra','Rajasthan','Punjab','Haryana','Uttar Pradesh','Gujarat','Karnataka'];

type SortKey = 'mandi' | 'modalPrice' | 'changePct' | 'arrivalsQtl';
type SortDir = 'asc' | 'desc';

function TrendBadge({ direction, pct }: { direction: string; pct: number }) {
  const color = direction === 'UP' ? 'var(--c-green)' : direction === 'DOWN' ? 'var(--c-red)' : 'var(--text-muted)';
  const arrow = direction === 'UP' ? '▲' : direction === 'DOWN' ? '▼' : '─';
  return (
    <span style={{ fontSize: 12, fontWeight: 600, color }}>
      {arrow} {Math.abs(pct).toFixed(1)}%
    </span>
  );
}

export default function MandiPrices() {
  const { isMobile }  = useDevice();
  const setPage       = useUIStore(s => s.setPage);
  const [commodity, setCommodity] = useState('Wheat');
  const [stateFilter, setStateFilter] = useState('All States');
  const [search,      setSearch]      = useState('');
  const [sortKey,     setSortKey]     = useState<SortKey>('modalPrice');
  const [sortDir,     setSortDir]     = useState<SortDir>('desc');

  const { data, isLoading, refetch, dataUpdatedAt } = useQuery(
    ['live-prices', commodity, stateFilter],
    () => priceApi.getLive(commodity, stateFilter === 'All States' ? undefined : stateFilter),
    { staleTime: 30_000, refetchInterval: 120_000 }
  );

  const sorted: LivePrice[] = useMemo(() => {
    if (!data) return [];
    let rows = [...data];
    if (search.trim()) {
      const q = search.toLowerCase();
      rows = rows.filter(r => r.mandi.toLowerCase().includes(q) || r.district.toLowerCase().includes(q));
    }
    rows.sort((a, b) => {
      let diff = 0;
      if (sortKey === 'mandi')       diff = a.mandi.localeCompare(b.mandi);
      if (sortKey === 'modalPrice')  diff = a.modalPrice - b.modalPrice;
      if (sortKey === 'changePct')   diff = a.changePct - b.changePct;
      if (sortKey === 'arrivalsQtl') diff = (a.arrivalsQtl ?? 0) - (b.arrivalsQtl ?? 0);
      return sortDir === 'asc' ? diff : -diff;
    });
    return rows;
  }, [data, search, sortKey, sortDir]);

  function toggleSort(key: SortKey) {
    if (sortKey === key) setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    else { setSortKey(key); setSortDir('desc'); }
  }

  const SortIcon = ({ k }: { k: SortKey }) => (
    <span style={{ marginLeft: 4, opacity: sortKey === k ? 1 : 0.3 }}>
      {sortKey === k ? (sortDir === 'desc' ? '↓' : '↑') : '↕'}
    </span>
  );

  const best  = sorted[0];
  const worst = [...sorted].reverse()[0];

  return (
    <div style={{ padding: isMobile ? '14px' : '24px 28px', maxWidth: 'var(--content-max)', margin: '0 auto' }}>

      {/* Header */}
      <div style={{ marginBottom: 20 }}>
        <h1 style={{ marginBottom: 4 }}>Mandi Prices</h1>
        <div className="flex items-center gap-3" style={{ flexWrap: 'wrap' }}>
          <p style={{ fontSize: 14, margin: 0 }}>Live from Agmarknet · Auto-refresh every 2 min</p>
          {dataUpdatedAt > 0 && (
            <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>
              Updated {new Date(dataUpdatedAt).toLocaleTimeString('en-IN')}
            </span>
          )}
          <button className="btn btn-ghost btn-sm" onClick={() => refetch()}>↻ Refresh</button>
        </div>
      </div>

      {/* Summary cards (top / bottom price) */}
      {!isLoading && sorted.length > 0 && (
        <div style={{ display: 'grid', gridTemplateColumns: isMobile ? '1fr 1fr' : 'repeat(4,1fr)', gap: 12, marginBottom: 18 }}>
          {[
            { label: 'Highest price',  val: `₹${best?.modalPrice?.toLocaleString('en-IN')}`, sub: best?.mandi,  color: 'var(--c-green)' },
            { label: 'Lowest price',   val: `₹${worst?.modalPrice?.toLocaleString('en-IN')}`, sub: worst?.mandi, color: 'var(--c-red)' },
            { label: 'Mandis tracked', val: String(sorted.length), sub: `${stateFilter}`,       color: 'var(--text-primary)' },
            { label: 'Best spread',    val: `₹${((best?.modalPrice ?? 0) - (worst?.modalPrice ?? 0)).toLocaleString('en-IN')}`, sub: 'max − min', color: 'var(--c-amber)' },
          ].map((s, i) => (
            <div key={i} className="card card-pad-sm">
              <div className="stat-label" style={{ marginBottom: 6 }}>{s.label}</div>
              <div className="stat-value" style={{ fontSize: 20, color: s.color }}>{s.val}</div>
              <div style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 2 }}>{s.sub}</div>
            </div>
          ))}
        </div>
      )}

      {/* Commodity tabs */}
      <div style={{ overflowX: 'auto', marginBottom: 14 }}>
        <div className="flex gap-2" style={{ paddingBottom: 4 }}>
          {COMMODITIES.map(c => (
            <button key={c} onClick={() => setCommodity(c)}
              className={`btn btn-sm ${commodity === c ? 'btn-primary' : 'btn-ghost'}`}
              style={{ flexShrink: 0 }}>{c}</button>
          ))}
        </div>
      </div>

      {/* Filters row */}
      <div className="flex gap-3" style={{ marginBottom: 14, flexWrap: 'wrap' }}>
        <input
          value={search} onChange={e => setSearch(e.target.value)}
          placeholder="Search mandi or district…"
          className="input" style={{ maxWidth: 240, flex: 1 }}
        />
        <select value={stateFilter} onChange={e => setStateFilter(e.target.value)} className="select" style={{ width: 'auto' }}>
          {STATES_LIST.map(s => <option key={s}>{s}</option>)}
        </select>
        {!isMobile && (
          <button className="btn btn-secondary btn-sm" onClick={() => setPage('sell')}>
            Get sell advice →
          </button>
        )}
      </div>

      {/* Table */}
      <div className="card" style={{ overflow: 'hidden' }}>
        {isLoading ? (
          <div style={{ padding: 24 }}>
            {[1,2,3,4,5,6].map(i => (
              <div key={i} className="skeleton" style={{ height: 44, marginBottom: 8, borderRadius: 'var(--r-md)' }} />
            ))}
          </div>
        ) : sorted.length === 0 ? (
          <div style={{ padding: '48px 24px', textAlign: 'center' }}>
            <div style={{ fontSize: 40, marginBottom: 12 }}>🔍</div>
            <h3 style={{ marginBottom: 8 }}>No prices found</h3>
            <p style={{ fontSize: 14 }}>Try a different commodity or state filter.</p>
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table className="data-table" style={{ minWidth: 620 }}>
              <thead>
                <tr>
                  <th onClick={() => toggleSort('mandi')} style={{ cursor: 'pointer' }}>
                    Mandi <SortIcon k="mandi" />
                  </th>
                  <th className="hide-mobile">District / State</th>
                  <th style={{ textAlign: 'right' }}>Min ₹</th>
                  <th onClick={() => toggleSort('modalPrice')} style={{ cursor: 'pointer', textAlign: 'right' }}>
                    Modal ₹ <SortIcon k="modalPrice" />
                  </th>
                  <th style={{ textAlign: 'right' }}>Max ₹</th>
                  <th onClick={() => toggleSort('changePct')} style={{ cursor: 'pointer' }}>
                    Trend <SortIcon k="changePct" />
                  </th>
                  <th onClick={() => toggleSort('arrivalsQtl')} style={{ cursor: 'pointer', textAlign: 'right' }} className="hide-mobile">
                    Arrivals <SortIcon k="arrivalsQtl" />
                  </th>
                  <th className="hide-mobile">Date</th>
                </tr>
              </thead>
              <tbody>
                {sorted.map((p, i) => (
                  <tr key={i}>
                    <td>
                      <div style={{ fontWeight: 600, fontSize: 13 }}>{p.mandi}</div>
                    </td>
                    <td className="hide-mobile" style={{ color: 'var(--text-muted)', fontSize: 12 }}>
                      {p.district}, {p.state}
                    </td>
                    <td style={{ textAlign: 'right', color: 'var(--text-muted)', fontSize: 12 }}>
                      ₹{p.minPrice.toLocaleString('en-IN')}
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <span className="price-tag">₹{p.modalPrice.toLocaleString('en-IN')}</span>
                    </td>
                    <td style={{ textAlign: 'right', color: 'var(--text-muted)', fontSize: 12 }}>
                      ₹{p.maxPrice.toLocaleString('en-IN')}
                    </td>
                    <td>
                      <TrendBadge direction={p.trendDirection} pct={p.changePct} />
                    </td>
                    <td className="hide-mobile" style={{ textAlign: 'right', fontSize: 12, color: 'var(--text-muted)' }}>
                      {p.arrivalsQtl != null ? `${p.arrivalsQtl.toLocaleString('en-IN')} qtl` : '—'}
                    </td>
                    <td className="hide-mobile" style={{ fontSize: 12, color: 'var(--text-muted)' }}>
                      {p.priceDate}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {sorted.length > 0 && (
        <p style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 8 }}>
          {sorted.length} mandis · All prices in ₹ per quintal · Source: Agmarknet / data.gov.in
        </p>
      )}
    </div>
  );
}
