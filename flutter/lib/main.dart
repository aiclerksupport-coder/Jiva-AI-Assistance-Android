import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'dart:io';

import 'package:app_settings/app_settings.dart';
import 'package:audioplayers/audioplayers.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'domain/wake_event_policy.dart';
import 'ui/jiva_theme.dart';
import 'ui/jiva_widgets.dart';
import 'ui/jiva_about_screen.dart';
import 'ui/jiva_splash.dart';
import 'domain/app_launch_match.dart';
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
import 'domain/phone_workflow.dart';
import 'domain/offline_phone_planner.dart';
import 'services/workflow_checkpoint_store.dart';
import 'domain/assistant_models.dart';
import 'domain/local_command_parser.dart';
import 'services/api_key_store.dart';
import 'services/owner_key_authorization.dart';
import 'services/gemini_repository.dart';
import 'services/gemini_live_client.dart';
import 'services/live_audio_io.dart';
import 'services/live_audio_transport.dart';
import 'services/live_conversation_session.dart';
import 'services/gemini_retry_policy.dart';
import 'services/reminder_service.dart';
import 'services/on_device_llm.dart';
import 'services/native_background_scheduler.dart';
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
    theme: JivaTheme.light,
    darkTheme: JivaTheme.dark,
    themeMode: ThemeMode.system,
    home: const JivaSplashGate(destination: AssistantScreen()),
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
  final _workflowTaskInput = TextEditingController();
  PhoneWorkflowSession? _phoneWorkflow;
  final _offlinePhonePlanner = const OfflinePhonePlanner();
  late final _workflowStore = WorkflowCheckpointStore(_secureStorage);
  bool _workflowActionDispatching = false;
  bool _autoRunSafeSteps = false;
  bool _workflowRecovered = false;
  bool _workflowPlanning = false;
  final _parser = LocalCommandParser();
  final _keyStore = ApiKeyStore(_secureStorage);
  final _ownerKeyAuth = OwnerKeyAuthorization();
  late GeminiRepository _gemini = GeminiRepository();
  final _geminiLive = GeminiLiveClient();
  LiveConversationSession? _liveConversation;
  bool _liveStarting = false;
  bool _liveActive = false;
  String _liveModel = "";
  final _reminderService = ReminderService(_secureStorage);
  final _offlineLlm = OnDeviceLlm();
  final _backgroundScheduler = NativeBackgroundScheduler();
  bool _offlineModelBusy = false;
  bool _offlineModelLoaded = false;
  bool _maintenanceScheduled = false;
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
  bool _planDialogOpen = false;
  bool _reminderDialogOpen = false;
  bool _keySettingsOpen = false;
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
  int _wakeGeneration = 0;
  bool _wakeGreetingInProgress = false;
  bool _wakeManagerStarted = false;
  bool _screenAccessibilityEnabled = false;
  bool _floatingControlsEnabled = false;
  bool _floatingControlsBusy = false;
  bool _screenAssistPending = false;
  bool _noiseProcessingEnabled = true;
  bool _patientListeningEnabled = true;
  String _audioProcessingStatus =
      'Microphone support is checked when listening starts.';
  Future<void>? _wakeManagerPause;
  String _wakeWordError = '';
  String _screenAssistError = '';
  String _screenVisibleText = '';
  Uint8List? _reviewedScreenJpeg;
  String _reviewedScreenPackage = '';
  bool _screenVisionBusy = false;
  String _screenVisionAnswer = '';

  Map<String, String> _wakeWordModels = {};
  Timer? _idleTimer;
  IntentPlan? _pendingPlan;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    if (Platform.isAndroid) {
      JivaAccessibility.setScreenshotHandler((jpeg, packageName) {
        if (!mounted || !_enabled) {
          return;
        }
        setState(() {
          _reviewedScreenJpeg = jpeg;
          _reviewedScreenPackage = packageName;
          _screenAssistPending = false;
          _screenAssistError =
              'Screenshot captured locally for review. Not uploaded to AI.';
        });
      });
      JivaAccessibility.setResultHandler((type, text, message, operationId) {
        if (!mounted) {
          return;
        }
        // Never let an old native reply clear the active workflow's busy state.
        if (_phoneWorkflow?.awaitingResult == true &&
            operationId != _phoneWorkflow?.operationId) {
          return;
        }
        setState(() {
          _screenAssistPending = false;
          _onWorkflowNativeResult(type, message, operationId);
          if (type == 'read') {
            _screenVisibleText = text.join('\n');
          }
          _screenAssistError = message;
        });
        _resetSleepTimer();
      });
      JivaSpeech.setCallbacks(
        onResult: (text) {
          if (!mounted) {
            return;
          }
          setState(() => _listening = false);
          if (text.trim().isNotEmpty) {
            _submit(text);
          }
        },
        onPartialResult: (text) {
          if (mounted) {
            setState(() => _transcript = text);
          }
        },
        onError: (message) => _showReply(message),
        onFallback: (message) {
          if (!mounted) {
            return;
          }
          setState(() {
            _audioProcessingStatus = message;
            _listening = true;
          });
          _idleTimer?.cancel();
          unawaited(_listenWithSpeechToText());
        },
        onStatus: (status) {
          if (!mounted) {
            return;
          }
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
          if (mounted) {
            setState(() => _audioProcessingStatus = status);
          }
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
      androidNotificationOptions: AndroidNotificationOptions(
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
    if (!_isForeground && _liveConversation != null) {
      final live = _liveConversation;
      _liveConversation = null;
      _liveActive = false;
      if (live != null) {
        unawaited(live.stop());
      }
    }
    if (_isForeground) {
      if (Platform.isAndroid) {
        unawaited(_refreshAccessibilityStatus());
      }
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
    if (_sleeping && mounted) {
      setState(() => _sleeping = false);
    }
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
      final liveModel =
          await _secureStorage.read(key: 'jiva_live_model_id') ?? '';
      final enabled = _userToggledAssistant ? _enabled : storedEnabled;
      final selectedVoice =
          await _secureStorage.read(key: 'jiva_selected_voice') ??
          defaultVoiceName;
      final noiseProcessingEnabled = await _keyStore
          .readNoiseProcessingEnabled();
      final patientListeningEnabled = await _keyStore
          .readPatientListeningEnabled();
      final wakeModels = await _keyStore.readWakeWordModelPaths();
      final wakeActive =
          Platform.isAndroid && await FlutterForegroundTask.isRunningService;
      final screenAccessibilityEnabled =
          Platform.isAndroid && await JivaAccessibility.isEnabled();
      if (Platform.isAndroid) {
        await JivaAccessibility.setJivaEnabled(enabled);
      }
      if (!mounted) {
        return;
      }
      setState(() {
        _reminders
          ..clear()
          ..addAll(reminders);
        _hasApiKey = hasKey;
        _enabled = enabled;
        _selectedVoice = voicePresetFor(selectedVoice).name;
        _liveModel = liveModel;
        _noiseProcessingEnabled = noiseProcessingEnabled;
        _patientListeningEnabled = patientListeningEnabled;
        _wakeWordModels = wakeModels;
        _wakeWordActive = wakeActive && enabled;
        _screenAccessibilityEnabled = screenAccessibilityEnabled;
        if (_reminderService.restoreWarning != null) {
          _reply = _reminderService.restoreWarning!;
        }
      });
      try {
        final recovered = await _workflowStore.load();
        if (mounted && recovered != null) {
          setState(() {
            _phoneWorkflow = recovered;
            _workflowRecovered = true;
          });
        }
      } catch (error) {
        if (mounted) {
          setState(
            () => _screenAssistError =
                'Saved task could not be recovered. No phone action was executed: $error',
          );
        }
      }
      if (wakeActive && enabled) {
        unawaited(_startBackgroundWakeWord(startService: false));
      } else if (wakeActive) {
        await FlutterForegroundTask.stopService();
      }
    } catch (error) {
      _showReply('Could not initialize reminders: $error');
    }
  }

  Future<void> _toggleLiveConversation() async {
    if (_liveStarting) {
      return;
    }
    if (_liveActive) {
      final previous = _liveConversation;
      setState(() {
        _liveConversation = null;
        _liveActive = false;
      });
      if (previous != null) {
        await previous.stop();
      }
      await _resumeWakeWordManager();
      return;
    }
    if (!_enabled || !_isForeground || _busy || _listening) {
      return;
    }
    if (_liveModel.trim().isEmpty) {
      final controller = TextEditingController();
      final proposed = await showDialog<String>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Gemini Live model ID'),
          content: TextField(
            controller: controller,
            decoration: const InputDecoration(
              hintText: 'Enter currently supported native audio model',
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, controller.text.trim()),
              child: const Text('Use model'),
            ),
          ],
        ),
      );
      controller.dispose();
      if (proposed == null ||
          !RegExp(r'^[A-Za-z0-9._-]{1,100}$').hasMatch(proposed) ||
          !mounted) {
        return;
      }
      await _secureStorage.write(key: 'jiva_live_model_id', value: proposed);
      if (!mounted) {
        return;
      }
      setState(() => _liveModel = proposed);
    }
    setState(() => _liveStarting = true);
    LiveConversationSession? candidate;
    try {
      await _pauseWakeWordManager();
      await _speech.stop();
      await _tts.stop();
      await _audioPlayer.stop();
      final key = await _keyStore.read();
      if (key.isEmpty) {
        throw StateError('Add Gemini API key first');
      }
      candidate = LiveConversationSession(
        LiveAudioTransport(
          apiKey: key,
          // Configured by owner; no potentially retired hardcoded preview model.
          model: _liveModel,
          voiceName: _selectedVoice,
        ),
        LiveAudioIo(),
      );
      await candidate.start(
        onText: (text) {
          if (mounted) {
            setState(() => _transcript = text);
          }
        },
        onError: (message) {
          if (mounted) {
            setState(() => _audioProcessingStatus = message);
          }
        },
      );
      if (!mounted || !_enabled || !_isForeground) {
        await candidate.stop();
        return;
      }
      setState(() {
        _liveConversation = candidate;
        _liveActive = true;
      });
    } catch (error) {
      await candidate?.stop();
      if (mounted) {
        setState(
          () => _audioProcessingStatus = 'Live voice could not start: $error',
        );
      }
      await _resumeWakeWordManager();
    } finally {
      if (mounted) {
        setState(() => _liveStarting = false);
      }
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _idleTimer?.cancel();
    _reviewedScreenJpeg = null;
    _reviewedScreenPackage = '';
    if (Platform.isAndroid) {
      FlutterForegroundTask.removeTaskDataCallback(_onWakeTaskData);
      JivaAccessibility.clearResultHandler();
      JivaSpeech.clearCallbacks();
      unawaited(JivaSpeech.stop());
    }
    _input.dispose();
    _screenTarget.dispose();
    _workflowTaskInput.dispose();
    if (_offlineLlm.isLoaded) {
      unawaited(_offlineLlm.dispose().catchError((Object _) {}));
    }
    final live = _liveConversation;
    if (live != null) {
      unawaited(live.stop());
    }
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
    if (text.isEmpty || _busy || !_enabled) {
      return;
    }
    // Spoken natural-language phone tasks use the same review/approval gates
    // as typed tasks. Never silently start accessibility actions from voice.
    final phoneMatch = RegExp(
      r'^(?:jiva\s+)?(?:phone task|phone par|mobile par|mobile task)\s*[:,-]?\s*(.+)$',
      caseSensitive: false,
    ).firstMatch(text);
    if (phoneMatch != null && !_workflowPlanning && _phoneWorkflow == null) {
      _workflowTaskInput.text = phoneMatch.group(1)!.trim();
      await _planPhoneTask();
      return;
    }
    // Reserve the command synchronously before the first await: voice and taps
    // can otherwise both enter while the microphone is being paused.
    setState(() {
      _busy = true;
      _sleeping = false;
      _pendingPlan = null;
    });
    _idleTimer?.cancel();
    try {
      await _pauseWakeWordManager();
    } catch (error) {
      if (mounted) {
        setState(() => _busy = false);
      }
      _showReply('Could not pause wake-word microphone: $error');
      return;
    }
    if (!mounted || !_enabled) {
      if (mounted) {
        setState(() => _busy = false);
      }
      return;
    }
    setState(() {
      _input.clear();
      _transcript = text;
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
        if (_pendingPlan == null) {
          await _resumeWakeWordManager();
        }
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
      if (!_enabled || !mounted) {
        return;
      }
      final plan = _planFromJson(json);
      if (!mounted) {
        return;
      }
      setState(() {
        _reply = plan.reply;
        _pendingPlan = plan.action == AssistantAction.chat ? null : plan;
      });
      await _speakReply(plan.reply);
      if (mounted && _enabled) {
        setState(() => _busy = false);
        _resetSleepTimer();
        if (_pendingPlan == null) {
          await _resumeWakeWordManager();
        }
      }
    } catch (error) {
      _showReply(error.toString().replaceFirst('Exception: ', ''));
    }
  }

  Future<void> _speakReply(String text) async {
    try {
      if (!_enabled) {
        return;
      }
      final key = await _keyStore.read();
      if (!_enabled) {
        return;
      }
      if (key.isEmpty) {
        // By default flutter_tts may complete as soon as playback starts.
        // Do not open speech recognition until the greeting has finished.
        await _tts.awaitSpeakCompletion(true);
        await _tts.speak(text).timeout(const Duration(seconds: 12));
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
      if (!_enabled) {
        return;
      }
      await _tts.stop();
      await _audioPlayer.stop();
      final finished = Completer<void>();
      var playbackStarted = false;
      final stateSubscription = _audioPlayer.onPlayerStateChanged.listen((
        state,
      ) {
        if (state == PlayerState.playing) {
          playbackStarted = true;
        }
        if (playbackStarted &&
            (state == PlayerState.completed || state == PlayerState.stopped) &&
            !finished.isCompleted) {
          finished.complete();
        }
      });
      try {
        await _audioPlayer.play(BytesSource(response.toWav()));
        await finished.future.timeout(const Duration(seconds: 90));
      } finally {
        await stateSubscription.cancel();
      }
    } catch (error) {
      // A timed-out TTS utterance must not continue into mic capture.
      try {
        await _tts.stop();
      } catch (_) {
        // The speech provider may already be gone.
      }
      if (mounted && _enabled) {
        setState(() {
          _reply = '$_reply\nVoice response failed: $error';
        });
      }
    }
  }

  Future<void> _startBackgroundWakeWord({bool startService = true}) async {
    if (!_enabled) {
      return;
    }
    // Android 14+ cannot create a new microphone foreground service from a
    // normal background state. Start it while the activity is visible, then
    // let the already-running user-visible service continue when backgrounded.
    if (startService && !_isForeground) {
      if (mounted) {
        setState(
          () => _wakeWordError =
              'Open Jiva before starting microphone background listening.',
        );
      }
      return;
    }
    if (!Platform.isAndroid) {
      _showReply(
        'Background wake-word listening is available only in the Android app. '
        'iOS voice input is foreground-only.',
      );
      return;
    }
    if (_porcupineManager != null) {
      return;
    }
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
          throw StateError(
            'A saved wake-word model file is missing. Import it again.',
          );
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
        throw StateError(
          'Microphone permission or speech service is unavailable.',
        );
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
          throw StateError(
            'Android did not start the microphone foreground service.',
          );
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
      if (!mounted) {
        return;
      }
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
    _wakeWordPending = false;
    _wakeGeneration++;
    if (_wakeGreetingInProgress) {
      _wakeGreetingInProgress = false;
      if (mounted) {
        setState(() => _busy = false);
      }
    }
    try {
      if (Platform.isAndroid && await FlutterForegroundTask.isRunningService) {
        await FlutterForegroundTask.stopService();
        if (await FlutterForegroundTask.isRunningService) {
          throw StateError(
            'Android did not stop the microphone foreground service.',
          );
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
    if (pendingPause != null) {
      return pendingPause;
    }
    final manager = _porcupineManager;
    if (manager == null || !_wakeManagerStarted) {
      return Future<void>.value();
    }
    final pause = manager
        .stop()
        .then((_) {
          _wakeManagerStarted = false;
        })
        .whenComplete(() => _wakeManagerPause = null);
    _wakeManagerPause = pause;
    return pause;
  }

  Future<void> _resumeWakeWordManager() async {
    if (!_enabled ||
        !_wakeWordActive ||
        _porcupineManager == null ||
        _wakeManagerStarted ||
        _wakeWordPending ||
        _wakeManagerPause != null ||
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
    // One wake event per microphone handoff. Ignore callback storms and
    // detections arriving while a command or another capture is active.
    if (!WakeEventPolicy.shouldQueue(
      enabled: _enabled,
      active: _wakeWordActive,
      pending: _wakeWordPending,
      busy: _busy,
      listening: _listening,
      hasActionPlan: _pendingPlan != null,
    )) {
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
    if (_isForeground) {
      unawaited(_handleWakeWord());
    }
  }

  void _onWakeWordError(PorcupineException error) {
    _wakeWordError = error.message ?? 'Unknown wake-word error';
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
    if (!mounted ||
        !_enabled ||
        !_isForeground ||
        !_wakeWordPending ||
        _busy ||
        _listening ||
        _pendingPlan != null) {
      return;
    }
    _wakeWordPending = false;
    final generation = ++_wakeGeneration;
    _wakeGreetingInProgress = true;
    // Claim the UI before awaiting microphone release. Two foreground
    // callbacks can never start two simultaneous greetings/captures.
    setState(() {
      _sleeping = false;
      _busy = true;
      _reply = 'Ji, boliye';
    });
    _idleTimer?.cancel();
    try {
      await _pauseWakeWordManager();
      if (!mounted || !_enabled || generation != _wakeGeneration) {
        return;
      }
      await _speakReply('Ji, boliye');
      if (!mounted || !_enabled || generation != _wakeGeneration) {
        return;
      }
      _wakeGreetingInProgress = false;
      setState(() => _busy = false);
      await _listen();
    } catch (error) {
      _showReply('Wake-word handoff failed: $error');
    } finally {
      if (generation == _wakeGeneration && _wakeGreetingInProgress) {
        _wakeGreetingInProgress = false;
        if (mounted && _busy) {
          setState(() => _busy = false);
        }
      }
    }
  }

  IntentPlan _planFromJson(Map<String, dynamic> json) {
    String? bounded(String key, int maxLength) {
      final value = json[key];
      if (value is! String || value.trim().isEmpty) {
        return null;
      }
      final trimmed = value.trim();
      return trimmed.length > maxLength
          ? trimmed.substring(0, maxLength)
          : trimmed;
    }

    final settingsTarget = bounded('settingsTarget', 20)?.toLowerCase();
    final phoneAction = bounded('phoneAction', 30)?.toLowerCase();
    final requestedAction = actionFromName(json['action'] as String?);
    return IntentPlan(
      action:
          requestedAction == AssistantAction.phoneNavigation &&
              !supportedPhoneNavigationActions.contains(phoneAction)
          ? AssistantAction.chat
          : requestedAction,
      reply: bounded('reply', 2000) ?? 'What would you like Jiva to do?',
      appName: bounded('appName', 100),
      recipientQuery: bounded('recipientQuery', 100),
      message: bounded('message', 2000),
      settingsTarget:
          const {
            'general',
            'wifi',
            'bluetooth',
            'display',
            'sound',
          }.contains(settingsTarget)
          ? settingsTarget
          : 'general',
      phoneAction: phoneAction,
    );
  }

  Future<void> _listen() async {
    if (!mounted || !_enabled || _listening) {
      return;
    }
    setState(() => _listening = true);
    _idleTimer?.cancel();
    try {
      await _pauseWakeWordManager();
      if (!_speech.isAvailable) {
        final available = await _speech.initialize(
          onStatus: (status) {
            if (!mounted) {
              return;
            }
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
          if (mounted) {
            setState(() => _listening = true);
          }
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
      if (mounted) {
        setState(() => _listening = false);
      }
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
        listenOptions: SpeechListenOptions(
          listenFor: _patientListeningEnabled
              ? const Duration(seconds: 60)
              : const Duration(seconds: 30),
          pauseFor: _patientListeningEnabled
              ? const Duration(seconds: 6)
              : const Duration(seconds: 3),
        ),
      );
    } catch (error) {
      _showReply('Could not start speech recognition: $error');
    }
  }

  Future<void> _stop() async {
    // Invalidate in-flight greeting so Stop cannot open the mic afterwards.
    _wakeGeneration++;
    _wakeWordPending = false;
    if (_wakeGreetingInProgress) {
      _wakeGreetingInProgress = false;
      if (mounted) {
        setState(() => _busy = false);
      }
    }
    if (Platform.isAndroid) {
      await JivaSpeech.stop();
    }
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
      if (mounted) {
        setState(() => _noiseProcessingEnabled = enabled);
      }
    } catch (error) {
      _showReply('Could not save microphone processing setting: $error');
    }
  }

  Future<void> _setPatientListeningEnabled(bool enabled) async {
    try {
      await _keyStore.savePatientListeningEnabled(enabled);
      if (mounted) {
        setState(() => _patientListeningEnabled = enabled);
      }
    } catch (error) {
      _showReply('Could not save patient listening setting: $error');
    }
  }

  Future<void> _setAssistantEnabled(bool enabled) async {
    if (_enabled == enabled) {
      return;
    }
    _userToggledAssistant = true;
    if (!enabled) {
      _wakeGeneration++;
      _wakeWordPending = false;
      _wakeGreetingInProgress = false;
    }
    _idleTimer?.cancel();
    if (!enabled && Platform.isAndroid) {
      await JivaSpeech.stop();
    }
    setState(() {
      _enabled = enabled;
      _busy = false;
      _listening = false;
      _screenAssistPending = false;
      if (!enabled) {
        _reviewedScreenJpeg = null;
        _reviewedScreenPackage = '';
        _screenVisionAnswer = '';
      }
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
        setState(
          () => _reply = 'Jiva is off, but cleanup reported: $cleanupError',
        );
      }
    } else {
      _gemini = GeminiRepository();
      _resetSleepTimer();
    }
  }

  Future<void> _explainReviewedScreenshot() async {
    final jpeg = _reviewedScreenJpeg;
    if (jpeg == null ||
        jpeg.isEmpty ||
        _screenVisionBusy ||
        !_enabled ||
        _phoneWorkflow != null ||
        _screenAssistPending) {
      return;
    }
    final key = await _keyStore.read();
    if (!mounted) {
      return;
    }
    if (key.isEmpty) {
      setState(
        () => _screenAssistError = 'Configure your own Gemini key first.',
      );
      return;
    }
    final approved = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Share this screenshot with Gemini?'),
        content: Text(
          'A single screenshot from $_reviewedScreenPackage will be sent to '
          'Google Gemini to describe visible controls. It might contain private '
          'information. Never share banking, messages, credentials, OTPs, '
          'payments, medical records, or personal accounts. '
          'Jiva does not send it automatically and will not execute actions.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Keep local'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Share once'),
          ),
        ],
      ),
    );
    if (approved != true ||
        !mounted ||
        !_enabled ||
        !identical(jpeg, _reviewedScreenJpeg)) {
      return;
    }
    setState(() {
      _screenVisionBusy = true;
      _screenVisionAnswer = '';
    });
    try {
      final reply = await _gemini.explainReviewedScreen(
        key,
        jpeg,
        'Describe the visible screen and its main controls in simple Hinglish. '
        'Mention uncertainty; do not execute actions.',
      );
      if (mounted && _enabled && identical(jpeg, _reviewedScreenJpeg)) {
        setState(() => _screenVisionAnswer = reply);
      }
    } catch (error) {
      if (mounted) {
        setState(() => _screenAssistError = 'Visual analysis: $error');
      }
    } finally {
      if (mounted) {
        setState(() => _screenVisionBusy = false);
      }
    }
  }

  Future<void> _captureReviewedScreenshot() async {
    if (!_enabled ||
        !Platform.isAndroid ||
        _screenAssistPending ||
        _phoneWorkflow != null) {
      return;
    }
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Capture one target-app screenshot?'),
        content: const Text(
          'Jiva will capture a single Android 11+ screenshot after you switch to the target app. '
          'It stays in Jiva memory for local preview, is not uploaded to an AI model, '
          'and disappears when you clear it or close the app. '
          'Avoid banking, credentials, private conversations and payment screens.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Capture once'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted || !_enabled) {
      return;
    }
    try {
      if (!await JivaAccessibility.isEnabled()) {
        await JivaAccessibility.openSettings();
        if (mounted) {
          setState(
            () => _screenAssistError = 'Enable accessibility and retry.',
          );
        }
        return;
      }
      await _pauseWakeWordManager();
      await JivaAccessibility.requestReviewedScreenshot();
      if (mounted) {
        setState(() {
          _reviewedScreenJpeg = null;
          _reviewedScreenPackage = '';
          _screenAssistPending = true;
          _screenAssistError =
              'Switch to target app within 30 seconds for one screenshot.';
        });
      }
    } catch (error) {
      if (mounted) {
        setState(() => _screenAssistError = '$error');
      }
    }
  }

  Future<void> _readVisibleScreen() async {
    if (!_enabled || !Platform.isAndroid) {
      return;
    }
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
    if (confirmed != true || !mounted || !_enabled) {
      return;
    }

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
      if (mounted) {
        setState(() => _screenAssistError = '$error');
      }
    }
  }

  void _onWorkflowNativeResult(
    String type,
    String message,
    String operationId,
  ) {
    final session = _phoneWorkflow;
    if (session == null || !session.awaitingResult) {
      return;
    }
    final expected = switch (session.nextStep?.kind) {
      PhoneStepKind.read => 'read',
      PhoneStepKind.click => 'click',
      PhoneStepKind.typeText => 'type',
      PhoneStepKind.scrollForward => 'scroll_forward',
      PhoneStepKind.scrollBackward => 'scroll_backward',
      PhoneStepKind.back => 'back',
      PhoneStepKind.openApp => 'open_app',
      PhoneStepKind.verifyText => 'verify_text',
      null => '',
    };
    if (type != expected ||
        session.operationId == null ||
        operationId != session.operationId) {
      return;
    }
    // Never infer success from a generic result or timeout.
    // performAction(true) alone is not enough: require post-action proof.
    final success =
        (type == 'read' && message == 'Screen read locally.') ||
        (type == 'open_app' && message == 'Verified reviewed app opened.') ||
        (type == 'verify_text' &&
            message == 'Verified final goal on target screen.') ||
        (type != 'read' &&
            type != 'open_app' &&
            type != 'verify_text' &&
            message == 'Verified target screen changed.');
    session.acceptResult(
      success: success,
      status: message,
      resultOperationId: operationId,
    );
    if (!success) {
      _autoRunSafeSteps = false;
    }
    // Keep dispatch locked until the post-result checkpoint is durable.
    unawaited(
      _workflowStore
          .save(session)
          .then((_) {
            if (identical(session, _phoneWorkflow) && mounted) {
              setState(() => _workflowActionDispatching = false);
              if (_autoRunSafeSteps &&
                  success &&
                  !session.isComplete &&
                  !session.cancelled &&
                  !session.uncertainAfterRestart) {
                final next = session.nextStep;
                if (next != null && !next.needsExtraApproval) {
                  unawaited(_runNextWorkflowStep(autoApproved: true));
                } else {
                  // Preserve reviewed auto-run consent, but never dispatch a sensitive
                  // transition until the user explicitly approves that step.
                  _screenAssistError =
                      'Review the next sensitive action in Jiva to continue.';
                }
              } else if (session.isComplete) {
                _autoRunSafeSteps = false;
              }
            }
          })
          .catchError((Object error) {
            session.uncertainAfterRestart = true;
            _autoRunSafeSteps = false;
            if (mounted) {
              setState(() {
                _workflowActionDispatching = false;
                _screenAssistError =
                    'Task checkpoint failed; cancel workflow before further actions: $error';
              });
            }
          }),
    );
  }

  Future<void> _importOfflineModel() async {
    if (_offlineModelBusy || _offlineModelLoaded || !mounted) {
      return;
    }
    final file = await FilePicker.pickFiles(
      type: FileType.custom,
      allowedExtensions: ['gguf'],
      withData: false,
    );
    if (file == null || file.files.single.path == null || !mounted) {
      return;
    }
    setState(() => _offlineModelBusy = true);
    try {
      final sha = await _offlineLlm.importModel(file.files.single.path!);
      if (mounted) {
        _showReply(
          'Offline model imported. SHA-256: $sha. Tap Load offline AI.',
        );
      }
    } catch (error) {
      if (mounted) {
        _showReply('Offline import failed: $error');
      }
    } finally {
      if (mounted) {
        setState(() => _offlineModelBusy = false);
      }
    }
  }

  Future<void> _toggleOfflineModel() async {
    if (_offlineModelBusy || !mounted) {
      return;
    }
    setState(() => _offlineModelBusy = true);
    try {
      if (_offlineModelLoaded) {
        await _offlineLlm.dispose();
      } else {
        await _offlineLlm.load();
      }
      if (mounted) {
        setState(() => _offlineModelLoaded = _offlineLlm.isLoaded);
      }
    } catch (error) {
      if (mounted) {
        _showReply('Offline model error: $error');
      }
    } finally {
      if (mounted) {
        setState(() => _offlineModelBusy = false);
      }
    }
  }

  Future<void> _askOfflineModel() async {
    if (!_offlineModelLoaded || _offlineModelBusy || !mounted) {
      return;
    }
    final prompt = _input.text.trim();
    if (prompt.isEmpty) {
      _showReply('Type a question first.');
      return;
    }
    setState(() => _offlineModelBusy = true);
    try {
      final response = await _offlineLlm.answer(prompt);
      if (mounted) {
        _showReply('Offline AI: $response');
      }
    } catch (error) {
      if (mounted) {
        _showReply('Offline AI failed: $error');
      }
    } finally {
      if (mounted) {
        setState(() => _offlineModelBusy = false);
      }
    }
  }

  Future<void> _toggleSafeMaintenance() async {
    try {
      final status = _maintenanceScheduled
          ? await _backgroundScheduler.disableSafeMaintenance()
          : await _backgroundScheduler.enableSafeMaintenance();
      if (!mounted) {
        return;
      }
      if (status) {
        setState(() => _maintenanceScheduled = !_maintenanceScheduled);
      }
      _showReply(
        _maintenanceScheduled
            ? 'OS-managed safe maintenance scheduled; timing not guaranteed.'
            : 'Safe maintenance disabled.',
      );
    } catch (error) {
      if (mounted) {
        _showReply('Maintenance scheduling unavailable: $error');
      }
    }
  }

  Future<void> _planPhoneTask() async {
    if (!mounted ||
        !_enabled ||
        _busy ||
        _workflowPlanning ||
        _screenAssistPending ||
        _phoneWorkflow != null) {
      return;
    }
    final task = _workflowTaskInput.text.trim();
    if (task.isEmpty) {
      return;
    }
    setState(() => _workflowPlanning = true);
    try {
      final key = await _keyStore.read();
      PhoneWorkflow workflow;
      if (_offlineModelLoaded) {
        // Local output goes through exactly the same strict allowlist/approval.
        try {
          workflow = PhoneWorkflow.fromJson(
            jsonDecode(await _offlineLlm.proposeWorkflow(task)),
          );
        } catch (_) {
          workflow =
              _offlinePhonePlanner.plan(task) ??
              (throw StateError(
                'Offline model could not produce a safe plan.',
              ));
        }
      } else if (key.isEmpty) {
        workflow =
            _offlinePhonePlanner.plan(task) ??
            (throw StateError(
              'Load local model, or add a key for AI planning.',
            ));
      } else {
        try {
          final draft = await _gemini.planPhoneWorkflow(key, task);
          workflow = PhoneWorkflow.fromJson(draft['steps']);
        } catch (_) {
          workflow =
              _offlinePhonePlanner.plan(task) ??
              (throw StateError(
                'AI offline. Only simple read/back/scroll commands work without cloud.',
              ));
        }
      }
      final session = PhoneWorkflowSession(workflow);
      await _workflowStore.save(session);
      if (!mounted || !_enabled) {
        return;
      }
      setState(() {
        _phoneWorkflow = session;
        _autoRunSafeSteps = false;
        _workflowRecovered = false;
      });
    } catch (error) {
      if (mounted) {
        setState(() => _screenAssistError = 'Workflow planner: $error');
      }
    } finally {
      if (mounted) {
        setState(() => _workflowPlanning = false);
      }
    }
  }

  Future<void> _startAutoSafeWorkflow() async {
    final session = _phoneWorkflow;
    if (session == null ||
        session.isComplete ||
        session.uncertainAfterRestart ||
        session.awaitingResult ||
        _workflowActionDispatching) {
      return;
    }
    final approved = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Run safe steps automatically?'),
        content: const Text(
          'Jiva will execute only the reviewed plan, up to 8 steps, '
          'across only the reviewed apps. Each transition verifies the exact Android '
          'package. Every action must be checked before continuing. '
          'Reviewed app switches are automatic; sensitive clicks and typing require approval. '
          'Stop anytime using Jiva floating control. This is not a background '
          'force-stop bypass.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Start safe-run'),
          ),
        ],
      ),
    );
    if (approved != true || !mounted || !identical(session, _phoneWorkflow)) {
      return;
    }
    setState(() => _autoRunSafeSteps = true);
    if (session.nextStep?.needsExtraApproval == true) {
      setState(() => _autoRunSafeSteps = false);
      return;
    }
    await _runNextWorkflowStep(autoApproved: true);
  }

  Future<void> _runNextWorkflowStep({bool autoApproved = false}) async {
    final session = _phoneWorkflow;
    final step = session?.nextStep;
    if (session == null ||
        step == null ||
        session.awaitingResult ||
        _screenAssistPending ||
        _workflowActionDispatching ||
        session.uncertainAfterRestart ||
        !_enabled ||
        !mounted ||
        !Platform.isAndroid) {
      return;
    }
    final approved =
        autoApproved && _autoRunSafeSteps && !step.needsExtraApproval
        ? true
        : await showDialog<bool>(
            context: context,
            builder: (context) => AlertDialog(
              title: const Text('Approve this phone action?'),
              content: Text(
                '${step.description}\n\n'
                'Jiva will act once in the reviewed target app. '
                'Text entry is only allowed in exactly one focused non-password field. '
                'Do not use private, banking, login or payment screens. '
                'A successful action is verified by the Android service.',
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(context, false),
                  child: const Text('Cancel'),
                ),
                FilledButton(
                  onPressed: () => Navigator.pop(context, true),
                  child: const Text('Approve once'),
                ),
              ],
            ),
          );
    if (approved != true ||
        !mounted ||
        !identical(session, _phoneWorkflow) ||
        !_enabled) {
      return;
    }
    if (step.needsExtraApproval) {
      final approvedAgain = step.kind == PhoneStepKind.openApp
          ? true // reviewed plan + explicit first dialog already approved app transition
          : step.kind == PhoneStepKind.typeText
          ? (await showDialog<bool>(
                  context: context,
                  builder: (context) => AlertDialog(
                    title: const Text('Confirm text input'),
                    content: Text(
                      'Type the following exact text into the currently focused '
                      'non-password input?\n\n${step.label}\n\nDo not enter OTPs, credentials, '
                      'payment or private information.',
                    ),
                    actions: [
                      TextButton(
                        onPressed: () => Navigator.pop(context, false),
                        child: const Text('Cancel'),
                      ),
                      FilledButton(
                        onPressed: () => Navigator.pop(context, true),
                        child: const Text('Type once'),
                      ),
                    ],
                  ),
                ) ??
                false)
          : await _confirmVisibleClick(
              step.label,
              sensitive: true,
              finalStep: true,
            );
      if (!approvedAgain || !mounted || !identical(session, _phoneWorkflow)) {
        return;
      }
    }
    try {
      await _pauseWakeWordManager();
      if (!identical(session, _phoneWorkflow) || !_enabled) {
        return;
      }
      final enabled = await JivaAccessibility.isEnabled();
      if (!enabled) {
        throw StateError('Enable Android Accessibility first.');
      }
      // Reset target binding only for the first step of a new reviewed plan.
      if (session.index == 0) {
        await JivaAccessibility.cancelPendingScreenAction();
      }
      // Write-ahead checkpoint: process death can never cause a blind retry.
      _workflowActionDispatching = true;
      session.beginStep();
      try {
        await _workflowStore.save(session);
      } catch (_) {
        session.acceptResult(
          success: false,
          status: 'Could not safely checkpoint action.',
          resultOperationId: session.operationId,
        );
        _workflowActionDispatching = false;
        session.uncertainAfterRestart = true;
        rethrow;
      }
      if (!mounted || !identical(session, _phoneWorkflow)) {
        return;
      }
      setState(() {
        _screenAssistPending = true;
        _screenAssistError = 'Switch to the target app within 30 seconds.';
      });
      final operationId = session.operationId!;
      switch (step.kind) {
        case PhoneStepKind.openApp:
          final apps = await InstalledApps.getInstalledApps(
            excludeSystemApps: true,
            excludeNonLaunchableApps: true,
            detectPlatformType: false,
          );
          final appIndex = AppLaunchMatch.select(
            step.label,
            apps.map((app) => app.name).toList(growable: false),
          );
          if (appIndex == null) {
            throw StateError(
              'App name is missing/ambiguous. Review the installed app and re-plan.',
            );
          }
          await JivaAccessibility.launchReviewedApp(
            apps[appIndex].packageName,
            operationId: operationId,
          );
        case PhoneStepKind.verifyText:
          await JivaAccessibility.verifyVisibleGoal(
            step.label,
            operationId: operationId,
          );
        case PhoneStepKind.read:
          await JivaAccessibility.requestVisibleScreenRead(
            operationId: operationId,
          );
        case PhoneStepKind.click:
          await JivaAccessibility.queueVisibleTextClick(
            step.label,
            operationId: operationId,
          );
        case PhoneStepKind.typeText:
          await JivaAccessibility.queueFocusedTextInput(
            step.label,
            operationId: operationId,
          );
        case PhoneStepKind.scrollForward:
          await JivaAccessibility.queueScreenAction(
            'scroll_forward',
            operationId: operationId,
          );
        case PhoneStepKind.scrollBackward:
          await JivaAccessibility.queueScreenAction(
            'scroll_backward',
            operationId: operationId,
          );
        case PhoneStepKind.back:
          await JivaAccessibility.queueScreenAction(
            'back',
            operationId: operationId,
          );
      }
    } catch (error) {
      if (session.awaitingResult) {
        session.acceptResult(
          success: false,
          status: '$error',
          resultOperationId: session.operationId,
        );
      }
      _workflowActionDispatching = false;
      // A platform exception after dispatch is ambiguous: manual re-plan required.
      session.uncertainAfterRestart = true;
      unawaited(_workflowStore.save(session).catchError((Object _) {}));
      if (mounted) {
        setState(() {
          _screenAssistPending = false;
          _screenAssistError = '$error';
        });
      }
    }
  }

  void _cancelPhoneWorkflow() {
    _autoRunSafeSteps = false;
    setState(() {
      _phoneWorkflow?.cancel();
      _phoneWorkflow = null;
    });
    _screenAssistPending = false;
    _workflowActionDispatching = false;
    _workflowRecovered = false;
    unawaited(
      _workflowStore.clear().catchError((Object error) {
        if (mounted) {
          setState(
            () => _screenAssistError = 'Could not clear saved task: $error',
          );
        }
      }),
    );
    // Cancel the queued native action too, rather than allowing an old tap
    // after the user cancelled the workflow.
    unawaited(
      JivaAccessibility.cancelPendingScreenAction().catchError((Object _) {}),
    );
  }

  Future<void> _toggleFloatingControls() async {
    if (_floatingControlsBusy || !_enabled || !Platform.isAndroid) {
      return;
    }
    setState(() => _floatingControlsBusy = true);
    try {
      final current = await JivaAccessibility.areFloatingControlsEnabled();
      await JivaAccessibility.setFloatingControls(!current);
      if (mounted) {
        setState(() => _floatingControlsEnabled = !current);
      }
    } catch (error) {
      if (mounted) {
        setState(() => _screenAssistError = 'Floating controls: $error');
      }
    } finally {
      if (mounted) {
        setState(() => _floatingControlsBusy = false);
      }
    }
  }

  Future<void> _refreshAccessibilityStatus() async {
    try {
      final enabled = await JivaAccessibility.isEnabled();
      if (mounted) {
        setState(() => _screenAccessibilityEnabled = enabled);
      }
      if (enabled) {
        final floating = await JivaAccessibility.areFloatingControlsEnabled();
        if (mounted) {
          setState(() => _floatingControlsEnabled = floating);
        }
      } else if (mounted) {
        setState(() => _floatingControlsEnabled = false);
      }
    } catch (error) {
      if (mounted) {
        setState(() => _screenAssistError = '$error');
      }
    }
  }

  Future<void> _performVisibleScreenAction(String action) async {
    if (!_enabled || !Platform.isAndroid) {
      return;
    }
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
      if (mounted) {
        setState(() => _screenAssistError = '$error');
      }
    }
  }

  Future<void> _clickVisibleScreenLabel() async {
    final target = _screenTarget.text.trim();
    if (!_enabled || !Platform.isAndroid || target.isEmpty) {
      return;
    }
    final sensitive = RegExp(
      r'(?:\b(send|delete|pay|purchase|transfer|confirm|submit|post|buy|remove|share)\b|भेज|हट|मिटा|खरीद|भुगतान|पैसा|स्थानांतरण|पुष्टि|साझा)',
      caseSensitive: false,
    ).hasMatch(target);
    final firstConfirmation = await _confirmVisibleClick(
      target,
      sensitive: sensitive,
    );
    if (!firstConfirmation || !mounted || !_enabled) {
      return;
    }
    if (sensitive) {
      final secondConfirmation = await _confirmVisibleClick(
        target,
        sensitive: true,
        finalStep: true,
      );
      if (!secondConfirmation || !mounted || !_enabled) {
        return;
      }
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
      if (mounted) {
        setState(() => _screenAssistError = '$error');
      }
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
    if (!mounted || !_enabled) {
      return;
    }
    setState(() {
      _busy = false;
      _reply = value;
      _pendingPlan = null;
      _sleeping = false;
    });
    _resetSleepTimer();
    if (_pendingPlan == null) {
      unawaited(_resumeWakeWordManager());
    }
  }

  Future<void> _openKeySettings() async {
    if (!mounted || _keySettingsOpen) {
      return;
    }
    _keySettingsOpen = true;
    _idleTimer?.cancel();
    final controller = TextEditingController();
    try {
      // Do not listen to voice while the owner types a private API key or
      // verifies the device's lock-screen credential.
      await _pauseWakeWordManager();
      if (!mounted) {
        return;
      }

      final action = await showDialog<bool>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: const Text('Owner Gemini API key'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: controller,
                obscureText: true,
                decoration: const InputDecoration(
                  labelText: 'Your Gemini API key',
                ),
              ),
              const SizedBox(height: 12),
              const Text(
                'Stored encrypted on this phone. Saving or removing requires '
                'your phone fingerprint or real screen-lock PIN/password. '
                'Jiva does not collect your PIN or need Firebase login.',
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext, false),
              child: const Text('Remove key'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(dialogContext, true),
              child: const Text('Test & save'),
            ),
          ],
        ),
      );
      if (action == null || !mounted) {
        return;
      }

      // A NEW OS authentication is required for EVERY storage mutation.
      // Do not reuse a successful authentication for another operation.
      final permitted = await _ownerKeyAuth.confirm(action ? 'save' : 'remove');
      if (!mounted) {
        return;
      }
      if (!permitted) {
        _showReply(
          'Owner verification cancelled or unavailable. Gemini key unchanged.',
        );
        return;
      }

      if (action) {
        final key = controller.text.trim();
        if (key.isEmpty) {
          _showReply('Enter a Gemini API key before saving.');
          return;
        }
        setState(() {
          _busy = true;
          _reply = 'Verifying Gemini key and model access…';
        });
        try {
          await _gemini.verifyApiKey(
            key,
            onRetry: (attempt, _) {
              if (mounted && _enabled) {
                setState(() {
                  _reply =
                      'Gemini verification interrupted. Retrying (${attempt + 1}/${GeminiRetryPolicy.maxAttempts})…';
                });
              }
            },
          );
          await _keyStore.save(key);
          if (mounted) {
            setState(() {
              _hasApiKey = true;
              _keyVerified = true;
              _reply =
                  'Owner verified. Gemini key saved encrypted on this phone.';
            });
          }
        } catch (_) {
          _showReply(
            'Gemini verification or secure storage failed. Key not changed.',
          );
        } finally {
          if (mounted) {
            setState(() => _busy = false);
          }
        }
      } else {
        try {
          await _keyStore.clear();
          if (mounted) {
            setState(() {
              _hasApiKey = false;
              _keyVerified = false;
              _reply =
                  'Owner verified. Gemini API key removed from this phone.';
            });
          }
        } catch (_) {
          _showReply('Secure Gemini key removal failed. Please try again.');
        }
      }
    } catch (_) {
      _showReply('Could not open owner key settings securely.');
    } finally {
      controller.clear();
      controller.dispose();
      _keySettingsOpen = false;
      if (mounted) {
        _resetSleepTimer();
        try {
          await _resumeWakeWordManager();
        } catch (_) {
          _showReply(
            'Could not resume wake-word microphone after key settings.',
          );
        }
      }
    }
  }

  Future<void> _importWakeWordModel(String name) async {
    try {
      final selection = await FilePicker.pickFiles(
        type: FileType.custom,
        allowedExtensions: const ['ppn'],
        withData: false,
      );
      final sourcePath = selection?.files.single.path;
      if (sourcePath == null) {
        return;
      }
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
    if (!mounted) {
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
                    if (await file.exists()) {
                      await file.delete();
                    }
                  }
                  await _keyStore.clearWakeWordConfig();
                  if (mounted) {
                    setState(() => _wakeWordModels = {});
                  }
                  if (dialogContext.mounted) {
                    Navigator.pop(dialogContext);
                  }
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
    if (!mounted || !_enabled || _planDialogOpen || _busy) {
      return;
    }
    // Reserve the dialog before async microphone work.
    _planDialogOpen = true;
    _idleTimer?.cancel();
    try {
      await _pauseWakeWordManager();
    } catch (error) {
      _planDialogOpen = false;
      _showReply('Could not pause wake-word microphone: $error');
      return;
    }
    if (!mounted || !_enabled) {
      _planDialogOpen = false;
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
                        final contact = await _contactPicker
                            .selectPhoneNumber();
                        final phoneNumbers = contact?.phoneNumbers;
                        final selected =
                            contact?.selectedPhoneNumber ??
                            (phoneNumbers != null && phoneNumbers.isNotEmpty
                                ? phoneNumbers.first
                                : null);
                        if (selected != null) {
                          number.text = selected;
                        }
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
    _planDialogOpen = false;
    if (accepted != null) {
      await _dispatch(accepted, expectedPlan: plan);
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
      'Choose a messaging app and chat, then tap Send yourself. Jiva cannot send silently.',
    AssistantAction.openSettings =>
      'Jiva opens Android settings; you approve protected changes there.',
    AssistantAction.camera => 'Open the camera app on this device.',
    AssistantAction.openApp =>
      'Open ${plan.appName ?? 'the selected app'} if installed.',
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

  Future<void> _dispatch(
    IntentPlan plan, {
    required IntentPlan expectedPlan,
  }) async {
    // The edited/confirmed plan is a copy, so compare its original dialog
    // identity instead. Reject callbacks from any stale dialog.
    if (!mounted ||
        !_enabled ||
        _busy ||
        !identical(_pendingPlan, expectedPlan)) {
      return;
    }
    setState(() {
      _pendingPlan = null;
      _busy = true;
      _sleeping = false;
    });
    _idleTimer?.cancel();
    try {
      await _pauseWakeWordManager();
      if (!mounted || !_enabled) {
        return;
      }
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
          final image = await _imagePicker.pickImage(
            source: ImageSource.camera,
          );
          if (image != null) {
            _showReply('Camera image captured on this device.');
          }
          break;
        case AssistantAction.openApp:
          await _openInstalledApp(plan.appName ?? '');
          break;
        case AssistantAction.reminder:
          await _editReminder();
          break;
        case AssistantAction.phoneNavigation:
          if (!Platform.isAndroid) {
            throw UnsupportedError(
              'Phone navigation is available only on Android.',
            );
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
        if (_pendingPlan == null) {
          await _resumeWakeWordManager();
        }
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
    final index = AppLaunchMatch.select(
      query,
      apps.map((app) => app.name).toList(growable: false),
    );
    String? selectedPackage;
    String selectedName = '';
    if (index != null) {
      selectedPackage = apps[index].packageName;
      selectedName = apps[index].name;
    } else {
      // No automatic guessing: the owner explicitly selects a launcher.
      if (!mounted || !_enabled) {
        return;
      }
      final filter = query.trim().toLowerCase();
      final candidates = apps
          .where(
            (app) => filter.isEmpty || app.name.toLowerCase().contains(filter),
          )
          .take(15)
          .toList();
      if (candidates.isEmpty) {
        throw StateError('No installed app matches "$query".');
      }
      selectedPackage = await showDialog<String>(
        context: context,
        builder: (dialogContext) => SimpleDialog(
          title: const Text('Choose exact installed app'),
          children: [
            for (final candidate in candidates)
              SimpleDialogOption(
                onPressed: () =>
                    Navigator.pop(dialogContext, candidate.packageName),
                child: Text('${candidate.name} (${candidate.packageName})'),
              ),
            TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: const Text('Cancel'),
            ),
          ],
        ),
      );
      if (selectedPackage == null || !mounted || !_enabled) {
        return;
      }
      selectedName = candidates
          .firstWhere((app) => app.packageName == selectedPackage)
          .name;
    }
    if (!mounted || !_enabled) {
      return;
    }
    if (await InstalledApps.startApp(selectedPackage) != true) {
      throw StateError('Android could not open $selectedName.');
    }
  }

  Future<void> _editReminder() async {
    if (!mounted || !_enabled || _reminderDialogOpen) {
      return;
    }
    _reminderDialogOpen = true;
    final wasBusy = _busy;
    _idleTimer?.cancel();
    if (!wasBusy && mounted) {
      setState(() => _busy = true);
    }
    final text = TextEditingController();
    var selected = DateTime.now().add(const Duration(hours: 1));
    var notificationsDisabled = false;
    var saving = false;
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
                child: Text(
                  'Date: ${selected.year}-${selected.month.toString().padLeft(2, '0')}-${selected.day.toString().padLeft(2, '0')}',
                ),
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
                child: Text(
                  'Time: ${TimeOfDay.fromDateTime(selected).format(context)}',
                ),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: saving
                  ? null
                  : () async {
                      if (saving) {
                        return;
                      }
                      updateDialog(() => saving = true);
                      try {
                        if (text.text.trim().isEmpty) {
                          throw ArgumentError('Reminder text cannot be empty.');
                        }
                        if (!selected.isAfter(DateTime.now())) {
                          throw ArgumentError('Choose a time in the future.');
                        }
                        final androidPermission = await _notifications
                            .resolvePlatformSpecificImplementation<
                              AndroidFlutterLocalNotificationsPlugin
                            >()
                            ?.requestNotificationsPermission();
                        final iosPermission = await _notifications
                            .resolvePlatformSpecificImplementation<
                              IOSFlutterLocalNotificationsPlugin
                            >()
                            ?.requestPermissions(
                              alert: true,
                              badge: true,
                              sound: true,
                            );
                        notificationsDisabled =
                            androidPermission == false ||
                            iosPermission == false;
                        final result = await _reminderService.schedule(
                          text.text,
                          selected,
                        );
                        if (dialogContext.mounted) {
                          Navigator.pop(dialogContext, result);
                        }
                      } catch (error) {
                        _showReply(error.toString());
                      } finally {
                        if (dialogContext.mounted) {
                          updateDialog(() => saving = false);
                        }
                      }
                    },
              child: const Text('Schedule'),
            ),
          ],
        ),
      ),
    );
    text.dispose();
    _reminderDialogOpen = false;
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

  void _showAboutJiva() {
    Navigator.of(
      context,
    ).push(MaterialPageRoute<void>(builder: (_) => const JivaAboutScreen()));
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      titleSpacing: 8,
      title: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Image.asset(
            'assets/branding/jiva_symbol.png',
            width: 34,
            height: 34,
            fit: BoxFit.contain,
          ),
          const SizedBox(width: 5),
          const Flexible(
            child: Text(
              'Jiva AI',
              maxLines: 1,
              overflow: TextOverflow.fade,
              softWrap: false,
              style: TextStyle(fontWeight: FontWeight.w800, fontSize: 19),
            ),
          ),
        ],
      ),
      actions: [
        Semantics(
          label: _enabled ? 'Jiva enabled' : 'Jiva disabled',
          child: Switch(value: _enabled, onChanged: _setAssistantEnabled),
        ),
        IconButton(
          tooltip: 'Wake-word settings',
          onPressed: _openWakeWordSettings,
          icon: const Icon(Icons.hearing_outlined),
        ),
        IconButton(
          tooltip: !_hasApiKey
              ? 'Gemini key'
              : _keyVerified
              ? 'Gemini verified'
              : 'Gemini key saved',
          onPressed: _openKeySettings,
          icon: const Icon(Icons.auto_awesome_outlined),
        ),
        PopupMenuButton<String>(
          tooltip: 'More options',
          onSelected: (value) {
            if (value == 'about') _showAboutJiva();
            if (value == 'key') _openKeySettings();
          },
          itemBuilder: (context) => const [
            PopupMenuItem(value: 'about', child: Text('About Jiva AI')),
            PopupMenuItem(value: 'key', child: Text('Gemini API key')),
          ],
        ),
      ],
    ),
    body: SafeArea(
      child: ListView(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 18),
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 18),
            decoration: BoxDecoration(
              gradient: JivaTheme.accentGradient,
              borderRadius: BorderRadius.circular(22),
              boxShadow: [
                BoxShadow(
                  color: JivaPalette.teal.withValues(alpha: .15),
                  blurRadius: 18,
                  offset: const Offset(0, 6),
                ),
              ],
            ),
            child: Row(
              children: [
                const Icon(Icons.graphic_eq, size: 34, color: JivaPalette.cyan),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Your AI Assistant',
                        style: Theme.of(context).textTheme.titleLarge?.copyWith(
                          color: Colors.white,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                      const SizedBox(height: 3),
                      const Text(
                        'Talk · Plan · Read · Assist · Automate',
                        style: TextStyle(color: Colors.white, fontSize: 12),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 14),
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
                    style: Theme.of(context).textTheme.titleLarge?.copyWith(
                      color: Theme.of(context).colorScheme.onSurface,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  if (_transcript.isNotEmpty) Text('You: $_transcript'),
                  const SizedBox(height: 8),
                  Text(_reply),
                  const SizedBox(height: 12),
                  DropdownButtonFormField<String>(
                    initialValue: _selectedVoice,
                    decoration: const InputDecoration(labelText: 'Jiva voice'),
                    items: voicePresets
                        .map(
                          (voice) => DropdownMenuItem(
                            value: voice.name,
                            child: JivaButtonLabel(
                              '${voice.perceivedVoice}: ${voice.label}',
                            ),
                          ),
                        )
                        .toList(),
                    onChanged: _enabled
                        ? (value) async {
                            if (value == null) {
                              return;
                            }
                            await _secureStorage.write(
                              key: 'jiva_selected_voice',
                              value: value,
                            );
                            if (mounted) {
                              setState(() => _selectedVoice = value);
                            }
                          }
                        : null,
                  ),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      Expanded(
                        child: JivaGradientButton(
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
                          icon: Icons.mic,
                          label: _listening ? 'Listening…' : 'Speak',
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
                      if (_sleeping) {
                        setState(() => _sleeping = false);
                      }
                      _resetSleepTimer();
                    },
                    onSubmitted: (_) => _submit(),
                  ),
                  const SizedBox(height: 8),
                  JivaGradientButton(
                    onPressed: !_enabled || _busy ? null : _submit,
                    icon: Icons.send_outlined,
                    label: 'Send',
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          JivaSectionHeading(title: 'Quick Actions', icon: Icons.bolt),
          const SizedBox(height: 10),
          LayoutBuilder(
            builder: (context, constraints) {
              final columns = constraints.maxWidth >= 330 ? 3 : 2;
              return GridView.count(
                crossAxisCount: columns,
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                childAspectRatio: columns == 3 ? 1.95 : 2.5,
                crossAxisSpacing: 8,
                mainAxisSpacing: 8,
                children: [
                  JivaActionTile(
                    label: 'Apps',
                    icon: Icons.apps,
                    onPressed: !_enabled
                        ? null
                        : () => _showPlan(
                            const IntentPlan(
                              action: AssistantAction.openApp,
                              reply: 'Choose an installed app.',
                            ),
                          ),
                  ),
                  JivaActionTile(
                    label: 'Reminders',
                    icon: Icons.calendar_month,
                    onPressed: _enabled ? _editReminder : null,
                  ),
                  JivaActionTile(
                    label: 'Settings',
                    icon: Icons.settings_outlined,
                    onPressed: !_enabled
                        ? null
                        : () => _showPlan(
                            const IntentPlan(
                              action: AssistantAction.openSettings,
                              reply: 'Open settings?',
                            ),
                          ),
                  ),
                  JivaActionTile(
                    label: 'Call',
                    icon: Icons.call_outlined,
                    onPressed: !_enabled
                        ? null
                        : () => _showPlan(
                            const IntentPlan(
                              action: AssistantAction.call,
                              reply: 'Choose a number to call.',
                            ),
                          ),
                  ),
                  JivaActionTile(
                    label: 'Message',
                    icon: Icons.chat_bubble_outline,
                    onPressed: !_enabled
                        ? null
                        : () => _showPlan(
                            const IntentPlan(
                              action: AssistantAction.sms,
                              reply: 'Choose a number and review a message.',
                            ),
                          ),
                  ),
                  JivaActionTile(
                    label: 'Camera',
                    icon: Icons.photo_camera_outlined,
                    onPressed: !_enabled
                        ? null
                        : () => _showPlan(
                            const IntentPlan(
                              action: AssistantAction.camera,
                              reply: 'Open camera?',
                            ),
                          ),
                  ),
                ],
              );
            },
          ),
          const SizedBox(height: 12),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const JivaSectionHeading(
                    title: 'Live Conversation',
                    icon: Icons.mic,
                  ),
                  const SizedBox(height: 10),
                  JivaGradientButton(
                    label: _liveActive
                        ? 'Stop Live Conversation'
                        : _liveStarting
                        ? 'Connecting…'
                        : 'Start Live Conversation',
                    icon: _liveActive ? Icons.stop_circle : Icons.mic,
                    onPressed: _liveStarting || !_enabled || !_isForeground
                        ? null
                        : _toggleLiveConversation,
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Live mic requires an explicit start, network, permissions and a supported model.',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),
          Card(
            child: ExpansionTile(
              title: const Text(
                'Offline AI & Maintenance',
                style: TextStyle(fontWeight: FontWeight.w800),
              ),
              leading: const Icon(Icons.memory, color: JivaPalette.teal),
              childrenPadding: const EdgeInsets.fromLTRB(12, 0, 12, 14),
              children: [
                Row(
                  children: [
                    Expanded(
                      child: OutlinedButton(
                        onPressed: _offlineModelBusy
                            ? null
                            : _importOfflineModel,
                        child: const JivaButtonLabel('Import local GGUF'),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: OutlinedButton(
                        onPressed: _offlineModelBusy
                            ? null
                            : _toggleOfflineModel,
                        child: JivaButtonLabel(
                          _offlineModelLoaded
                              ? 'Unload offline AI'
                              : 'Load offline AI',
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton(
                    onPressed: _offlineModelLoaded && !_offlineModelBusy
                        ? _askOfflineModel
                        : null,
                    child: const JivaButtonLabel('Ask offline AI'),
                  ),
                ),
                SizedBox(
                  width: double.infinity,
                  child: OutlinedButton(
                    onPressed: _toggleSafeMaintenance,
                    child: JivaButtonLabel(
                      _maintenanceScheduled
                          ? 'Stop safe maintenance'
                          : 'Schedule safe maintenance',
                    ),
                  ),
                ),
                Text(
                  'Local model is user-imported and runs on-device. Maintenance only performs private integrity upkeep. Phone actions still require approval.',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
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
            child: ExpansionTile(
              leading: const Icon(
                Icons.settings_voice,
                color: JivaPalette.teal,
              ),
              title: const Text(
                'Voice & Listening',
                style: TextStyle(fontWeight: FontWeight.w800),
              ),
              subtitle: Text(
                _audioProcessingStatus,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              children: [
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
              child: ExpansionTile(
                leading: const Icon(
                  Icons.desktop_windows_outlined,
                  color: JivaPalette.teal,
                ),
                title: const Text(
                  'Visible-screen Assistance',
                  style: TextStyle(fontWeight: FontWeight.w800),
                ),
                subtitle: const Text(
                  'Screen reading, approved actions & phone workflows',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                childrenPadding: const EdgeInsets.fromLTRB(14, 2, 14, 14),
                children: [
                  const SizedBox(height: 8),
                  Text(
                    'Optional Android Accessibility access. After confirming, switch to the target app within 30 seconds; Jiva reads or acts only once on that next app screen. Text stays local and is not sent to Gemini. Android cannot reliably identify every secure window; do not use this on private, banking, login, or payment screens. Password fields block all screen actions.',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  const SizedBox(height: 12),
                  Text(
                    'AI-guided phone workflow',
                    style: Theme.of(context).textTheme.titleSmall,
                  ),
                  const Text(
                    'Describe a task, review the plan, then use safe-run for hands-free safe steps. Sensitive steps pause for approval. Screen text stays local.',
                  ),
                  TextField(
                    controller: _workflowTaskInput,
                    enabled: _enabled && !_workflowPlanning,
                    maxLength: 1000,
                    decoration: const InputDecoration(
                      labelText: 'Describe a phone task',
                      hintText: 'Find settings, scroll, then read',
                    ),
                  ),
                  OutlinedButton(
                    onPressed:
                        !_enabled ||
                            _workflowPlanning ||
                            _screenAssistPending ||
                            _phoneWorkflow != null
                        ? null
                        : _planPhoneTask,
                    child: Text(
                      _workflowPlanning
                          ? 'Planning…'
                          : 'Generate plan (offline basics supported)',
                    ),
                  ),
                  if (_phoneWorkflow != null) ...[
                    const SizedBox(height: 6),
                    Text(
                      'Step ${_phoneWorkflow!.index + 1} of ${_phoneWorkflow!.workflow.steps.length}',
                    ),
                    for (
                      var i = 0;
                      i < _phoneWorkflow!.workflow.steps.length;
                      i++
                    )
                      Text(
                        '${i < _phoneWorkflow!.index ? "✓" : "•"} ${_phoneWorkflow!.workflow.steps[i].description}',
                      ),
                    Text(_phoneWorkflow!.lastStatus),
                    if (_phoneWorkflow!.isComplete)
                      Text(
                        _phoneWorkflow!.goalVerified
                            ? 'Goal verified from exact visible result.'
                            : 'Steps ended, but end-goal was not verified.',
                      ),
                    if (_workflowRecovered)
                      const Text(
                        'Recovered after app restart. Review target app; no action automatically replays.',
                      ),
                    if (_phoneWorkflow!.uncertainAfterRestart)
                      const Text(
                        'Action status uncertain. Cancel and re-plan after inspecting the target app.',
                      ),
                    Wrap(
                      spacing: 8,
                      children: [
                        FilledButton(
                          onPressed:
                              !_enabled ||
                                  _phoneWorkflow!.isComplete ||
                                  _phoneWorkflow!.awaitingResult ||
                                  _screenAssistPending ||
                                  _workflowActionDispatching ||
                                  _phoneWorkflow!.uncertainAfterRestart
                              ? null
                              : _runNextWorkflowStep,
                          child: Text(
                            _phoneWorkflow!.isComplete
                                ? 'Done'
                                : 'Approve next step',
                          ),
                        ),
                        OutlinedButton(
                          onPressed:
                              !_enabled ||
                                  _autoRunSafeSteps ||
                                  _phoneWorkflow!.isComplete ||
                                  _phoneWorkflow!.awaitingResult ||
                                  _workflowActionDispatching ||
                                  _phoneWorkflow!.uncertainAfterRestart
                              ? null
                              : _startAutoSafeWorkflow,
                          child: Text(
                            _autoRunSafeSteps
                                ? 'Safe-run active'
                                : 'Run safe steps',
                          ),
                        ),
                        TextButton(
                          onPressed: _cancelPhoneWorkflow,
                          child: const Text('Cancel workflow'),
                        ),
                      ],
                    ),
                  ],
                  const SizedBox(height: 8),
                  OutlinedButton(
                    onPressed: !_enabled ? null : _readVisibleScreen,
                    child: const JivaButtonLabel('Read visible screen'),
                  ),
                  OutlinedButton.icon(
                    icon: const Icon(Icons.picture_in_picture_alt_outlined),
                    onPressed:
                        !_enabled ||
                            !_screenAccessibilityEnabled ||
                            _floatingControlsBusy
                        ? null
                        : _toggleFloatingControls,
                    label: JivaButtonLabel(
                      _floatingControlsBusy
                          ? 'Updating…'
                          : _floatingControlsEnabled
                          ? 'Hide system-wide Jiva controls'
                          : 'Show system-wide Jiva controls',
                    ),
                  ),
                  const Text(
                    'Floating controls require enabled Android Accessibility. '
                    'Jiva/Stop/Close only; background actions are never silently approved.',
                  ),
                  OutlinedButton.icon(
                    icon: const Icon(Icons.screenshot_monitor),
                    onPressed:
                        !_enabled ||
                            _screenAssistPending ||
                            _phoneWorkflow != null
                        ? null
                        : _captureReviewedScreenshot,
                    label: const JivaButtonLabel(
                      'Capture screenshot (local preview)',
                    ),
                  ),
                  if (_reviewedScreenJpeg != null) ...[
                    Text(
                      'Captured from $_reviewedScreenPackage (local until you explicitly share)',
                    ),
                    Image.memory(_reviewedScreenJpeg!, gaplessPlayback: false),
                    OutlinedButton.icon(
                      icon: const Icon(Icons.visibility),
                      onPressed: _screenVisionBusy
                          ? null
                          : _explainReviewedScreenshot,
                      label: Text(
                        _screenVisionBusy
                            ? 'Analyzing…'
                            : 'Explain with Gemini (share once)',
                      ),
                    ),
                    if (_screenVisionAnswer.isNotEmpty)
                      SelectableText(_screenVisionAnswer),
                    TextButton(
                      onPressed: () => setState(() {
                        _reviewedScreenJpeg = null;
                        _reviewedScreenPackage = '';
                        _screenVisionAnswer = '';
                      }),
                      child: const Text('Delete screenshot from memory'),
                    ),
                  ],
                  Row(
                    children: [
                      Expanded(
                        child: JivaActionTile(
                          label: 'Back',
                          icon: Icons.arrow_back,
                          onPressed: !_enabled
                              ? null
                              : () => _performVisibleScreenAction('back'),
                        ),
                      ),
                      const SizedBox(width: 6),
                      Expanded(
                        child: JivaActionTile(
                          label: 'Scroll up',
                          icon: Icons.arrow_upward,
                          onPressed: !_enabled
                              ? null
                              : () => _performVisibleScreenAction(
                                  'scroll_backward',
                                ),
                        ),
                      ),
                      const SizedBox(width: 6),
                      Expanded(
                        child: JivaActionTile(
                          label: 'Scroll down',
                          icon: Icons.arrow_downward,
                          onPressed: !_enabled
                              ? null
                              : () => _performVisibleScreenAction(
                                  'scroll_forward',
                                ),
                        ),
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
                    child: const JivaButtonLabel('Review and click'),
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
                                setState(() => _screenAssistError = '$error');
                              }
                            }
                          },
                    child: JivaButtonLabel(
                      _screenAccessibilityEnabled
                          ? 'Manage Android Accessibility Settings'
                          : 'Open Android Accessibility Settings',
                    ),
                  ),
                ],
              ),
            ),
          ],
          const SizedBox(height: 18),
          const JivaSectionHeading(
            title: 'Upcoming Reminders',
            icon: Icons.notifications_outlined,
          ),
          if (_reminders.isEmpty)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 10),
              child: Text('No reminders scheduled.'),
            ),
          for (final item in _reminders)
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.alarm),
              title: Text(item.text),
              subtitle: Text(item.triggerAt.toLocal().toString()),
            ),
          const SizedBox(height: 16),
          Card(
            child: ListTile(
              leading: const Icon(Icons.info_outline),
              title: const Text('About Jiva AI'),
              subtitle: const Text('Version 1.0.1 · Build 2'),
              trailing: const Icon(Icons.chevron_right),
              onTap: _showAboutJiva,
            ),
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
    floatingActionButton: (_pendingPlan == null && _phoneWorkflow == null)
        ? null
        : Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              if (_pendingPlan != null)
                FloatingActionButton.extended(
                  heroTag: 'jiva_review_action',
                  onPressed: () => _showPlan(_pendingPlan!),
                  icon: const Icon(Icons.verified_user_outlined),
                  label: const Text('Review action'),
                ),
              if (_pendingPlan != null && _phoneWorkflow != null)
                const SizedBox(height: 12),
              if (_phoneWorkflow != null)
                FloatingActionButton.extended(
                  heroTag: 'jiva_phone_workflow',
                  tooltip: 'Jiva task control inside this app',
                  onPressed:
                      _phoneWorkflow!.awaitingResult ||
                          _workflowActionDispatching ||
                          _phoneWorkflow!.uncertainAfterRestart ||
                          _phoneWorkflow!.isComplete
                      ? null
                      : _runNextWorkflowStep,
                  icon: const Icon(Icons.assistant),
                  label: Text(
                    _phoneWorkflow!.isComplete
                        ? 'Task done'
                        : 'Review step ${_phoneWorkflow!.index + 1}',
                  ),
                ),
            ],
          ),
  );
}
