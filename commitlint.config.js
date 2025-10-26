module.exports = {
  extends: ['@commitlint/config-conventional'],
  rules: {
    // Custom rules for this project
    'type-enum': [
      2,
      'always',
      [
        'feat',     // New feature
        'fix',      // Bug fix
        'docs',     // Documentation changes
        'style',    // Code style changes (formatting, etc)
        'refactor', // Code refactoring
        'perf',     // Performance improvements
        'test',     // Adding or updating tests
        'build',    // Build system or external dependencies
        'ci',       // CI/CD changes
        'chore',    // Other changes (maintenance, etc)
        'revert',   // Revert previous commit
        'vad',      // Voice Activity Detection specific changes
        'audio',    // Audio processing specific changes
      ],
    ],
    'scope-enum': [
      2,
      'always',
      [
        'vad',        // Voice Activity Detection
        'recording',  // Audio recording functionality
        'playback',   // Audio playback functionality
        'types',      // TypeScript type definitions
        'api',        // API changes
        'ios',        // iOS specific changes
        'android',    // Android specific changes
        'docs',       // Documentation
        'example',    // Example app
        'tests',      // Test files
        'config',     // Configuration files
        'deps',       // Dependencies
        'release',    // Release related
      ],
    ],
    'subject-case': [2, 'always', 'sentence-case'],
    'subject-max-length': [2, 'always', 100],
    'body-max-line-length': [2, 'always', 100],
    'footer-max-line-length': [2, 'always', 100],
  },
};
