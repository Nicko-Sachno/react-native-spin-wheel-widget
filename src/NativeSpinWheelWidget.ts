import { TurboModuleRegistry, type TurboModule } from 'react-native';

/** Snapshot of the widget's persisted state, returned by {@link Spec.getLastResult}. */
export type SpinWheelResult = {
  /** The wheel's resting angle in degrees (0–360), persisted across spins. */
  restingAngle: number;
  /** Epoch millis of the last successful config load (0 if never). */
  lastFetchTime: number;
  /** The remote config URL currently configured ('' when running fully offline). */
  configUrl: string;
  /** Index of the segment currently under the pointer (derived from restingAngle). */
  segmentIndex: number;
  /** Hex color of that segment, e.g. '#7CB342'. */
  color: string;
  /** Human-readable name of that segment, e.g. 'Green'. */
  name: string;
};

export interface Spec extends TurboModule {
  /**
   * Point the widget at a remote JSON config and (optionally) override the asset host.
   * Persisted to the widget's SharedPreferences; takes effect on the next refresh/spin.
   * Pass '' for assetsHost to keep the host declared inside the fetched config.
   */
  configure(configUrl: string, assetsHost: string): void;

  /**
   * Push a full config JSON string straight to the widget (fully offline; overrides
   * both the network fetch and the bundled config). Pass '' to clear the override.
   */
  setConfigJson(json: string): void;

  /** Force the widget to reload its config + assets and re-render. Resolves true if a widget is placed. */
  refresh(): Promise<boolean>;

  /** Read the widget's current persisted state without triggering a fetch. */
  getLastResult(): SpinWheelResult;

  // ---- NativeEventEmitter plumbing (event name: 'onSpinResult') ----
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

export default TurboModuleRegistry.getEnforcing<Spec>('SpinWheelWidget');
