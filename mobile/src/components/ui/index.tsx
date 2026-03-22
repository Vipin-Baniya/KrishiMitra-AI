// ════════════════════════════════════════════════════════════
//  src/components/ui/index.tsx
//  All reusable primitives — designed for farmer UX
// ════════════════════════════════════════════════════════════
import React, { ReactNode } from 'react';
import {
  View, Text, TouchableOpacity, StyleSheet, ActivityIndicator,
  ViewStyle, TextStyle, Platform, Pressable,
} from 'react-native';
import Animated, { FadeInDown, FadeInUp } from 'react-native-reanimated';
import SkeletonContent from 'react-native-skeleton-content';
import { useTheme, SPACING, RADIUS, FONT_SIZE, FONT_WEIGHT } from '../../services/foundation';
import type { AppTheme } from '../../services/foundation';

// ─────────────────────────────────────────────────────────────
// CARD
// ─────────────────────────────────────────────────────────────
export function Card({ children, style, onPress, accent }: {
  children: ReactNode;
  style?:   ViewStyle;
  onPress?: () => void;
  accent?:  string;
}) {
  const { colors, shadow } = useTheme();
  const cardStyle: ViewStyle = {
    backgroundColor: colors.surface,
    borderRadius:    RADIUS.lg,
    borderWidth:     0.5,
    borderColor:     colors.border,
    padding:         SPACING.lg,
    ...(Platform.OS === 'ios' ? shadow.sm as ViewStyle : shadow.sm as ViewStyle),
    ...(accent ? { borderLeftWidth: 3, borderLeftColor: accent } : {}),
    ...style,
  };

  if (onPress) {
    return (
      <Pressable onPress={onPress} style={({ pressed }) => [cardStyle, pressed && { opacity: 0.92 }]}>
        {children}
      </Pressable>
    );
  }
  return <View style={cardStyle}>{children}</View>;
}

// ─────────────────────────────────────────────────────────────
// STAT CARD
// ─────────────────────────────────────────────────────────────
export function StatCard({ label, value, unit, sub, change, changeUp, color, loading }: {
  label:      string;
  value:      string | number;
  unit?:      string;
  sub?:       string;
  change?:    string;
  changeUp?:  boolean;
  color?:     string;
  loading?:   boolean;
}) {
  const { colors } = useTheme();
  return (
    <Card>
      <Text style={{ fontSize: FONT_SIZE.xs, fontWeight: FONT_WEIGHT.semibold, color: colors.textMuted, letterSpacing: 0.6, textTransform: 'uppercase', marginBottom: 8 }}>
        {label}
      </Text>
      {loading
        ? <SkeletonContent isLoading containerStyle={{ height: 36 }} layout={[{ width: 80, height: 28 }]} />
        : (
          <View style={{ flexDirection:'row', alignItems:'baseline', gap: 3, marginBottom: 4 }}>
            <Text style={{ fontSize: FONT_SIZE.xl, fontWeight: FONT_WEIGHT.bold, color: color ?? colors.textPrimary }}>
              {typeof value === 'number' ? value.toLocaleString('en-IN') : value}
            </Text>
            {unit && <Text style={{ fontSize: FONT_SIZE.sm, color: colors.textMuted }}>{unit}</Text>}
          </View>
        )
      }
      <View style={{ flexDirection:'row', alignItems:'center', justifyContent:'space-between' }}>
        {change && (
          <Text style={{ fontSize: 12, fontWeight: FONT_WEIGHT.medium, color: changeUp ? colors.chartGreen : colors.chartRed }}>
            {changeUp ? '▲' : '▼'} {change}
          </Text>
        )}
        {sub && <Text style={{ fontSize: FONT_SIZE.xs, color: colors.textMuted }}>{sub}</Text>}
      </View>
    </Card>
  );
}

// ─────────────────────────────────────────────────────────────
// BUTTON
// ─────────────────────────────────────────────────────────────
type BtnVariant = 'primary'|'secondary'|'ghost'|'danger';
export function Button({ label, onPress, variant='primary', loading, disabled, fullWidth, size='md', icon }: {
  label:     string;
  onPress:   () => void;
  variant?:  BtnVariant;
  loading?:  boolean;
  disabled?: boolean;
  fullWidth?: boolean;
  size?:     'sm'|'md'|'lg';
  icon?:     ReactNode;
}) {
  const { colors } = useTheme();
  const bg: Record<BtnVariant, string> = {
    primary:   colors.accent,
    secondary: colors.elevated,
    ghost:     'transparent',
    danger:    colors.danger,
  };
  const fg: Record<BtnVariant, string> = {
    primary:   colors.textInverse,
    secondary: colors.textPrimary,
    ghost:     colors.textSecondary,
    danger:    '#FFFFFF',
  };
  const border: Record<BtnVariant, string|undefined> = {
    primary:   undefined,
    secondary: colors.borderMd,
    ghost:     colors.border,
    danger:    undefined,
  };
  const py: Record<string,number> = { sm:6, md:10, lg:14 };
  const fs: Record<string,number> = { sm:12, md:14, lg:16 };

  return (
    <Pressable
      onPress={onPress}
      disabled={disabled || loading}
      style={({ pressed }) => ({
        backgroundColor: bg[variant],
        borderRadius:    RADIUS.md,
        paddingVertical: py[size],
        paddingHorizontal: SPACING.lg,
        borderWidth:     border[variant] ? 1 : 0,
        borderColor:     border[variant],
        opacity:         (disabled || loading) ? 0.5 : pressed ? 0.88 : 1,
        flexDirection:   'row',
        alignItems:      'center',
        justifyContent:  'center',
        gap:             SPACING.sm,
        alignSelf:       fullWidth ? 'stretch' : 'flex-start',
      })}
    >
      {loading
        ? <ActivityIndicator size="small" color={fg[variant]} />
        : (
          <>
            {icon}
            <Text style={{ fontSize: fs[size], fontWeight: FONT_WEIGHT.semibold, color: fg[variant] }}>{label}</Text>
          </>
        )
      }
    </Pressable>
  );
}

// ─────────────────────────────────────────────────────────────
// BADGE
// ─────────────────────────────────────────────────────────────
type BadgeColor = 'green'|'red'|'amber'|'blue'|'muted';
export function Badge({ label, color='muted' }: { label:string; color?:BadgeColor }) {
  const { colors } = useTheme();
  const bg: Record<BadgeColor,string> = {
    green: 'rgba(79,180,131,0.15)',
    red:   colors.dangerDim,
    amber: colors.warningDim,
    blue:  'rgba(96,165,250,0.15)',
    muted: colors.elevated,
  };
  const fg: Record<BadgeColor,string> = {
    green: colors.accent,
    red:   colors.danger,
    amber: colors.warning,
    blue:  colors.info,
    muted: colors.textMuted,
  };
  return (
    <View style={{ backgroundColor: bg[color], paddingHorizontal: 8, paddingVertical: 2, borderRadius: RADIUS.full }}>
      <Text style={{ fontSize: 10, fontWeight: FONT_WEIGHT.bold, color: fg[color], textTransform: 'uppercase', letterSpacing: 0.5 }}>
        {label}
      </Text>
    </View>
  );
}

// ─────────────────────────────────────────────────────────────
// PRICE TAG
// ─────────────────────────────────────────────────────────────
export function PriceTag({ price, size='md' }: { price: number | string; size?:'sm'|'md'|'lg' }) {
  const { colors } = useTheme();
  const fs: Record<string,number> = { sm:12, md:14, lg:18 };
  return (
    <View style={{ backgroundColor: colors.accentDim, borderRadius: RADIUS.sm, paddingHorizontal: 8, paddingVertical: 3, borderWidth: 1, borderColor: 'rgba(79,180,131,0.25)' }}>
      <Text style={{ fontSize: fs[size], fontWeight: FONT_WEIGHT.bold, color: colors.accent }}>
        {typeof price === 'number' ? `₹${price.toLocaleString('en-IN')}` : price}
      </Text>
    </View>
  );
}

// ─────────────────────────────────────────────────────────────
// SECTION HEADER
// ─────────────────────────────────────────────────────────────
export function SectionHeader({ title, action, actionLabel }: {
  title: string; action?:()=>void; actionLabel?:string;
}) {
  const { colors } = useTheme();
  return (
    <View style={{ flexDirection:'row', alignItems:'center', justifyContent:'space-between', marginBottom: SPACING.sm }}>
      <Text style={{ fontSize: FONT_SIZE.md, fontWeight: FONT_WEIGHT.bold, color: colors.textPrimary }}>{title}</Text>
      {action && actionLabel && (
        <TouchableOpacity onPress={action}>
          <Text style={{ fontSize: FONT_SIZE.sm, color: colors.accent, fontWeight: FONT_WEIGHT.medium }}>{actionLabel}</Text>
        </TouchableOpacity>
      )}
    </View>
  );
}

// ─────────────────────────────────────────────────────────────
// TREND INDICATOR
// ─────────────────────────────────────────────────────────────
export function TrendIndicator({ direction, pct }: { direction:'UP'|'DOWN'|'FLAT'; pct:number }) {
  const { colors } = useTheme();
  const color = direction==='UP' ? colors.chartGreen : direction==='DOWN' ? colors.chartRed : colors.textMuted;
  const arrow = direction==='UP' ? '▲' : direction==='DOWN' ? '▼' : '─';
  return (
    <Text style={{ fontSize: 12, fontWeight: FONT_WEIGHT.medium, color }}>
      {arrow} {Math.abs(pct).toFixed(1)}%
    </Text>
  );
}

// ─────────────────────────────────────────────────────────────
// LOADING SKELETON ROWS
// ─────────────────────────────────────────────────────────────
export function SkeletonRows({ n=3, height=60 }: { n?:number; height?:number }) {
  const { colors } = useTheme();
  return (
    <>
      {Array.from({length:n}).map((_,i) => (
        <SkeletonContent
          key={i}
          isLoading
          containerStyle={{ marginBottom: SPACING.sm }}
          layout={[{ width:'100%', height, borderRadius: RADIUS.lg }]}
          boneColor={colors.elevated}
          highlightColor={colors.border}
        />
      ))}
    </>
  );
}

// ─────────────────────────────────────────────────────────────
// DIVIDER
// ─────────────────────────────────────────────────────────────
export function Divider({ style }: { style?:ViewStyle }) {
  const { colors } = useTheme();
  return <View style={{ height: 1, backgroundColor: colors.border, marginVertical: SPACING.md, ...style }} />;
}

// ─────────────────────────────────────────────────────────────
// ANIMATED FADE-IN WRAPPER
// ─────────────────────────────────────────────────────────────
export function FadeIn({ children, delay=0 }: { children:ReactNode; delay?:number }) {
  return (
    <Animated.View entering={FadeInDown.delay(delay).duration(350)}>
      {children}
    </Animated.View>
  );
}

// ─────────────────────────────────────────────────────────────
// PROGRESS BAR
// ─────────────────────────────────────────────────────────────
export function ProgressBar({ pct, color, height=5 }: { pct:number; color?:string; height?:number }) {
  const { colors } = useTheme();
  return (
    <View style={{ height, backgroundColor: colors.elevated, borderRadius: RADIUS.full, overflow:'hidden' }}>
      <View style={{ height, width:`${Math.min(100, Math.max(0, pct))}%`, backgroundColor: color ?? colors.accent, borderRadius: RADIUS.full }} />
    </View>
  );
}

// ─────────────────────────────────────────────────────────────
// HEADER  (used in tab screens — replaces nav header)
// ─────────────────────────────────────────────────────────────
export function ScreenHeader({ title, subtitle, right }: {
  title:    string;
  subtitle?: string;
  right?:   ReactNode;
}) {
  const { colors } = useTheme();
  return (
    <View style={{ paddingHorizontal: SPACING.lg, paddingTop: SPACING.lg, paddingBottom: SPACING.md }}>
      <View style={{ flexDirection:'row', alignItems:'flex-start', justifyContent:'space-between' }}>
        <View style={{ flex:1 }}>
          <Text style={{ fontSize: FONT_SIZE.xxl, fontWeight: FONT_WEIGHT.bold, color: colors.textPrimary, letterSpacing:-0.5 }}>
            {title}
          </Text>
          {subtitle && (
            <Text style={{ fontSize: FONT_SIZE.sm, color: colors.textMuted, marginTop: 3 }}>{subtitle}</Text>
          )}
        </View>
        {right}
      </View>
    </View>
  );
}
