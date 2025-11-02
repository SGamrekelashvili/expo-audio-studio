import React, { useEffect, useState } from 'react';
import { StyleSheet, SafeAreaView, View, Text, TouchableOpacity, StatusBar } from 'react-native';
import AudioPlayer from './components/AudioPlayer';
import RecordingTab from './components/RecordingTab';
import ChunkRecorder from './components/ChunkRecorder';

const colors = {
  primary: '#6366F1',
  background: '#F8FAFC',
  surface: '#FFFFFF',
  text: '#1E293B',
  textSecondary: '#64748B',
  border: '#E2E8F0',
};

export default function App() {
  const [activeTab, setActiveTab] = useState<'player' | 'recorder' | 'chunk-recorder'>('player');


  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="dark-content" backgroundColor={colors.background} />

      {/* Header */}
      <View style={styles.header}>
        <Text style={styles.title}>Expo Audio Studio</Text>
        <Text style={styles.subtitle}>Record & Play Audio with VAD</Text>

        {/* Tab Navigation */}
        <View style={styles.tabContainer}>
          <TouchableOpacity
            style={[styles.tab, activeTab === 'player' && styles.activeTab]}
            onPress={() => setActiveTab('player')}
          >
            <Text style={[styles.tabText, activeTab === 'player' && styles.activeTabText]}>
              Player
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.tab, activeTab === 'recorder' && styles.activeTab]}
            onPress={() => setActiveTab('recorder')}
          >
            <Text style={[styles.tabText, activeTab === 'recorder' && styles.activeTabText]}>
              Recorder
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.tab, activeTab === 'chunk-recorder' && styles.activeTab]}
            onPress={() => setActiveTab('chunk-recorder')}
          >
            <Text style={[styles.tabText, activeTab === 'chunk-recorder' && styles.activeTabText]}>
              Chunk Recorder
            </Text>
          </TouchableOpacity>
        </View>
      </View>

      {/* Tab Content */}
      {activeTab === 'player' ? <AudioPlayer /> : activeTab === 'chunk-recorder' ? <ChunkRecorder /> : <RecordingTab />}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
  },
  header: {
    padding: 20,
    paddingBottom: 16,
    alignItems: 'center',
    backgroundColor: colors.surface,
    borderBottomWidth: 1,
    borderBottomColor: colors.border,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  title: {
    fontSize: 28,
    fontWeight: 'bold',
    color: colors.text,
    marginBottom: 4,
  },
  subtitle: {
    fontSize: 14,
    color: colors.textSecondary,
    marginBottom: 20,
  },
  tabContainer: {
    flexDirection: 'row',
    backgroundColor: colors.border,
    borderRadius: 12,
    padding: 4,
    width: '100%',
  },
  tab: {
    flex: 1,
    paddingVertical: 12,
    paddingHorizontal: 16,
    borderRadius: 8,
    alignItems: 'center',
  },
  activeTab: {
    backgroundColor: colors.primary,
  },
  tabText: {
    fontSize: 15,
    fontWeight: '600',
    color: colors.textSecondary,
  },
  activeTabText: {
    color: colors.surface,
  },
});
