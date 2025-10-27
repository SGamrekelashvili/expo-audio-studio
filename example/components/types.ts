export interface AudioRecording {
  path: string;
  name: string;
  duration: number;
  createdAt: Date;
}

export interface VADConfig {
  enabled: boolean;
  threshold: number;
  eventMode: 'onEveryFrame' | 'onChange' | 'throttled';
  throttleMs?: number;
}
