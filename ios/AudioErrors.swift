import ExpoModulesCore

internal final class RecordingFailedException: Exception {
  override var reason: String {
    "Recording failed to start."
  }
}

internal final class PlaybackFailedException: Exception {
  override var reason: String {
    "Playback failed to start."
  }
}

internal final class InvalidUrlException: Exception {
  let url: String

  init(url: String) {
    self.url = url
    super.init()
  }

  override var reason: String {
    "Invalid audio file URL provided: \(url)"
  }

  override var code: String {
    "INVALID_URL"
  }
}

internal final class NoRecorderException: Exception {
    override var reason: String {
        "No active recorder to stop."
    }
    override var code: String {
        "NO_RECORDER"
    }
}

internal final class NoPlayerException: Exception {
    override var reason: String {
        "No active player to stop or query."
    }
    override var code: String {
        "NO_PLAYER"
    }
}

internal final class SetSpeedException: Exception {
    override var reason: String {
        "Failed to set playback speed. Invalid speed value."
    }
    override var code: String {
        "INVALID_SPEED"
    }
}

internal final class GetDurationException: Exception {
    let url: String
    let customMessage: String

    init(url: String, message: String) {
        self.url = url
        self.customMessage = message
        super.init()
    }

    override var reason: String {
        "Failed to get duration for URL: \(url). Details: \(customMessage)"
    }

    override var code: String {
        "GET_DURATION_FAILED"
    }
}