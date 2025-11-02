import { Platform, StyleSheet, Text, View } from 'react-native'
import React, { useCallback, useEffect, useRef, useState } from 'react'
import ExpoAudioStudio from 'expo-audio-studio'
import * as FileSystem from 'expo-file-system/legacy'


import { writeAsStringAsync } from 'expo-file-system/legacy';
// @ts-ignore - base-64 library doesn't have types
import { encode as base64Encode, decode as base64Decode } from 'base-64';

const createWavHeader = (dataLength: number, sampleRate: number = 16000, numChannels: number = 1, bitsPerSample: number = 16): Uint8Array => {
  const byteRate = sampleRate * numChannels * bitsPerSample / 8;
  const blockAlign = numChannels * bitsPerSample / 8;
  const chunkSize = 36 + dataLength;
  
  const buffer = new ArrayBuffer(44);
  const view = new DataView(buffer);
  
  view.setUint32(0, 0x52494646, false); 
  view.setUint32(4, chunkSize, true); 
  view.setUint32(8, 0x57415645, false); 
  
  view.setUint32(12, 0x666d7420, false); 
  view.setUint32(16, 16, true); 
  view.setUint16(20, 1, true); 
  view.setUint16(22, numChannels, true); 
  view.setUint32(24, sampleRate, true); 
  view.setUint32(28, byteRate, true); 
  view.setUint16(32, blockAlign, true); 
  view.setUint16(34, bitsPerSample, true); 
  
  view.setUint32(36, 0x64617461, false); 
  view.setUint32(40, dataLength, true); 
  
  return new Uint8Array(buffer);
};

export const chunksToWav = async (base64Chunks: string[], filename: string) => {
  if (base64Chunks.length === 0) {
    throw new Error('No audio chunks to process');
  }
  
  console.log(`Processing ${base64Chunks.length} chunks...`);
  
  const decodedChunks = base64Chunks.map((chunk, index) => {
    try {
      const binaryString = base64Decode(chunk);
      const bytes = new Uint8Array(binaryString.length);
      for (let i = 0; i < binaryString.length; i++) {
        bytes[i] = binaryString.charCodeAt(i);
      }
      return bytes;
    } catch (error) {
      console.error(`Error decoding chunk ${index}:`, error);
      throw error;
    }
  });
  
  const totalLength = decodedChunks.reduce((sum, chunk) => sum + chunk.length, 0);
  
  const pcmData = new Uint8Array(totalLength);
  let offset = 0;
  for (const chunk of decodedChunks) {
    pcmData.set(chunk, offset);
    offset += chunk.length;
  }
  
  const wavHeaderBytes = createWavHeader(totalLength);
  
  console.log(`Creating complete WAV file: ${44 + totalLength} bytes total`);
  const completeWavData = new Uint8Array(44 + totalLength);
  completeWavData.set(wavHeaderBytes, 0);
  completeWavData.set(pcmData, 44);
  
  console.log('Converting complete WAV to base64...');
  const chunkSize = 1024 * 1024; // 1MB chunks
  const base64Parts: string[] = [];
  
  for (let i = 0; i < completeWavData.length; i += chunkSize) {
    const chunk = completeWavData.slice(i, Math.min(i + chunkSize, completeWavData.length));
    const binaryString = Array.from(chunk, byte => String.fromCharCode(byte)).join('');
    base64Parts.push(base64Encode(binaryString));
  }
  
  const wavFileBase64 = base64Parts.join('');
  
  console.log(`Writing WAV file (${wavFileBase64.length} base64 chars)`);
  
  await writeAsStringAsync(filename, wavFileBase64, {
    encoding: 'base64',
  });
  
  return filename;
};

const ChunkRecorder = () => {
    const chunkRef = useRef<string[]>([])
    const [hasPermission, setHasPermission] = useState(false);
    const [isRecording, setIsRecording] = useState(false);
    const [_, setRecordingPath] = useState<string>('');
    useEffect(() => {
        ExpoAudioStudio.setListenToChunks(true)
        ExpoAudioStudio.addListener('onAudioChunk', (chunk) => {
            chunkRef.current = [...chunkRef.current, chunk.base64]
        })

    
      return () => {
        ExpoAudioStudio.removeAllListeners('onAudioChunk')
        ExpoAudioStudio.setListenToChunks(false)
      }
    }, [])

    const handleStartRecording = useCallback(async () => {
    chunkRef.current = [];
    console.log('Starting new recording...');

    try {
      if(Platform.OS === 'ios') {
        await ExpoAudioStudio.configureAudioSession({
          category: 'playAndRecord',
          mode: 'default',
          options: {
            defaultToSpeaker: true,
          allowBluetooth: true,
          allowBluetoothA2DP: true,
        },
      });
        await ExpoAudioStudio.activateAudioSession();
      }

      const path = ExpoAudioStudio.startRecording();
      setIsRecording(true)
      setRecordingPath(path);
      console.log('Recording started:', path);
    } catch (error) {
      console.error('Recording error:', error);
    }
  }, [hasPermission]);

 const handleStopRecording = useCallback(async () => {
  try {
    const path = ExpoAudioStudio.stopRecording();

    console.log('Recording stopped. Native file:', path);
    console.log('Total chunks collected:', chunkRef.current.length);
    

    if (chunkRef.current.length > 0) {
      const joinedPath = `${FileSystem.cacheDirectory}joined_audio_${Date.now()}.wav`;
      
      try {
        await chunksToWav(chunkRef.current, joinedPath);
        console.log('Chunks joined successfully:', joinedPath);
        
        console.log('Playing joined audio:', joinedPath);
        const playResult = ExpoAudioStudio.startPlaying(joinedPath);
        console.log('Playback started:', playResult);
      } catch (chunkError) {
        console.error('Chunk processing error:', chunkError);
      }
      
      chunkRef.current = [];
    }
    
    setRecordingPath(path);
    setIsRecording(false);
  } catch (error) {
    console.error('Stop error:', error);
  }
}, []);

    
  return (
    <View>
        {
            isRecording ? (
                <Text onPress={handleStopRecording} style={{ fontSize: 20, fontWeight: 'bold' }}>Stop Recording</Text>
            ) : (
                <Text onPress={handleStartRecording} style={{ fontSize: 20, fontWeight: 'bold' }}>ChunkRecorder</Text>
            )
        }
    </View>
  )
}

export default ChunkRecorder

const styles = StyleSheet.create({})