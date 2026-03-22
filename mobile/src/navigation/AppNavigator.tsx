import React, { useEffect } from 'react';
import { NavigationContainer, DefaultTheme, DarkTheme } from '@react-navigation/native';
import { createBottomTabNavigator }  from '@react-navigation/bottom-tabs';
import { createStackNavigator }      from '@react-navigation/stack';
import { useColorScheme, Platform, Text, View }  from 'react-native';

import { useAuthStore }  from '../store/authStore';
import { useTheme }      from '../services/foundation';

// ── Screen imports — each maps to its actual exported name ────
// MainScreens.tsx exports: DashboardScreen, MandiPricesScreen
import DashboardScreen    from './screens/DashboardScreen';
import MandiPricesScreen  from './screens/MandiPricesScreen';

// AiChat and Alerts are standalone exports from SecondaryScreens.tsx
import AiChatScreen       from './screens/AiChatScreen';
import AlertsScreen       from './screens/AlertsScreen';
import LoginScreen        from './screens/LoginScreen';

// MissingScreens.tsx exports: SellAdvisorScreen, ProfitSimScreen,
//   CropAdvisorScreen, ProfileScreen, ForecastDetailScreen
import SellAdvisorScreen    from './screens/SellAdvisorScreen';
import ProfitSimScreen      from './screens/ProfitSimScreen';
import CropAdvisorScreen    from './screens/CropAdvisorScreen';
import ProfileScreen        from './screens/ProfileScreen';
import ForecastDetailScreen from './screens/ForecastDetailScreen';

// ── Nav type params ───────────────────────────────────────────
export type RootStackParams = {
  Main:           undefined;
  ForecastDetail: { commodity: string; mandi: string };
  ProfitSim:      { commodity: string; mandi: string };
  Profile:        undefined;
  CropAdvisor:    undefined;
};

export type TabParams = {
  Dashboard: undefined;
  Prices:    undefined;
  Sell:      undefined;
  Chat:      undefined;
  Alerts:    undefined;
};

const Tab   = createBottomTabNavigator<TabParams>();
const Stack = createStackNavigator<RootStackParams>();

// ── Tab icon helper ───────────────────────────────────────────
function TabIcon({ name, color, size }: { name: string; color: string; size: number }) {
  const icons: Record<string, string> = {
    Dashboard: '⊞', Prices: '📈', Sell: '💰', Chat: '🤖', Alerts: '🔔',
  };
  return (
    <Text style={{ fontSize: size - 2, color, marginBottom: -2 }}>
      {icons[name] ?? '●'}
    </Text>
  );
}

function TabNavigator() {
  const { colors } = useTheme();
  const farmer = useAuthStore(s => s.farmer);

  return (
    <Tab.Navigator
      screenOptions={({ route }) => ({
        headerShown: false,
        tabBarStyle: {
          backgroundColor: colors.surface,
          borderTopColor:  colors.border,
          borderTopWidth:  0.5,
          height:          Platform.OS === 'ios' ? 84 : 60,
          paddingBottom:   Platform.OS === 'ios' ? 24 : 8,
          paddingTop:      8,
        },
        tabBarActiveTintColor:   colors.accent,
        tabBarInactiveTintColor: colors.textMuted,
        tabBarLabelStyle: { fontSize: 10, fontWeight: '500', marginTop: 2 },
        tabBarIcon: ({ color, size }) => (
          <TabIcon name={route.name} color={color} size={size} />
        ),
      })}
    >
      <Tab.Screen name="Dashboard" component={DashboardScreen} />
      <Tab.Screen name="Prices"    component={MandiPricesScreen} />
      <Tab.Screen name="Sell"      component={SellAdvisorScreen} />
      <Tab.Screen name="Chat"      component={AiChatScreen} />
      <Tab.Screen
        name="Alerts"
        component={AlertsScreen}
        options={{
          tabBarBadge: (farmer?.unreadAlerts ?? 0) > 0
            ? farmer!.unreadAlerts
            : undefined,
        }}
      />
    </Tab.Navigator>
  );
}

function MainNavigator() {
  const { colors } = useTheme();

  return (
    <Stack.Navigator
      screenOptions={{
        headerStyle:      { backgroundColor: colors.surface },
        headerTintColor:  colors.textPrimary,
        headerTitleStyle: { fontWeight: '700', fontSize: 16 },
        headerBackTitle:  '',
        cardStyle:        { backgroundColor: colors.bg },
      }}
    >
      <Stack.Screen name="Main"           component={TabNavigator}         options={{ headerShown: false }} />
      <Stack.Screen name="ForecastDetail" component={ForecastDetailScreen} options={{ title: 'Price Forecast' }} />
      <Stack.Screen name="ProfitSim"      component={ProfitSimScreen}      options={{ title: 'Profit Simulator' }} />
      <Stack.Screen name="Profile"        component={ProfileScreen}         options={{ title: 'My Profile' }} />
      <Stack.Screen name="CropAdvisor"    component={CropAdvisorScreen}    options={{ title: 'Crop Advisor' }} />
    </Stack.Navigator>
  );
}

export default function AppNavigator() {
  const scheme        = useColorScheme();
  const { isAuth, hydrate } = useAuthStore();
  const { colors }    = useTheme();

  useEffect(() => { hydrate(); }, []);

  const navTheme = {
    ...(scheme === 'dark' ? DarkTheme : DefaultTheme),
    colors: {
      ...(scheme === 'dark' ? DarkTheme.colors : DefaultTheme.colors),
      primary:    colors.accent,
      background: colors.bg,
      card:       colors.surface,
      text:       colors.textPrimary,
      border:     colors.border,
    },
  };

  return (
    <NavigationContainer theme={navTheme}>
      {isAuth ? <MainNavigator /> : <LoginScreen />}
    </NavigationContainer>
  );
}
