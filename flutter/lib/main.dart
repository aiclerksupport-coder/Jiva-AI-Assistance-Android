import 'dart:async';
import 'dart:io';

import 'package:app_settings/app_settings.dart';
import 'package:audioplayers/audioplayers.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_foreground_task/flutter_foreground_task.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:flutter_native_contact_picker/flutter_native_contact_picker.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_tts/flutter_tts.dart';
import 'package:installed_apps/installed_apps.dart';
import 'package:image_picker/image_picker.dart';
import 'package:jiva_accessibility/jiva_accessibility.dart';
import 'package:jiva_accessibility/jiva_speech.dart';
import 'package:path_provider/path_provider.dart';
import 'package:porcupine_flutter/porcupine_error.dart';
import 'package:porcupine_flutter/porcupine_manager.dart';
import 'package:share_plus/share_plus.dart';
import 'package:speech_to_text/speech_to_text.dart';
import 'package:url_launcher/url_launcher.dart';

import 'domain/action_policy.dart';
import 'domain/assistant_models.dart';
import 'domain/local_command_parser.dart';
import 'services/api_key_store.dart';
import 'services/gemini_repository.dart';
import 'services/gemini_live_client.dart';
import 'services/gemini_retry_policy.dart';
import 'services/reminder_service.dart';
import 'services/wake_word_service.dart';
import 'domain/voice_preset.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  FlutterForegroundTask.initCommunicationPort();
  runApp(const JivaApp());
}

class JivaApp extends StatelessWidget {
  const JivaApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Jiva AI Assistance',
        theme: ThemeData(
          colorScheme: ColorScheme.fromSeed(seedColor: Colors.teal),
          useMaterial3: true,
        ),
        home: const AssistantScreen(),
      );
}

class AssistantScreen extends StatefulWidget {
  const AssistantScreen({super.key});

  @override
  State<AssistantScreen> createState() => _AssistantScreenState();
}

class _AssistantScreenState extends State<AssistantScreen>
    with WidgetsBindingObserver {
  static const _secureStorage = FlutterSecureStorage();
  final _input = TextEditingController();
  final _screenTarget = TextEditingController();
  final _parser = LocalCommandParser();
  final _keyStore = ApiKeyStore(_secureStorage);
  late GeminiRepository _gemini = GeminiRepository();
  final _geminiLive = GeminiLiveClient();
  final _reminderService = ReminderService(_secureStorage);
  final _speech = SpeechToText();
  final _tts = FlutterTts();
  final _audioPlayer = AudioPlayer();
  final _notifications = FlutterLocalNotificationsPlugin();
  final _imagePicker = ImagePicker();
  final _contactPicker = FlutterNativeContactPicker();
  PorcupineManager? _porcupineManager;
  final List<ReminderItem> _reminders = [];

  String _transcript = '';
  String _reply = "Namaste! I'm Jiva. Speak or type in your language.";
  bool _busy = false;
  bool _enabled = true;
  bool _userToggledAssistant = false;
  bool _listening = false;
  bool _hasApiKey = false;
  bool _keyVerified = false;
  String _selectedVoice = defaultVoiceName;
  bool _sleeping = false;
  bool _isForeground = true;
  bool _wakeWordActive = false;
  bool _wakeWordPending = false;
  bool _wakeManagerStarted = false;
  bool _screenAccessibilityEnabled = false;
  bool _screenAssistPending = false;
  bool _noiseProcessingEnabled = true;
  bool _patientListeningEnabled = true;
  String _audioProcessingStatus =
      'Microphone support is checked when listening starts.';
  Future<void>? _wakeManagerPause;
  String _wakeWordError = '';
  String _screenAssistError = '';
  String _screenVisibleText = '';
  Map<String, String> _wakeWordModels = {};
  Timer? _idleTimer;
  IntentPlan? _pendingPlan;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    if (Platform.isAndroid) {
      JivaAccessibility.setResultHandler((type, text, message) {
        if (!mounted) return;
        setState(() {
          _screenAssistPending = false;
          if (type == 'read') _screenVisibleText = text.join('\n');
          _screenAssistError = message;
        });
        _resetSleepTimer();
      });
      JivaSpeech.setCallbacks(
        onResult: (text) {
          if (!mounted) return;
          setState(() => _listening = false);
          if (text.trim().isNotEmpty) _submit(text);
        },
        onPartialResult: (text) {
          if (mounted) setState(() => _transcript = text);
        },
        onError: (message) => _showReply(message),
        onFallback: (message) {
          if (!mounted) return;
          setState(() {
            _audioProcessingStatus = message;
            _listening = true;
          });
          _idleTimer?.cancel();
          unawaited(_listenWithSpeechToText());
        },
        onStatus: (status) {
          if (!mounted) return;
          final active = status == 'listening' || status == 'processing';
          setState(() => _listening = active);
          if (active) {
            _idleTimer?.cancel();
          } else {
            _resetSleepTimer();
            if (!_busy && _pendingPlan == null) {
              unawaited(_resumeWakeWordManager());
            }
          }
        },
        onAudioProcessingStatus: (status) {
          if (mounted) setState(() => _audioProcessingStatus = status);
        },
      );
      _initializeWakeForegroundTask();
      FlutterForegroundTask.addTaskDataCallback(_onWakeTaskData);
    }
    _initialize();
    _resetSleepTimer();
  }

  void _initializeWakeForegroundTask() {
    FlutterForegroundTask.init(
      androidNotificationOptions: const AndroidNotificationOptions(
        channelId: 'jiva_wake_word',
        channelName: 'Jiva wake-word microphone',
        channelDescription:
            'Visible notification while Jiva listens for its wake words.',
        onlyAlertOnce: true,
      ),
      iosNotificationOptions: const IOSNotificationOptions(
        showNotification: false,
        playSound: false,
      ),
      foregroundTaskOptions: ForegroundTaskOptions(
        eventAction: ForegroundTaskEventAction.repeat(60 * 60 * 1000),
        autoRunOnBoot: false,
        allowWakeLock: true,
      ),
    );
  }

  void _onWakeTaskData(Object data) {
    if (data is Map && data['action'] == 'stop-wake-word') {
      unawaited(_stopBackgroundWakeWord());
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _isForeground = state == AppLifecycleState.resumed;
    if (_isForeground) {
      if (Platform.isAndroid) unawaited(_refreshAccessibilityStatus());
      if (_wakeWordPending) {
        unawaited(_handleWakeWord());
      } else {
        _resetSleepTimer();
      }
    } else {
      _idleTimer?.cancel();
    }
  }

  void _resetSleepTimer() {
    _idleTimer?.cancel();
    if (_sleeping && mounted) setState(() => _sleeping = false);
    if (!_enabled ||
        _busy ||
        _listening ||
        _screenAssistPending ||
        _pendingPlan != null ||
        !_isForeground) {
      return;
    }
    _idleTimer = Timer(const Duration(seconds: 30), () {
      if (!mounted ||
          _busy ||
          _listening ||
          _screenAssistPending ||
          _pendingPlan != null) {
        return;
      }
      setState(() => _sleeping = true);
    });
  }

  Future<void> _initialize() async {
    try {
      await _reminderService.initialize();
      final reminders = await _reminderService.list();
      final hasKey = (await _keyStore.read()).isNotEmpty;
      final storedEnabled = await _keyStore.readAssistantEnabled();
      final enabled = _userToggledAssistant ? _enabled : storedEnabled;
      final selectedVoice =
          await _secureStorage.read(key: 'jiva_selected_voice') ??
              defaultVoiceName;
      final noiseProcessingEnabled =
          await _keyStore.readNoiseProcessingEnabled();
      final patientListeningEnabled =
          await _keyStore.readPatientListeningEnabled();
      final wakeModels = await _keyStore.readWakeWordModelPaths();
      final wakeActive =
          Platform.isAndroid && await FlutterForegroundTask.isRunningService;
      final screenAccessibilityEnabled = Platform.isAndroid &&
          await JivaAccessibility.isEnabled();
      if (Platform.isAndroid) {
        await JivaAccessibility.setJivaEnabled(enabled);
      }
      if (!mounted) return;
      setState(() {
        _reminders
          ..clear()
          ..addAll(reminders);
        _hasApiKey = hasKey;
        _enabled = enabled;
        _selectedVoice = voicePresetFor(selectedVoice).name;
        _noiseProcessingEnabled = noiseProcessingEnabled;
        _patientListeningEnabled = patientListeningEnabled;
        _wakeWordModels = wakeModels;
        _wakeWordActive = wakeActive && enabled;
        _screenAccessibilityEnabled = screenAccessibilityEnabled;
      });
      if (wakeActive && enabled) {
        unawaited(_startBackgroundWakeWord(startService: false));
      } else if (wakeActive) {
        await FlutterForegroundTask.stopService();
      }
    } catch (error) {
      _showReply('Could not initialize reminders: $error');
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _idleTimer?.cancel();
    if (Platform.isAndroid) {
      FlutterForegroundTask.removeTaskDataCallback(_onWakeTaskData);
      JivaAccessibility.clearResultHandler();
      JivaSpeech.clearCallbacks();
      unawaited(JivaSpeech.stop());
    }
    _input.dispose();
    _screenTarget.dispose();
    _gemini.close();
    _speech.stop();
    _tts.stop();
    _porcupineManager?.stop();
    _porcupineManager?.delete();
    _audioPlayer.dispose();
    super.dispose();
  }

  Future<void> _submit([String? value]) async {
    final text = (value ?? _input.text).trim();
    if (text.isEmpty || _busy || !_enabled) return;
    _idleTimer?.cancel();
    try {
      await _pauseWakeWordManager();
    } catch (error) {
      _showReply('Could not pause wake-word microphone: $error');
      return;
    }
    setState(() {
      _input.clear();
      _transcript = text;
      _busy = true;
      _sleeping = false;
      _pendingPlan = null;
    });
    final local = _parser.parse(text);
    if (local != null) {
      setState(() {
        _reply = local.reply;
        _pendingPlan = local;
      });
      await _speakReply(local.reply);
      if (mounted && _enabled) {
        setState(() => _busy = false);
        _resetSleepTimer();
        if (_pendingPlan == null) await _resumeWakeWordManager();
      }
      return;
    }
    try {
      final key = await _keyStore.read();
      if (key.isEmpty) {
        _showReply(
          "I didn't recognize that command. Connect your own Gemini API key "
          'for multilingual natural-language understanding.',
        );
        return;
      }
      final json = await _gemini.interpret(
        key,
        text,
        onRetry: (attempt, _) {
          if (mounted && _enabled) {
            setState(() {
              _reply =
                  'Gemini connection interrupted. Retrying (${attempt + 1}/${GeminiRetryPolicy.maxAttempts})…';
            });
          }
        },
      );
      if (!_enabled || !mounted) return;
      final plan = _planFromJson(json);
      if (!mounted) return;
      setState(() {
        _reply = plan.reply;
        _pendingPlan = plan.action == AssistantAction.chat ? null : plan;
      });
      await _speakReply(plan.reply);
      if (mounted && _enabled) {
        setState(() => _busy = false);
        _resetSleepTimer();
        if (_pendingPlan == null) await _resumeWakeWordManager();
      }
    } catch (error) {
      _showReply(error.toString().replaceFirst('Exception: ', ''));
    }
  }

  Future<void> _speakReply(String text) async {
    try {
      if (!_enabled) return;
      final key = await _keyStore.read();
      if (!_enabled) return;
      if (key.isEmpty) {
        await _tts.speak(text);
        return;
      }
      final response = await _geminiLive.respond(
        apiKey: key,
        text: text,
        voiceName: _selectedVoice,
        onRetry: (attempt, _) {
          if (mounted && _enabled) {
            setState(() {
              _reply =
                  'Gemini voice connection interrupted. Retrying (${attempt + 1}/${GeminiRetryPolicy.maxAttempts})…';
            });
          }
        },
      );
      if (!_enabled) return;
      await _tts.stop();
      await _audioPlayer.stop();
      final finished = Completer<void>();
      var playbackStarted = false;
      final stateSubscription = _audioPlayer.onPlayerStateChanged.listen(
        (state) {
          if (state == PlayerState.playing) playbackStarted = true;
          if (playbackStarted &&
              (state == PlayerState.completed || state == PlayerState.stopped) &&
              !finished.isCompleted) {
            finished.complete();
          }
        },
      );
      try {
        await _audioPlayer.play(BytesSource(response.toWav()));
        await finished.future.timeout(const Duration(seconds: 90));
      } finally {
        await stateSubscription.cancel();
      }
    } catch (error) {
      if (mounted && _enabled) {
        setState(() {
          _reply = '$_reply\nVoice response failed: $error';
        });
      }
    }
  }

  Future<void> _startBackgroundWakeWord({bool startService = true}) async {
    if (!_enabled) return;
    if (!Platform.isAndroid) {
      _showReply(
        'Background wake-word listening is available only in the Android app. '
        'iOS voice input is foreground-only.',
      );
      return;
    }
    if (_porcupineManager != null) return;
    PorcupineManager? manager;
    try {
      final accessKey = await _keyStore.readPicovoiceAccessKey();
      final models = await _keyStore.readWakeWordModelPaths();
      if (accessKey.isEmpty) {
        throw StateError('Save a Picovoice AccessKey in Wake-word settings.');
      }
      if (!models.containsKey('jiva') || !models.containsKey('giva')) {
        throw StateError('Import both Android Jiva and Giva .ppn models.');
      }
      for (final path in [models['jiva']!, models['giva']!]) {
        final file = File(path);
        if (!await file.exists()) {
          throw StateError('A saved wake-word model file is missing. Import it again.');
        }
        final length = await file.length();
        if (length == 0 || length > 10 * 1024 * 1024) {
          throw StateError('Wake-word model must be between 1 byte and 10 MB.');
        }
      }
      final speechAvailable = await _speech.initialize(
        onError: (error) => _showReply(
          "Speech recognition failed. Check the phone's speech service.",
        ),
      );
      if (!speechAvailable) {
        throw StateError('Microphone permission or speech service is unavailable.');
      }
      var notificationPermission =
          await FlutterForegroundTask.checkNotificationPermission();
      if (notificationPermission != NotificationPermission.granted) {
        notificationPermission =
            await FlutterForegroundTask.requestNotificationPermission();
      }
      if (notificationPermission != NotificationPermission.granted) {
        throw StateError(
          'Notification permission is required for the microphone foreground service.',
        );
      }
      if (startService || !await FlutterForegroundTask.isRunningService) {
        await FlutterForegroundTask.startService(
          serviceId: 726,
          notificationTitle: 'Jiva wake words are active',
          notificationText: 'Listening locally for Jiva or Giva',
          notificationButtons: const [
            NotificationButton(id: 'stop-wake-word', text: 'Stop'),
          ],
          callback: startJivaWakeForegroundService,
          serviceTypes: const [ForegroundServiceTypes.microphone],
        );
        if (!await FlutterForegroundTask.isRunningService) {
          throw StateError('Android did not start the microphone foreground service.');
        }
      }
      manager = await PorcupineManager.fromKeywordPaths(
        accessKey,
        [models['jiva']!, models['giva']!],
        _onWakeWordDetected,
        sensitivities: const [0.55, 0.55],
        errorCallback: _onWakeWordError,
      );
      await manager.start();
      _porcupineManager = manager;
      _wakeManagerStarted = true;
      if (!mounted) return;
      setState(() {
        _wakeWordModels = models;
        _wakeWordActive = true;
        _wakeWordError = '';
      });
      await FlutterForegroundTask.updateService(
        notificationText: 'Listening locally for Jiva or Giva',
      );
    } catch (error) {
      try {
        await manager?.stop();
      } on PorcupineException {
        // The manager may not have reached the started state.
      }
      await manager?.delete();
      if (await FlutterForegroundTask.isRunningService) {
        await FlutterForegroundTask.stopService();
      }
      if (mounted) {
        setState(() {
          _wakeWordActive = false;
          _wakeWordError = error.toString();
        });
        _showReply('Could not start wake-word listening: $error');
      }
    }
  }

  Future<void> _stopBackgroundWakeWord() async {
    Object? stopError;
    try {
      await _pauseWakeWordManager();
    } catch (error) {
      stopError = error;
    }
    final manager = _porcupineManager;
    _porcupineManager = null;
    if (manager != null) {
      try {
        await manager.delete();
      } catch (error) {
        stopError ??= error;
      }
    }
    _wakeManagerStarted = false;
    try {
      if (Platform.isAndroid && await FlutterForegroundTask.isRunningService) {
        await FlutterForegroundTask.stopService();
        if (await FlutterForegroundTask.isRunningService) {
          throw StateError('Android did not stop the microphone foreground service.');
        }
      }
    } catch (error) {
      stopError ??= error;
    }
    if (mounted) {
      setState(() {
        _wakeWordActive = false;
        if (stopError != null) {
          _wakeWordError = 'Could not stop wake listener: $stopError';
        }
      });
    }
  }

  Future<void> _pauseWakeWordManager() {
    final pendingPause = _wakeManagerPause;
    if (pendingPause != null) return pendingPause;
    final manager = _porcupineManager;
    if (manager == null || !_wakeManagerStarted) return Future<void>.value();
    _wakeManagerStarted = false;
    final pause = manager.stop().whenComplete(() => _wakeManagerPause = null);
    _wakeManagerPause = pause;
    return pause;
  }

  Future<void> _resumeWakeWordManager() async {
    if (!_enabled ||
        !_wakeWordActive ||
        _porcupineManager == null ||
        _wakeManagerStarted ||
        _busy ||
        _listening ||
        _pendingPlan != null) {
      return;
    }
    try {
      await _porcupineManager!.start();
      _wakeManagerStarted = true;
      await FlutterForegroundTask.updateService(
        notificationText: 'Listening locally for Jiva or Giva',
      );
    } catch (error) {
      _wakeWordError = error.toString();
      await _stopBackgroundWakeWord();
      _showReply('Could not resume wake-word listening: $error');
    }
  }

  void _onWakeWordDetected(int keywordIndex) {
    if (!_enabled) {
      unawaited(_stopBackgroundWakeWord());
      return;
    }
    _wakeWordPending = true;
    unawaited(_pauseWakeWordManager());
    unawaited(
      FlutterForegroundTask.updateService(
        notificationText:
            'Wake word detected: ${keywordIndex == 0 ? 'Jiva' : 'Giva'}',
      ),
    );
    if (_isForeground) unawaited(_handleWakeWord());
  }

  void _onWakeWordError(PorcupineException error) {
    _wakeWordError = error.message;
    _wakeWordActive = false;
    unawaited(
      FlutterForegroundTask.updateService(
        notificationText: 'Wake-word error · open Jiva to retry',
      ),
    );
    unawaited(_stopBackgroundWakeWord());
    _showReply('Wake-word engine error: ${error.message}');
  }

  Future<void> _handleWakeWord() async {
    if (!_enabled ||
        !_wakeWordPending ||
        _busy ||
        _listening ||
        _pendingPlan != null) {
      return;
    }
    _wakeWordPending = false;
    try {
      await _pauseWakeWordManager();
    } catch (error) {
      _showReply('Could not pause wake-word microphone: $error');
      return;
    }
    if (!mounted) return;
    setState(() {
      _sleeping = false;
      _busy = true;
      _reply = 'Ji, boliye';
    });
    _idleTimer?.cancel();
    await _speakReply('Ji, boliye');
    if (!mounted) return;
    setState(() => _busy = false);
    await _listen();
  }

  IntentPlan _planFromJson(Map<String, dynamic> json) {
    String? bounded(String key, int maxLength) {
      final value = json[key];
      if (value is! String || value.trim().isEmpty) return null;
      final trimmed = value.trim();
      return trimmed.length > maxLength
          ? trimmed.substring(0, maxLength)
          : trimmed;
    }

    final settingsTarget = bounded('settingsTarget', 20)?.toLowerCase();
    final phoneAction = bounded('phoneAction', 30)?.toLowerCase();
    final requestedAction = actionFromName(json['action'] as String?);
    return IntentPlan(
      action: requestedAction == AssistantAction.phoneNavigation &&
              !supportedPhoneNavigationActions.contains(phoneAction)
          ? AssistantAction.chat
          : requestedAction,
      reply: bounded('reply', 2000) ?? 'What would you like Jiva to do?',
      appName: bounded('appName', 100),
      recipientQuery: bounded('recipientQuery', 100),
      message: bounded('message', 2000),
      settingsTarget: const {'general', 'wifi', 'bluetooth', 'display', 'sound'}
              .contains(settingsTarget)
          ? settingsTarget
          : 'general',
      phoneAction: phoneAction,
    );
  }

  Future<void> _listen() async {
    if (!_enabled) return;
    if (!_enabled) return;
    _idleTimer?.cancel();
    try {
      await _pauseWakeWordManager();
      if (!_speech.isAvailable) {
        final available = await _speech.initialize(
          onStatus: (status) {
            if (!mounted) return;
            setState(() => _listening = status == 'listening');
            if (status == 'listening') {
              _idleTimer?.cancel();
            } else {
              _resetSleepTimer();
              if (!_busy && _pendingPlan == null) {
                unawaited(_resumeWakeWordManager());
              }
            }
          },
          onError: (error) => _showReply(
            "Speech recognition failed. Check the phone's speech service and try again.",
          ),
        );
        if (!available) {
          _showReply('Speech recognition is not available on this phone.');
          return;
        }
      }
      if (Platform.isAndroid && _noiseProcessingEnabled) {
        if (mounted) {
          setState(
            () => _audioProcessingStatus =
                'Checking Android microphone processing…',
          );
        }
        final enhanced = await JivaSpeech.start(
          patientListening: _patientListeningEnabled,
        );
        if (enhanced) {
          if (mounted) setState(() => _listening = true);
          _idleTimer?.cancel();
          return;
        }
        if (mounted) {
          setState(
            () => _audioProcessingStatus =
                'Using standard phone speech recognition; enhanced processing is unavailable on this device or Android version.',
          );
        }
      }
      await _listenWithSpeechToText();
    } catch (error) {
      _showReply('Could not start speech recognition: $error');
    }
  }

  Future<void> _listenWithSpeechToText() async {
    try {
      await _speech.listen(
        onResult: (result) {
          if (result.finalResult && result.recognizedWords.trim().isNotEmpty) {
            _speech.stop();
            _submit(result.recognizedWords);
          }
        },
        listenFor: _patientListeningEnabled
            ? const Duration(seconds: 60)
            : const Duration(seconds: 30),
        pauseFor: _patientListeningEnabled
            ? const Duration(seconds: 6)
            : const Duration(seconds: 3),
      );
    } catch (error) {
      _showReply('Could not start speech recognition: $error');
    }
  }

  Future<void> _stop() async {
    if (Platform.isAndroid) await JivaSpeech.stop();
    await _speech.stop();
    await _tts.stop();
    await _audioPlayer.stop();
    if (mounted) {
      setState(() {
        _listening = false;
      });
      _resetSleepTimer();
    }
    await _resumeWakeWordManager();
  }

  Future<void> _setNoiseProcessingEnabled(bool enabled) async {
    try {
      await _keyStore.saveNoiseProcessingEnabled(enabled);
      if (mounted) setState(() => _noiseProcessingEnabled = enabled);
    } catch (error) {
      _showReply('Could not save microphone processing setting: $error');
    }
  }

  Future<void> _setPatientListeningEnabled(bool enabled) async {
    try {
      await _keyStore.savePatientListeningEnabled(enabled);
      if (mounted) setState(() => _patientListeningEnabled = enabled);
    } catch (error) {
      _showReply('Could not save patient listening setting: $error');
    }
  }

  Future<void> _setAssistantEnabled(bool enabled) async {
    if (_enabled == enabled) return;
    _userToggledAssistant = true;
    _idleTimer?.cancel();
    if (!enabled && Platform.isAndroid) await JivaSpeech.stop();
    setState(() {
      _enabled = enabled;
      _busy = false;
      _listening = false;
      _screenAssistPending = false;
      _sleeping = false;
      _pendingPlan = null;
      _reply = enabled ? 'Jiva is on. How can I help?' : 'Jiva is off.';
    });
    Object? cleanupError;
    try {
      await _keyStore.saveAssistantEnabled(enabled);
    } catch (error) {
      cleanupError = error;
    }
    if (Platform.isAndroid) {
      try {
        await JivaAccessibility.setJivaEnabled(enabled);
      } catch (error) {
        cleanupError ??= error;
      }
    }
    if (!enabled) {
      _gemini.close();
      _gemini = GeminiRepository();
      try {
        await _speech.stop();
      } catch (error) {
        cleanupError ??= error;
      }
      try {
        await _tts.stop();
      } catch (error) {
        cleanupError ??= error;
      }
      try {
        await _audioPlayer.stop();
      } catch (error) {
        cleanupError ??= error;
      }
      try {
        await _geminiLive.cancel();
      } catch (error) {
        cleanupError ??= error;
      }
      if (Platform.isAndroid) {
        await _stopBackgroundWakeWord();
      }
      if (mounted && cleanupError != null) {
        setState(() => _reply = 'Jiva is off, but cleanup reported: $cleanupError');
      }
    } else {
      _gemini = GeminiRepository();
      _resetSleepTimer();
    }
  }

  Future<void> _readVisibleScreen() async {
    if (!_enabled || !Platform.isAndroid) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Read the current screen?'),
        content: const Text(
          'Jiva reads visible accessibility text locally only after this confirmation. '
          'Do not continue on a private, login, banking, payment, or otherwise secure screen. '
          'Android does not expose a reliable indicator for every secure screen.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Read this screen'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted || !_enabled) return;

    try {
      final isEnabled = await JivaAccessibility.isEnabled();
      if (!isEnabled) {
        await JivaAccessibility.openSettings();
        if (mounted) {
          setState(() {
            _screenAccessibilityEnabled = false;
            _screenAssistError =
                'Enable Jiva screen assistance in Android Accessibility Settings, then return here.';
          });
        }
        return;
      }
      await _pauseWakeWordManager();
      await JivaAccessibility.requestVisibleScreenRead();
      if (mounted) {
        setState(() {
          _screenAccessibilityEnabled = true;
          _screenVisibleText = '';
          _screenAssistPending = true;
          _screenAssistError =
              'Switch to the target app within 30 seconds. Jiva will capture that screen once.';
        });
        _resetSleepTimer();
      }
    } catch (error) {
      if (mounted) setState(() => _screenAssistError = '$error');
    }
  }

  Future<void> _refreshAccessibilityStatus() async {
    try {
      final enabled = await JivaAccessibility.isEnabled();
      if (mounted) setState(() => _screenAccessibilityEnabled = enabled);
    } catch (error) {
      if (mounted) setState(() => _screenAssistError = '$error');
    }
  }

  Future<void> _performVisibleScreenAction(String action) async {
    if (!_enabled || !Platform.isAndroid) return;
    try {
      await _pauseWakeWordManager();
      await JivaAccessibility.queueScreenAction(action);
      if (mounted) {
        setState(() {
          _screenVisibleText = '';
          _screenAssistPending = true;
          _screenAssistError = 'Switch to the target app within 30 seconds.';
        });
        _resetSleepTimer();
      }
    } catch (error) {
      if (mounted) setState(() => _screenAssistError = '$error');
    }
  }

  Future<void> _clickVisibleScreenLabel() async {
    final target = _screenTarget.text.trim();
    if (!_enabled || !Platform.isAndroid || target.isEmpty) return;
    final sensitive = RegExp(
      r'(?:\b(send|delete|pay|purchase|transfer|confirm|submit|post|buy|remove|share)\b|भेज|हट|मिटा|खरीद|भुगतान|पैसा|स्थानांतरण|पुष्टि|साझा)',
      caseSensitive: false,
    ).hasMatch(target);
    final firstConfirmation =
        await _confirmVisibleClick(target, sensitive: sensitive);
    if (!firstConfirmation || !mounted || !_enabled) return;
    if (sensitive) {
      final secondConfirmation = await _confirmVisibleClick(
        target,
        sensitive: true,
        finalStep: true,
      );
      if (!secondConfirmation || !mounted || !_enabled) return;
    }
    try {
      await _pauseWakeWordManager();
      await JivaAccessibility.queueVisibleTextClick(target);
      if (mounted) {
        setState(() {
          _screenVisibleText = '';
          _screenAssistPending = true;
          _screenAssistError = 'Switch to the target app within 30 seconds.';
        });
        _resetSleepTimer();
      }
    } catch (error) {
      if (mounted) setState(() => _screenAssistError = '$error');
    }
  }

  Future<bool> _confirmVisibleClick(
    String target, {
    required bool sensitive,
    bool finalStep = false,
  }) async {
    return await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            title: Text(
              sensitive && finalStep
                  ? 'Confirm sensitive click'
                  : 'Click visible control?',
            ),
            content: Text(
              sensitive && finalStep
                  ? 'Final confirmation: click "$target" now? This could send, delete, purchase, or share information. Verify the app and screen before proceeding.'
                  : 'Jiva will click the unique visible control labeled "$target". Review the current app and screen. Sensitive labels require a second confirmation.',
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: const Text('Cancel'),
              ),
              FilledButton(
                onPressed: () => Navigator.pop(context, true),
                child: Text(
                  sensitive && !finalStep ? 'Review again' : 'Confirm click',
                ),
              ),
            ],
          ),
        ) ??
        false;
  }

  void _showReply(String value) {
    if (!mounted || !_enabled) return;
    setState(() {
      _busy = false;
      _reply = value;
      _pendingPlan = null;
      _sleeping = false;
    });
    _resetSleepTimer();
    if (_pendingPlan == null) unawaited(_resumeWakeWordManager());
  }

  Future<void> _openKeySettings() async {
    _idleTimer?.cancel();
    try {
      await _pauseWakeWordManager();
    } catch (error) {
      _showReply('Could not pause wake-word microphone: $error');
      return;
    }
    final controller = TextEditingController();
    final save = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Connect Gemini to Jiva'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: controller,
              obscureText: true,
              decoration: const InputDecoration(labelText: 'Your Gemini API key'),
            ),
            const SizedBox(height: 12),
            const Text(
              'Stored securely on this device. Gemini receives your text; '
              'Live voice uses a WebSocket URL containing the key. Use '
              'short-lived tokens from a backend for production.',
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () async {
              await _keyStore.clear();
              if (context.mounted) Navigator.pop(context, false);
            },
            child: const Text('Remove key'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Test & save'),
          ),
        ],
      ),
    );
    if (save == true) {
      try {
        setState(() {
          _busy = true;
          _reply = 'Verifying Gemini key and model access…';
        });
        await _gemini.verifyApiKey(
          controller.text.trim(),
          onRetry: (attempt, _) {
            if (mounted && _enabled) {
              setState(() {
                _reply =
                    'Gemini key verification interrupted. Retrying (${attempt + 1}/${GeminiRetryPolicy.maxAttempts})…';
              });
            }
          },
        );
        await _keyStore.save(controller.text);
        if (mounted) {
          setState(() {
            _busy = false;
            _hasApiKey = true;
            _keyVerified = true;
            _reply = 'Gemini key and model access verified.';
          });
        }
      } catch (error) {
        _showReply(error.toString());
      } finally {
        if (mounted) {
          setState(() => _busy = false);
          _resetSleepTimer();
        }
      }
    } else if (save == false && mounted) {
      setState(() {
        _hasApiKey = false;
        _keyVerified = false;
      });
    }
    controller.dispose();
    if (mounted) {
      _resetSleepTimer();
      await _resumeWakeWordManager();
    }
  }

  Future<void> _importWakeWordModel(String name) async {
    try {
      final selection = await FilePicker.platform.pickFiles(
        type: FileType.custom,
        allowedExtensions: const ['ppn'],
        withData: false,
      );
      final sourcePath = selection?.files.single.path;
      if (sourcePath == null) return;
      final source = File(sourcePath);
      final size = await source.length();
      if (!source.path.toLowerCase().endsWith('.ppn') ||
          size == 0 ||
          size > 10 * 1024 * 1024) {
        throw StateError('Choose a non-empty .ppn model no larger than 10 MB.');
      }
      final directory = Directory(
        '${(await getApplicationSupportDirectory()).path}/jiva_wake_words',
      );
      await directory.create(recursive: true);
      final target = File('${directory.path}/$name.ppn');
      if (source.absolute.path != target.absolute.path) {
        await source.copy(target.path);
      }
      await _keyStore.saveWakeWordModelPath(name, target.path);
      final paths = await _keyStore.readWakeWordModelPaths();
      if (mounted) {
        setState(() {
          _wakeWordModels = paths;
          _wakeWordError = '';
        });
      }
    } catch (error) {
      if (mounted) {
        setState(() => _wakeWordError = 'Could not import the model: $error');
      }
    }
  }

  Future<void> _openWakeWordSettings() async {
    _idleTimer?.cancel();
    try {
      await _pauseWakeWordManager();
    } catch (error) {
      _showReply('Could not pause wake-word microphone: $error');
      return;
    }
    final controller = TextEditingController();
    var dialogMessage = _wakeWordError;
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, updateDialog) => AlertDialog(
          title: const Text('Background wake words'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                TextField(
                  controller: controller,
                  obscureText: true,
                  decoration: const InputDecoration(
                    labelText: 'Picovoice AccessKey',
                  ),
                ),
                const SizedBox(height: 8),
                const Text(
                  'Stored with platform secure storage. Generate Android .ppn '
                  'models for “Jiva” and “Giva” in Picovoice Console.',
                ),
                const Text(
                  'Stop background listening before changing the AccessKey or '
                  'models, then start it again.',
                ),
                TextButton(
                  onPressed: () async {
                    try {
                      await _keyStore.savePicovoiceAccessKey(controller.text);
                      controller.clear();
                      updateDialog(
                        () => dialogMessage = 'Picovoice AccessKey saved.',
                      );
                    } catch (error) {
                      updateDialog(() => dialogMessage = error.toString());
                    }
                  },
                  child: const Text('Save AccessKey'),
                ),
                TextButton(
                  onPressed: () async {
                    await _importWakeWordModel('jiva');
                    updateDialog(
                      () => dialogMessage = _wakeWordError.isEmpty
                          ? 'Jiva model imported.'
                          : _wakeWordError,
                    );
                  },
                  child: Text(
                    'Import Jiva .ppn · '
                    '${_wakeWordModels.containsKey('jiva') ? 'ready' : 'missing'}',
                  ),
                ),
                TextButton(
                  onPressed: () async {
                    await _importWakeWordModel('giva');
                    updateDialog(
                      () => dialogMessage = _wakeWordError.isEmpty
                          ? 'Giva model imported.'
                          : _wakeWordError,
                    );
                  },
                  child: Text(
                    'Import Giva .ppn · '
                    '${_wakeWordModels.containsKey('giva') ? 'ready' : 'missing'}',
                  ),
                ),
                if (dialogMessage.isNotEmpty)
                  Text(
                    dialogMessage,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                const SizedBox(height: 8),
                const Text(
                  'Android shows a persistent microphone notification. '
                  'Force-stop and device battery policies can stop listening. '
                  'iOS does not allow passive wake-word listening.',
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () async {
                try {
                  await _stopBackgroundWakeWord();
                  for (final path in _wakeWordModels.values) {
                    final file = File(path);
                    if (await file.exists()) await file.delete();
                  }
                  await _keyStore.clearWakeWordConfig();
                  if (mounted) setState(() => _wakeWordModels = {});
                  if (dialogContext.mounted) Navigator.pop(dialogContext);
                } catch (error) {
                  updateDialog(() => dialogMessage = error.toString());
                }
              },
              child: const Text('Remove setup'),
            ),
            TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: const Text('Close'),
            ),
            FilledButton(
              onPressed: () async {
                if (controller.text.trim().isNotEmpty) {
                  await _keyStore.savePicovoiceAccessKey(controller.text);
                }
                await _startBackgroundWakeWord();
                if (_wakeWordActive && dialogContext.mounted) {
                  Navigator.pop(dialogContext);
                } else {
                  updateDialog(
                    () => dialogMessage = _wakeWordError.isEmpty
                        ? 'Wake-word listening could not start.'
                        : _wakeWordError,
                  );
                }
              },
              child: const Text('Start listening'),
            ),
          ],
        ),
      ),
    );
    controller.dispose();
    if (mounted) {
      _resetSleepTimer();
      await _resumeWakeWordManager();
    }
  }

  Future<void> _showPlan(IntentPlan plan) async {
    if (!_enabled) return;
    _idleTimer?.cancel();
    try {
      await _pauseWakeWordManager();
    } catch (error) {
      _showReply('Could not pause wake-word microphone: $error');
      return;
    }
    if (mounted) {
      setState(() {
        _pendingPlan = plan;
        _sleeping = false;
      });
    }
    final number = TextEditingController(text: plan.phoneNumber ?? '');
    final message = TextEditingController(text: plan.message ?? '');
    final appName = TextEditingController(text: plan.appName ?? '');
    final accepted = await showDialog<IntentPlan>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(_planTitle(plan.action)),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(_planDescription(plan)),
              if ({
                AssistantAction.call,
                AssistantAction.sms,
                AssistantAction.whatsappShare,
              }.contains(plan.action)) ...[
                const SizedBox(height: 12),
                TextField(
                  controller: number,
                  keyboardType: TextInputType.phone,
                  decoration: const InputDecoration(
                    labelText: 'Phone number',
                    helperText: 'Choose or enter the number yourself.',
                  ),
                ),
                Align(
                  alignment: Alignment.centerLeft,
                  child: TextButton.icon(
                    onPressed: () async {
                      try {
                        final contact =
                            await _contactPicker.selectPhoneNumber();
                        final phoneNumbers = contact?.phoneNumbers;
                        final selected = contact?.selectedPhoneNumber ??
                            (phoneNumbers != null && phoneNumbers.isNotEmpty
                                ? phoneNumbers.first
                                : null);
                        if (selected != null) number.text = selected;
                      } catch (error) {
                        _showReply('Could not open the contact picker: $error');
                      }
                    },
                    icon: const Icon(Icons.contacts_outlined),
                    label: const Text('Choose contact'),
                  ),
                ),
              ],
              if (plan.action == AssistantAction.openApp) ...[
                const SizedBox(height: 12),
                TextField(
                  controller: appName,
                  decoration: const InputDecoration(labelText: 'App name'),
                ),
              ],
              if ({
                AssistantAction.sms,
                AssistantAction.whatsappShare,
              }.contains(plan.action)) ...[
                const SizedBox(height: 8),
                TextField(
                  controller: message,
                  maxLines: 3,
                  decoration: const InputDecoration(labelText: 'Message draft'),
                ),
              ],
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () {
              final updated = plan.copyWith(
                appName: appName.text.trim(),
                phoneNumber: number.text.trim(),
                message: message.text.trim(),
              );
              if (ActionPolicy.canDispatch(updated)) {
                Navigator.pop(dialogContext, updated);
              }
            },
            child: const Text('Review / continue'),
          ),
        ],
      ),
    );
    number.dispose();
    message.dispose();
    appName.dispose();
    if (accepted != null) {
      await _dispatch(accepted);
    } else if (mounted) {
      setState(() => _pendingPlan = null);
      _resetSleepTimer();
      await _resumeWakeWordManager();
    }
  }

  String _planTitle(AssistantAction action) => switch (action) {
        AssistantAction.call => 'Confirm call',
        AssistantAction.sms => 'Review message',
        AssistantAction.whatsappShare => 'Review WhatsApp handoff',
        AssistantAction.openSettings => 'Open device settings?',
        AssistantAction.camera => 'Open camera?',
        AssistantAction.openApp => 'Open app?',
        AssistantAction.reminder => 'Create reminder?',
        AssistantAction.phoneNavigation => 'Navigate phone?',
        AssistantAction.chat => 'Jiva',
      };

  String _planDescription(IntentPlan plan) => switch (plan.action) {
        AssistantAction.call =>
          'Jiva opens the system dialer. You must tap Call there.',
        AssistantAction.sms =>
          'Review the SMS in your messaging app and tap Send there.',
        AssistantAction.whatsappShare =>
          'Choose the chat and tap Send in WhatsApp. Jiva cannot send silently.',
        AssistantAction.openSettings =>
          'Jiva opens Android settings; you approve protected changes there.',
        AssistantAction.camera =>
          'Open the camera app on this device.',
        AssistantAction.openApp => 'Open ${plan.appName ?? 'the selected app'} if installed.',
        AssistantAction.reminder =>
          'Choose reminder text and time. Jiva will schedule a local notification.',
        AssistantAction.phoneNavigation => switch (plan.phoneAction) {
            'home' => 'Go to the Home screen.',
            'back' => 'Use Android Back.',
            'recents' => 'Open the recent apps screen.',
            'notifications' =>
              'Open the notification shade, which may show private notifications.',
            'quick_settings' => 'Open Android Quick Settings.',
            _ => 'Run the requested phone navigation action.',
          },
        AssistantAction.chat => plan.reply,
      };

  Future<void> _dispatch(IntentPlan plan) async {
    if (!_enabled) return;
    _idleTimer?.cancel();
    await _pauseWakeWordManager();
    setState(() {
      _pendingPlan = null;
      _busy = true;
      _sleeping = false;
    });
    try {
      switch (plan.action) {
        case AssistantAction.call:
          await _launch(Uri(scheme: 'tel', path: plan.phoneNumber));
          break;
        case AssistantAction.sms:
          await _launch(
            Uri(
              scheme: 'sms',
              path: plan.phoneNumber,
              queryParameters: {'body': plan.message ?? ''},
            ),
          );
          break;
        case AssistantAction.whatsappShare:
          await Share.share(plan.message ?? '');
          break;
        case AssistantAction.openSettings:
          await AppSettings.openAppSettings(
            type: switch (plan.settingsTarget) {
              'wifi' => AppSettingsType.wifi,
              'bluetooth' => AppSettingsType.bluetooth,
              'display' => AppSettingsType.display,
              'sound' => AppSettingsType.sound,
              _ => AppSettingsType.settings,
            },
          );
          break;
        case AssistantAction.camera:
          final image = await _imagePicker.pickImage(source: ImageSource.camera);
          if (image != null) _showReply('Camera image captured on this device.');
          break;
        case AssistantAction.openApp:
          await _openInstalledApp(plan.appName ?? '');
          break;
        case AssistantAction.reminder:
          await _editReminder();
          break;
        case AssistantAction.phoneNavigation:
          if (!Platform.isAndroid) {
            throw UnsupportedError('Phone navigation is available only on Android.');
          }
          final completed = await JivaAccessibility.performPhoneNavigation(
            plan.phoneAction ?? '',
          );
          if (!completed) {
            throw StateError(
              'Android could not perform that action. Check that Jiva screen assistance is enabled.',
            );
          }
          _showReply('Phone navigation action completed.');
          break;
        case AssistantAction.chat:
          return;
      }
    } catch (error) {
      _showReply('Could not complete that handoff: $error');
    } finally {
      if (mounted) {
        setState(() => _busy = false);
        _resetSleepTimer();
        if (_pendingPlan == null) await _resumeWakeWordManager();
      }
    }
  }

  Future<void> _launch(Uri uri) async {
    if (!await launchUrl(uri, mode: LaunchMode.externalApplication)) {
      throw Exception('No installed app can handle this action.');
    }
  }

  Future<void> _openInstalledApp(String query) async {
    final apps = await InstalledApps.getInstalledApps(
      excludeSystemApps: true,
      excludeNonLaunchableApps: true,
      detectPlatformType: false,
    );
    final matching = apps.where(
      (app) => app.name.toLowerCase() == query.toLowerCase(),
    );
    final exact = matching.isEmpty ? null : matching.first;
    var packageName = exact?.packageName;
    var appName = exact?.name;
    for (final candidate in apps) {
      if (packageName == null &&
          candidate.name.toLowerCase().contains(query.toLowerCase())) {
        packageName = candidate.packageName;
        appName = candidate.name;
        break;
      }
    }
    if (packageName == null) {
      throw Exception('No matching installed app was found.');
    }
    if (await InstalledApps.startApp(packageName) != true) {
      throw Exception('Could not open $appName.');
    }
  }

  Future<void> _editReminder() async {
    final wasBusy = _busy;
    _idleTimer?.cancel();
    if (!wasBusy && mounted) setState(() => _busy = true);
    final text = TextEditingController();
    var selected = DateTime.now().add(const Duration(hours: 1));
    var notificationsDisabled = false;
    final reminder = await showDialog<ReminderItem>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, updateDialog) => AlertDialog(
          title: const Text('New Jiva reminder'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: text,
                decoration: const InputDecoration(
                  labelText: 'What should Jiva remind you?',
                ),
              ),
              TextButton(
                onPressed: () async {
                  final date = await showDatePicker(
                    context: context,
                    initialDate: selected,
                    firstDate: DateTime.now(),
                    lastDate: DateTime.now().add(const Duration(days: 3650)),
                  );
                  if (date != null) {
                    updateDialog(
                      () => selected = DateTime(
                        date.year,
                        date.month,
                        date.day,
                        selected.hour,
                        selected.minute,
                      ),
                    );
                  }
                },
                child: Text('Date: ${selected.year}-${selected.month.toString().padLeft(2, '0')}-${selected.day.toString().padLeft(2, '0')}'),
              ),
              TextButton(
                onPressed: () async {
                  final time = await showTimePicker(
                    context: context,
                    initialTime: TimeOfDay.fromDateTime(selected),
                  );
                  if (time != null) {
                    updateDialog(
                      () => selected = DateTime(
                        selected.year,
                        selected.month,
                        selected.day,
                        time.hour,
                        time.minute,
                      ),
                    );
                  }
                },
                child: Text('Time: ${TimeOfDay.fromDateTime(selected).format(context)}'),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () async {
                try {
                  if (text.text.trim().isEmpty) {
                    throw ArgumentError('Reminder text cannot be empty.');
                  }
                  if (!selected.isAfter(DateTime.now())) {
                    throw ArgumentError('Choose a time in the future.');
                  }
                  final androidPermission = await _notifications
                      .resolvePlatformSpecificImplementation<
                          AndroidFlutterLocalNotificationsPlugin>()
                      ?.requestNotificationsPermission();
                  final iosPermission = await _notifications
                      .resolvePlatformSpecificImplementation<
                          IOSFlutterLocalNotificationsPlugin>()
                      ?.requestPermissions(alert: true, badge: true, sound: true);
                  notificationsDisabled =
                      androidPermission == false || iosPermission == false;
                  final result =
                      await _reminderService.schedule(text.text, selected);
                  if (dialogContext.mounted) Navigator.pop(dialogContext, result);
                } catch (error) {
                  _showReply(error.toString());
                }
              },
              child: const Text('Schedule'),
            ),
          ],
        ),
      ),
    );
    text.dispose();
    if (reminder != null && mounted) {
      setState(() {
        _reminders.add(reminder);
        _reminders.sort((a, b) => a.triggerAt.compareTo(b.triggerAt));
        _reply = notificationsDisabled
            ? 'Reminder scheduled, but notifications are disabled. '
                'Enable them in device Settings to see alerts.'
            : 'Reminder scheduled: ${reminder.text}';
      });
    }
    if (!wasBusy && mounted) {
      setState(() => _busy = false);
      _resetSleepTimer();
      await _resumeWakeWordManager();
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(
          title: const Text('Jiva'),
          actions: [
            Text(_enabled ? 'On' : 'Off'),
            Switch(
              value: _enabled,
              onChanged: _setAssistantEnabled,
            ),
            IconButton(
              tooltip: 'Wake-word settings',
              onPressed: _openWakeWordSettings,
              icon: const Icon(Icons.hearing),
            ),
            TextButton(
              onPressed: _openKeySettings,
              child: Text(
                !_hasApiKey
                    ? 'Gemini key'
                    : _keyVerified
                        ? 'Gemini verified'
                        : 'Gemini key saved',
              ),
            ),
          ],
        ),
        body: SafeArea(
          child: ListView(
            padding: const EdgeInsets.all(20),
            children: [
              Text(
                'Your assistant · user-controlled',
                style: Theme.of(context).textTheme.bodyMedium,
              ),
              const SizedBox(height: 16),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(
                        _busy
                            ? 'Jiva is working or speaking…'
                            : _listening
                                ? 'Jiva is listening…'
                                : !_enabled
                                    ? 'Jiva is off · turn it on to use the assistant'
                                    : _screenAssistPending
                                        ? 'Jiva is waiting for the target app…'
                                        : _sleeping
                                            ? 'Jiva is sleeping · tap Speak to wake'
                                            : 'Talk or type to Jiva',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      if (_transcript.isNotEmpty) Text('You: $_transcript'),
                      const SizedBox(height: 8),
                      Text(_reply),
                      const SizedBox(height: 12),
                      DropdownButtonFormField<String>(
                        value: _selectedVoice,
                        decoration: const InputDecoration(labelText: 'Jiva voice'),
                        items: voicePresets
                            .map(
                              (voice) => DropdownMenuItem(
                                value: voice.name,
                                child: Text(
                                  '${voice.perceivedVoice}: ${voice.label}',
                                ),
                              ),
                            )
                            .toList(),
                        onChanged: _enabled ? (value) async {
                          if (value == null) return;
                          await _secureStorage.write(
                            key: 'jiva_selected_voice',
                            value: value,
                          );
                          if (mounted) {
                            setState(() => _selectedVoice = value);
                          }
                        } : null,
                      ),
                      const SizedBox(height: 8),
                      Row(
                        children: [
                          Expanded(
                            child: FilledButton.icon(
                              onPressed: !_enabled || _busy || _listening
                                  ? null
                                  : () async {
                                      if (_sleeping) {
                                            _wakeWordPending = true;
                                            await _handleWakeWord();
                                          } else {
                                            await _listen();
                                      }
                                    },
                              icon: const Icon(Icons.mic),
                              label: Text(_listening ? 'Listening…' : 'Speak'),
                            ),
                          ),
                          const SizedBox(width: 8),
                          OutlinedButton(
                            onPressed: _enabled ? _stop : null,
                            child: const Text('Stop'),
                          ),
                        ],
                      ),
                      TextField(
                        controller: _input,
                        enabled: _enabled,
                        decoration: const InputDecoration(
                          labelText: 'Type in your language',
                        ),
                        onChanged: (_) {
                          if (_sleeping) setState(() => _sleeping = false);
                          _resetSleepTimer();
                        },
                        onSubmitted: (_) => _submit(),
                      ),
                      const SizedBox(height: 8),
                      FilledButton(
                        onPressed: !_enabled || _busy ? null : _submit,
                        child: const Text('Send'),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 18),
              Card(
                child: ListTile(
                  leading: Icon(
                    _wakeWordActive ? Icons.hearing : Icons.hearing_disabled,
                  ),
                  title: Text(
                    _wakeWordActive
                        ? 'Background wake-word listening is on'
                        : 'Background wake-word listening is off',
                  ),
                  subtitle: Text(
                    _wakeWordError.isNotEmpty
                        ? _wakeWordError
                        : 'Android only · local Porcupine detection · Jiva/Giva',
                  ),
                  trailing: TextButton(
                    onPressed: !_enabled
                        ? null
                        : _wakeWordActive
                            ? _stopBackgroundWakeWord
                            : _openWakeWordSettings,
                    child: Text(_wakeWordActive ? 'Stop' : 'Set up'),
                  ),
                ),
              ),
              const SizedBox(height: 18),
              Card(
                child: Column(
                  children: [
                    ListTile(
                      title: const Text('Voice input'),
                      subtitle: Text(_audioProcessingStatus),
                    ),
                    SwitchListTile(
                      value: _noiseProcessingEnabled,
                      onChanged: _enabled ? _setNoiseProcessingEnabled : null,
                      title: const Text('Clear voice processing'),
                      subtitle: const Text(
                        'Use Android noise reduction and automatic gain when supported. Other platforms use their built-in microphone processing.',
                      ),
                    ),
                    SwitchListTile(
                      value: _patientListeningEnabled,
                      onChanged: _enabled ? _setPatientListeningEnabled : null,
                      title: const Text('Patient listening'),
                      subtitle: const Text(
                        'Wait longer through pauses and slow speech.',
                      ),
                    ),
                    const Padding(
                      padding: EdgeInsets.fromLTRB(16, 0, 16, 12),
                      child: Text(
                        'Noise processing is best-effort: Android 13+ and the speech service/device must support supplied microphone audio. Otherwise Jiva uses standard phone speech recognition. The speech service may process audio remotely; only recognized text is sent to Gemini. Very loud noise can still affect accuracy.',
                      ),
                    ),
                  ],
                ),
              ),
              if (Platform.isAndroid) ...[
                const SizedBox(height: 18),
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Text(
                          'Visible-screen assistance',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        const SizedBox(height: 8),
                        Text(
                          'Optional Android Accessibility access. After confirming, switch to the target app within 30 seconds; Jiva reads or acts only once on that next app screen. Text stays local and is not sent to Gemini. Android cannot reliably identify every secure window; do not use this on private, banking, login, or payment screens. Password fields block all screen actions.',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                        const SizedBox(height: 8),
                        OutlinedButton(
                          onPressed: !_enabled ? null : _readVisibleScreen,
                          child: const Text('Read visible screen'),
                        ),
                        Wrap(
                          spacing: 8,
                          children: [
                            OutlinedButton(
                              onPressed: !_enabled
                                  ? null
                                  : () => _performVisibleScreenAction('back'),
                              child: const Text('Back'),
                            ),
                            OutlinedButton(
                              onPressed: !_enabled
                                  ? null
                                  : () => _performVisibleScreenAction(
                                        'scroll_backward',
                                      ),
                              child: const Text('Scroll up'),
                            ),
                            OutlinedButton(
                              onPressed: !_enabled
                                  ? null
                                  : () => _performVisibleScreenAction(
                                        'scroll_forward',
                                      ),
                              child: const Text('Scroll down'),
                            ),
                          ],
                        ),
                        TextField(
                          controller: _screenTarget,
                          enabled: _enabled,
                          onChanged: (_) => setState(() {}),
                          decoration: const InputDecoration(
                            labelText: 'Exact visible button label',
                          ),
                        ),
                        OutlinedButton(
                          onPressed: !_enabled || _screenTarget.text.trim().isEmpty
                              ? null
                              : _clickVisibleScreenLabel,
                          child: const Text('Review and click'),
                        ),
                        if (_screenAssistError.isNotEmpty)
                          Text(
                            _screenAssistError,
                            style: TextStyle(
                              color: Theme.of(context).colorScheme.error,
                            ),
                          ),
                        if (_screenVisibleText.isNotEmpty) ...[
                          const Text(
                            'Visible text (not sent to Gemini):',
                            style: TextStyle(fontWeight: FontWeight.bold),
                          ),
                          SelectableText(_screenVisibleText),
                        ],
                        TextButton(
                          onPressed: !_enabled
                              ? null
                              : () async {
                                  try {
                                    await JivaAccessibility.openSettings();
                                  } catch (error) {
                                    if (mounted) {
                                      setState(
                                        () => _screenAssistError = '$error',
                                      );
                                    }
                                  }
                                },
                          child: Text(
                            _screenAccessibilityEnabled
                                ? 'Manage Android Accessibility Settings'
                                : 'Open Android Accessibility Settings',
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
              const SizedBox(height: 18),
              Text('Quick actions', style: Theme.of(context).textTheme.titleLarge),
              Wrap(
                spacing: 8,
                children: [
                  OutlinedButton(
                    onPressed: !_enabled ? null : () => _showPlan(const IntentPlan(
                      action: AssistantAction.openApp,
                      reply: 'Choose an installed app.',
                    )),
                    child: const Text('Apps'),
                  ),
                  OutlinedButton(
                    onPressed: _enabled ? _editReminder : null,
                    child: const Text('Reminders'),
                  ),
                  OutlinedButton(
                    onPressed: !_enabled ? null : () => _showPlan(const IntentPlan(
                      action: AssistantAction.openSettings,
                      reply: 'Open settings?',
                    )),
                    child: const Text('Settings'),
                  ),
                  OutlinedButton(
                    onPressed: !_enabled ? null : () => _showPlan(const IntentPlan(
                      action: AssistantAction.call,
                      reply: 'Choose a number to call.',
                    )),
                    child: const Text('Call'),
                  ),
                  OutlinedButton(
                    onPressed: !_enabled ? null : () => _showPlan(const IntentPlan(
                      action: AssistantAction.sms,
                      reply: 'Choose a number and review a message.',
                    )),
                    child: const Text('Message'),
                  ),
                  OutlinedButton(
                    onPressed: !_enabled ? null : () => _showPlan(const IntentPlan(
                      action: AssistantAction.camera,
                      reply: 'Open camera?',
                    )),
                    child: const Text('Camera'),
                  ),
                ],
              ),
              const SizedBox(height: 18),
              Text(
                'Upcoming reminders',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              if (_reminders.isEmpty) const Text('No reminders scheduled.'),
              for (final item in _reminders)
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: const Icon(Icons.alarm),
                  title: Text(item.text),
                  subtitle: Text(item.triggerAt.toLocal().toString()),
                ),
              const SizedBox(height: 12),
              Text(
                "Speech languages depend on your phone's speech service. "
                'Gemini requests send text to Google using your key. '
                'Sensitive actions require your review.',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
        ),
        floatingActionButton: _pendingPlan == null
            ? null
            : FloatingActionButton.extended(
                onPressed: () => _showPlan(_pendingPlan!),
                icon: const Icon(Icons.verified_user_outlined),
                label: const Text('Review action'),
              ),
      );
}
