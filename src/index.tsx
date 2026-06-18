import { NativeEventEmitter, type EmitterSubscription } from 'react-native';
import SpinWheelWidget, {
  type SpinWheelResult,
} from './NativeSpinWheelWidget';

export type { SpinWheelResult };

/** Payload emitted when a spin finishes on the home-screen widget (while the app is alive). */
export type SpinResultEvent = {
  /** Final resting angle in degrees (0–360). */
  angle: number;
  /** The app-widget id that spun. */
  widgetId: number;
  /** Index of the segment the wheel landed on. */
  segmentIndex: number;
  /** Hex color of the landed segment, e.g. '#7CB342'. */
  color: string;
  /** Human-readable name of the landed segment, e.g. 'Green'. */
  name: string;
};

const emitter = new NativeEventEmitter(SpinWheelWidget as any);

/**
 * Point the widget at a remote JSON config, optionally overriding the asset host.
 * Pass an empty `assetsHost` to keep the host declared inside the fetched config.
 */
export function configure(configUrl: string, assetsHost: string = ''): void {
  SpinWheelWidget.configure(configUrl, assetsHost);
}

/** Push a full config JSON string to the widget (offline override). Pass '' to clear. */
export function setConfigJson(json: string): void {
  SpinWheelWidget.setConfigJson(json);
}

/** Force the widget to reload its config + assets and re-render. */
export function refresh(): Promise<boolean> {
  return SpinWheelWidget.refresh();
}

/** Read the widget's current persisted state (resting angle, last fetch time, config url). */
export function getLastResult(): SpinWheelResult {
  return SpinWheelWidget.getLastResult();
}

/** Subscribe to spin-completion events. Returns a subscription; call `.remove()` to unsubscribe. */
export function addSpinResultListener(
  listener: (event: SpinResultEvent) => void
): EmitterSubscription {
  return emitter.addListener('onSpinResult', (payload) =>
    listener(payload as SpinResultEvent)
  );
}
