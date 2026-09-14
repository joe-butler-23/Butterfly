import 'dart:async';

import 'package:flutter/scheduler.dart';

class CoalescedAsyncRunner {
  CoalescedAsyncRunner({required this.delay, this.restartDelay = true});

  final Duration delay;

  /// Whether a new task postpones a pending run by [delay].
  /// Set to false to coalesce calls at a fixed maximum frame rate.
  final bool restartDelay;
  Timer? _timer;
  Future<void>? _running;
  Future<void> Function()? _pendingTask;
  Completer<void>? _pendingCompleter;
  bool _disposed = false;

  Future<void> schedule(Future<void> Function() task) {
    if (_disposed) return Future.value();
    _pendingTask = task;
    final completer = _pendingCompleter ??= Completer<void>();
    if (_running == null && (restartDelay || _timer == null)) {
      _timer?.cancel();
      _timer = Timer(delay, _runPending);
    }
    return completer.future;
  }

  void _runPending() {
    _timer = null;
    final task = _pendingTask;
    final completer = _pendingCompleter;
    _pendingTask = null;
    _pendingCompleter = null;
    if (_disposed || task == null) {
      if (completer?.isCompleted == false) completer?.complete();
      return;
    }
    if (!restartDelay) {
      // Start the throttle interval with the work itself. If a slow task takes
      // longer than [delay], the latest pending update can run immediately
      // instead of adding another full delay after the task completes.
      _timer = Timer(delay, () {
        _timer = null;
        if (_running == null && _pendingTask != null && !_disposed) {
          _runPending();
        }
      });
    }
    _running = () async {
      try {
        await task();
        if (completer?.isCompleted == false) completer?.complete();
      } catch (error, stackTrace) {
        if (completer?.isCompleted == false) {
          completer?.completeError(error, stackTrace);
        }
      }
    }();
    unawaited(
      _running!.whenComplete(() {
        _running = null;
        if (_pendingTask != null && !_disposed) {
          if (restartDelay) {
            _timer = Timer(delay, _runPending);
          } else if (_timer == null) {
            _runPending();
          }
        }
      }),
    );
  }

  void cancel() {
    _timer?.cancel();
    _timer = null;
    _pendingTask = null;
    final completer = _pendingCompleter;
    _pendingCompleter = null;
    if (completer?.isCompleted == false) completer?.complete();
  }

  void dispose() {
    _disposed = true;
    cancel();
  }

  Future<void> disposeAndWait() async {
    dispose();
    await _running;
  }
}

/// Runs the latest request at a frame boundary.
///
/// Requests made before the frame are coalesced. A request made while work is
/// running replaces any request waiting for the next frame. The frame callback
/// is the only source of scheduling; no duration is used to approximate a
/// display frame.
class FrameCoalescedAsyncRunner {
  FrameCoalescedAsyncRunner({this.binding});

  /// Resolved lazily so constructing a runner never requires a binding.
  final SchedulerBinding? binding;
  SchedulerBinding get _scheduler => binding ?? SchedulerBinding.instance;
  _FrameRequest? _pending;
  int? _frameCallbackId;
  Future<void>? _running;
  bool _disposed = false;

  Future<void> schedule(Future<void> Function() task) {
    if (_disposed) return Future.value();
    final request = _pending;
    final completer = Completer<void>();
    if (request == null) {
      _pending = _FrameRequest(task, [completer]);
    } else {
      request.task = task;
      request.completers.add(completer);
    }
    if (_running == null && _frameCallbackId == null) {
      _scheduleFrame();
    }
    return completer.future;
  }

  void _scheduleFrame() {
    if (_disposed || _frameCallbackId != null) return;
    _frameCallbackId = _scheduler.scheduleFrameCallback(_onFrame);
  }

  void _onFrame(Duration _) {
    _frameCallbackId = null;
    if (_disposed || _running != null) return;
    final request = _pending;
    _pending = null;
    if (request == null) return;

    final running = Completer<void>();
    _running = running.future;
    unawaited(_run(request, running));
  }

  Future<void> _run(_FrameRequest request, Completer<void> running) async {
    try {
      await request.task();
      _complete(request.completers);
    } catch (error, stackTrace) {
      _completeError(request.completers, error, stackTrace);
    } finally {
      running.complete();
      if (identical(_running, running.future)) {
        _running = null;
      }
      if (!_disposed && _pending != null && _frameCallbackId == null) {
        _scheduleFrame();
      }
    }
  }

  static void _complete(List<Completer<void>> completers) {
    for (final completer in completers) {
      if (!completer.isCompleted) completer.complete();
    }
  }

  static void _completeError(
    List<Completer<void>> completers,
    Object error,
    StackTrace stackTrace,
  ) {
    for (final completer in completers) {
      if (!completer.isCompleted) completer.completeError(error, stackTrace);
    }
  }

  /// Cancels the queued frame and completes its callers without running it.
  /// Work that has already started is allowed to finish.
  void cancel() {
    final callbackId = _frameCallbackId;
    if (callbackId != null) {
      _scheduler.cancelFrameCallbackWithId(callbackId);
      _frameCallbackId = null;
    }
    final request = _pending;
    _pending = null;
    if (request != null) _complete(request.completers);
  }

  void dispose() {
    if (_disposed) return;
    _disposed = true;
    cancel();
  }

  Future<void> disposeAndWait() async {
    dispose();
    await _running;
  }
}

class _FrameRequest {
  _FrameRequest(this.task, this.completers);

  Future<void> Function() task;
  final List<Completer<void>> completers;
}
