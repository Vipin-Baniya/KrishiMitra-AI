import React, { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from 'react-query';
import toast from 'react-hot-toast';
import { alertApi, useAuthStore } from '../services/api';
import { useDevice } from '../hooks/useDevice';
import type { Alert } from '../services/api';

const SEVERITY_META: Record<string, { icon: string; color: string; bg: string; label: string }> = {
  URGENT:  { icon: '🔴', color: 'var(--danger)',  bg: 'var(--danger-dim)',  label: 'Urgent' },
  WARNING: { icon: '🟡', color: 'var(--warning)', bg: 'var(--warning-dim)', label: 'Warning' },
  INFO:    { icon: '🔵', color: 'var(--info)',    bg: 'var(--info-dim)',    label: 'Info' },
};

const TYPE_LABELS: Record<string, string> = {
  PRICE_SPIKE:   '📈 Price spike',
  PRICE_DROP:    '📉 Price drop',
  SELL_WINDOW:   '💰 Sell window',
  WEATHER:       '⛈️ Weather alert',
  SYSTEM:        '⚙️ System',
  BROADCAST:     '📢 Broadcast',
};

function AlertCard({ alert, onMarkRead }: { alert: Alert; onMarkRead: (id: string) => void }) {
  const meta = SEVERITY_META[alert.severity] ?? SEVERITY_META.INFO;

  return (
    <div
      className="card card-pad-sm"
      style={{
        borderLeft: `3px solid ${meta.color}`,
        opacity:    alert.isRead ? 0.65 : 1,
        transition: 'opacity 0.2s',
      }}
    >
      <div className="flex items-start gap-3">
        <span style={{ fontSize: 22, flexShrink: 0, marginTop: 1 }}>{meta.icon}</span>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="flex items-center gap-2" style={{ marginBottom: 5, flexWrap: 'wrap' }}>
            <span style={{ fontSize: 14, fontWeight: 600, color: 'var(--text-primary)' }}>{alert.title}</span>
            {!alert.isRead && <span className="badge badge-green">NEW</span>}
            {alert.severity === 'URGENT' && <span className="badge badge-red">URGENT</span>}
            {alert.commodity && (
              <span className="badge badge-muted">{alert.commodity}</span>
            )}
            {alert.mandiName && (
              <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>📍 {alert.mandiName}</span>
            )}
          </div>
          <p style={{ fontSize: 13, margin: 0, lineHeight: 1.6 }}>{alert.body}</p>
          <div className="flex items-center justify-between" style={{ marginTop: 8 }}>
            <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>
              {new Date(alert.createdAt).toLocaleDateString('en-IN', {
                day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit'
              })}
              {alert.type && ` · ${TYPE_LABELS[alert.type] ?? alert.type}`}
            </span>
            {!alert.isRead && (
              <button
                onClick={() => onMarkRead(alert.id)}
                className="btn btn-ghost btn-sm"
                style={{ fontSize: 11, padding: '3px 8px' }}
              >
                Mark read
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

export default function AlertsPage() {
  const { isMobile }  = useDevice();
  const farmer        = useAuthStore(s => s.farmer);
  const qc            = useQueryClient();
  const [page,        setPage]        = useState(0);
  const [severityFilter, setSeverity] = useState<string>('ALL');

  const { data, isLoading } = useQuery(
    ['alerts', page],
    () => alertApi.list(page),
    { keepPreviousData: true, staleTime: 30_000 }
  );

  const markAllMutation = useMutation(alertApi.markAllRead, {
    onSuccess: () => {
      qc.invalidateQueries('alerts');
      toast.success('All alerts marked as read');
    },
  });

  const markOneMutation = useMutation(alertApi.markRead, {
    onSuccess: () => qc.invalidateQueries('alerts'),
  });

  const alerts = (data?.alerts ?? []).filter(a =>
    severityFilter === 'ALL' || a.severity === severityFilter
  );

  const unreadCount = data?.totalUnread ?? 0;

  return (
    <div style={{ padding: isMobile ? '14px' : '24px 28px', maxWidth: 860, margin: '0 auto' }}>

      {/* Header */}
      <div className="flex items-start justify-between" style={{ marginBottom: 20, flexWrap: 'wrap', gap: 12 }}>
        <div>
          <h1 style={{ marginBottom: 4 }}>
            Alerts
            {unreadCount > 0 && (
              <span style={{
                marginLeft: 10, fontSize: 13, fontWeight: 700,
                background: 'var(--danger)', color: 'white',
                padding: '2px 9px', borderRadius: 'var(--r-full)',
                verticalAlign: 'middle',
              }}>{unreadCount}</span>
            )}
          </h1>
          <p style={{ fontSize: 14, margin: 0 }}>
            Price spikes, sell windows, weather alerts — all in one place
          </p>
        </div>
        {unreadCount > 0 && (
          <button
            className="btn btn-ghost btn-sm"
            onClick={() => markAllMutation.mutate()}
            disabled={markAllMutation.isLoading}
          >
            {markAllMutation.isLoading ? 'Marking…' : '✓ Mark all read'}
          </button>
        )}
      </div>

      {/* Severity filter */}
      <div className="flex gap-2" style={{ marginBottom: 16, flexWrap: 'wrap' }}>
        {['ALL', 'URGENT', 'WARNING', 'INFO'].map(s => (
          <button
            key={s}
            onClick={() => setSeverity(s)}
            className={`btn btn-sm ${severityFilter === s ? 'btn-primary' : 'btn-ghost'}`}
          >
            {s === 'ALL' ? 'All alerts' : (SEVERITY_META[s]?.icon + ' ' + SEVERITY_META[s]?.label)}
          </button>
        ))}
      </div>

      {/* Alert list */}
      {isLoading ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {[1, 2, 3, 4].map(i => (
            <div key={i} className="skeleton" style={{ height: 88, borderRadius: 'var(--r-lg)' }} />
          ))}
        </div>
      ) : alerts.length === 0 ? (
        <div style={{ textAlign: 'center', padding: '64px 24px' }}>
          <div style={{ fontSize: 52, marginBottom: 14 }}>✅</div>
          <h3 style={{ marginBottom: 8 }}>All caught up!</h3>
          <p style={{ fontSize: 14 }}>
            {severityFilter === 'ALL'
              ? "No alerts right now. We'll notify you when something needs attention."
              : `No ${SEVERITY_META[severityFilter]?.label.toLowerCase()} alerts.`}
          </p>
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {alerts.map(a => (
            <AlertCard
              key={a.id}
              alert={a}
              onMarkRead={id => markOneMutation.mutate(id)}
            />
          ))}
        </div>
      )}

      {/* Pagination */}
      {(data?.totalPages ?? 0) > 1 && (
        <div className="flex items-center justify-between" style={{ marginTop: 20 }}>
          <button
            className="btn btn-ghost btn-sm"
            disabled={page === 0}
            onClick={() => setPage(p => p - 1)}
          >← Previous</button>
          <span style={{ fontSize: 13, color: 'var(--text-muted)' }}>
            Page {page + 1} of {data?.totalPages}
          </span>
          <button
            className="btn btn-ghost btn-sm"
            disabled={page >= (data?.totalPages ?? 1) - 1}
            onClick={() => setPage(p => p + 1)}
          >Next →</button>
        </div>
      )}
    </div>
  );
}
