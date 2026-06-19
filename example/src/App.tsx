import { useEffect, useState } from 'react';
import {
  Text,
  View,
  StyleSheet,
  Pressable,
  ScrollView,
} from 'react-native';
import {
  configure,
  setConfigJson,
  refresh,
  getLastResult,
  addSpinResultListener,
  type SpinWheelResult,
  type SpinResultEvent,
} from 'react-native-spin-wheel-widget';

export default function App() {
  const [state, setState] = useState<SpinWheelResult | null>(null);
  const [lastSpin, setLastSpin] = useState<SpinResultEvent | null>(null);
  const [log, setLog] = useState<string[]>([]);
  const [status, setStatus] = useState(
    'Add the "Spin Wheel" widget to your home screen.'
  );

  // Append a timestamped line to the on-screen event log (newest first, capped at 50).
  const append = (msg: string) =>
    setLog((prev) =>
      [`${new Date().toLocaleTimeString()}  ${msg}`, ...prev].slice(0, 50)
    );

  // Live spin-completion events (fire only while this app is running).
  useEffect(() => {
    const sub = addSpinResultListener((e) => {
      setLastSpin(e);
      append(
        `onSpinResult ← #${e.widgetId}  sector ${e.segmentIndex}  @ ${e.angle.toFixed(1)}°`
      );
    });
    append('addSpinResultListener() registered');
    setState(getLastResult());
    append('getLastResult() on mount');
    return () => sub.remove();
  }, []);

  const refreshState = () => {
    const r = getLastResult();
    setState(r);
    append(
      `getLastResult() → sector ${r.segmentIndex} @ ${r.restingAngle.toFixed(1)}°`
    );
  };

  const onUseBundled = () => {
    // Clear any overrides → widget renders from the bundled config + drawables.
    setConfigJson('');
    configure('', '');
    append("setConfigJson('') + configure('', '')");
    // Note: this clears the remote *config* URL, so no config fetch happens. The bundled config still
    // names Drive-hosted assets, so the widget will still download that art when online and reconcile
    // its asset cache; the bundled drawables are the *fallback* used only when offline.
    setStatus('Configured: bundled config (assets still download when online; drawables are the offline fallback).');
    refreshState();
  };

  const onConfigureRemote = () => {
    // Hosted config.json (raw GitHub). Goes live once the repo is pushed; until then it 404s and the
    // widget gracefully falls back to cache/bundled. Adjust the branch (main/master) if yours differs.
    configure(
      'https://raw.githubusercontent.com/Nicko-Sachno/react-native-spin-wheel-widget/main/config.json',
      ''
    );
    append('configure(remoteUrl)');
    setStatus('Configured: remote config URL set.');
    refreshState();
  };

  const onRefresh = async () => {
    append('refresh() called… (forces a config re-fetch, bypassing the TTL)');
    const placed = await refresh();
    append(`refresh() → ${placed ? 'widget placed' : 'no widget placed'}`);
    setStatus(
      placed
        ? 'Forced refresh sent → widget re-fetches config + assets and re-renders.'
        : 'No widget placed yet.'
    );
    refreshState();
  };

  return (
    <ScrollView contentContainerStyle={styles.container}>
      <Text style={styles.title}>Spin Wheel Widget</Text>
      <Text style={styles.status}>{status}</Text>

      <View style={styles.card}>
        <Text style={styles.cardTitle}>Persisted state</Text>
        <Text style={styles.row}>configUrl: {state?.configUrl || '(none)'}</Text>
        <Text style={styles.row}>
          restingAngle: {state?.restingAngle?.toFixed(1) ?? '—'}°
        </Text>
        <Text style={styles.row}>
          lastFetchTime:{' '}
          {state?.lastFetchTime
            ? new Date(state.lastFetchTime).toLocaleTimeString()
            : '—'}
        </Text>
        <Text style={styles.row}>
          sector under pointer: {state?.segmentIndex ?? '—'}
        </Text>
      </View>

      <View style={styles.card}>
        <Text style={styles.cardTitle}>Last spin event</Text>
        <Text style={styles.row}>
          {lastSpin
            ? `#${lastSpin.widgetId} → sector ${lastSpin.segmentIndex} at ${lastSpin.angle.toFixed(1)}°`
            : 'Tap the wheel on your home screen…'}
        </Text>
      </View>

      <Button label="Use bundled config" onPress={onUseBundled} />
      <Button label="Configure remote URL" onPress={onConfigureRemote} />
      <Button label="Refresh widget" onPress={onRefresh} />
      <Button label="Read last result" onPress={refreshState} />

      <Text style={styles.hint}>
        “Refresh widget” forces a config re-fetch (bypasses the 5-min TTL). To push a new promotion,
        update the hosted config.json with new asset IDs — the config is the source of truth, so the
        widget downloads the new images and prunes the old ones.
      </Text>

      <View style={styles.card}>
        <Text style={styles.cardTitle}>Event log (RN callbacks received)</Text>
        {log.length === 0 ? (
          <Text style={styles.row}>No events yet…</Text>
        ) : (
          log.map((line, i) => (
            <Text key={i} style={styles.logLine}>
              {line}
            </Text>
          ))
        )}
      </View>
    </ScrollView>
  );
}

function Button({ label, onPress }: { label: string; onPress: () => void }) {
  return (
    <Pressable
      style={({ pressed }) => [styles.button, pressed && styles.buttonPressed]}
      onPress={onPress}
    >
      <Text style={styles.buttonText}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  container: { padding: 20, gap: 12, paddingTop: 64 },
  title: { fontSize: 24, fontWeight: '700' },
  status: { fontSize: 14, color: '#555', marginBottom: 8 },
  card: { backgroundColor: '#f2f4f7', borderRadius: 12, padding: 14, gap: 4 },
  cardTitle: { fontWeight: '600', marginBottom: 4 },
  row: { fontSize: 14, color: '#222' },
  logLine: { fontSize: 12, color: '#333', fontFamily: 'monospace' },
  hint: { fontSize: 12, color: '#666', fontStyle: 'italic' },
  button: {
    backgroundColor: '#2B5876',
    borderRadius: 10,
    paddingVertical: 14,
    alignItems: 'center',
  },
  buttonPressed: { opacity: 0.7 },
  buttonText: { color: 'white', fontWeight: '600', fontSize: 16 },
});
