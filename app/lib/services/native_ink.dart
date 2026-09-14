import 'dart:async';
import 'dart:io' show Platform;

import 'package:butterfly/cubits/settings.dart';
import 'package:butterfly/cubits/transform.dart';
import 'package:butterfly/handlers/handler.dart';
import 'package:butterfly_api/butterfly_api.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

const _channelName = 'linwood.dev/butterfly/ink';

@immutable
class NativeInkBrush {
  const NativeInkBrush({required this.argb, required this.width});

  final int argb;
  final double width;

  factory NativeInkBrush.fromPen(PenTool tool, CameraTransform camera) {
    final property = tool.property;
    return NativeInkBrush(
      argb: property.paint.previewColor.value,
      width: property.strokeWidth * (tool.zoomDependent ? 1 : camera.size),
    );
  }
}

@immutable
class NativeInkGeometry {
  const NativeInkGeometry({
    required this.argb,
    required this.width,
    required this.thinning,
    required this.smoothing,
    required this.streamline,
    required this.pressurePolicy,
  });

  final int argb;
  final double width, thinning, smoothing, streamline;
  final IgnorePressure pressurePolicy;

  Map<String, Object> toMap() => {
    'argb': argb,
    'width': width,
    'thinning': thinning,
    'smoothing': smoothing,
    'streamline': streamline,
    'pressurePolicy': pressurePolicy.name,
  };

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is NativeInkGeometry &&
          argb == other.argb &&
          width == other.width &&
          thinning == other.thinning &&
          smoothing == other.smoothing &&
          streamline == other.streamline &&
          pressurePolicy == other.pressurePolicy;

  @override
  int get hashCode =>
      Object.hash(argb, width, thinning, smoothing, streamline, pressurePolicy);
}

@immutable
class NativeInkState {
  const NativeInkState({
    required this.generation,
    required this.argb,
    required this.width,
    required this.geometry,
    required this.canvasBounds,
    required this.devicePixelRatio,
  });

  static const protocolVersion = 1;
  final int generation, argb;
  final double width, devicePixelRatio;
  final NativeInkGeometry geometry;
  final Rect canvasBounds;

  Map<String, Object> toMap() => {
    'protocolVersion': protocolVersion,
    'generation': generation,
    'enabled': true,
    'eligible': true,
    'argb': argb,
    'width': width,
    'geometry': geometry.toMap(),
    'canvasBounds': {
      'left': canvasBounds.left,
      'top': canvasBounds.top,
      'right': canvasBounds.right,
      'bottom': canvasBounds.bottom,
    },
    'devicePixelRatio': devicePixelRatio,
  };

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is NativeInkState &&
          generation == other.generation &&
          argb == other.argb &&
          width == other.width &&
          geometry == other.geometry &&
          canvasBounds == other.canvasBounds &&
          devicePixelRatio == other.devicePixelRatio;

  @override
  int get hashCode => Object.hash(
    generation,
    argb,
    width,
    geometry,
    canvasBounds,
    devicePixelRatio,
  );
}

@immutable
class _Registration {
  const _Registration(this.identity, this.pointer);

  final NativeInkStrokeIdentity identity;
  final int pointer;

  Map<String, Object> toMap() => {
    'generation': identity.generation,
    'strokeSequence': identity.strokeSequence,
    'sourceTimestampUs': identity.sourceTimestampUs,
    'pointer': pointer,
  };
}

class _Stroke {
  _Stroke(this.registration);

  final _Registration registration;
  String? finalElementId;
  int? finalPointCount;
}

class _Handoff {
  static const _maximum = 16;
  final Map<int, _Stroke> _activeByPointer = {};
  final Map<int, _Stroke> _bySequence = {};
  final List<_Stroke> _ordered = [];
  final List<_Registration> _retired = [];
  int _nextSequence = 0;
  bool overflowed = false;

  bool get hasFinals => _ordered.any((stroke) => stroke.finalElementId != null);

  _Registration register({
    required int generation,
    required int pointer,
    required int sourceTimestampUs,
  }) {
    final previous = _activeByPointer.remove(pointer);
    if (previous != null) _retire(previous);
    final registration = _Registration((
      generation: generation,
      strokeSequence: ++_nextSequence,
      sourceTimestampUs: sourceTimestampUs,
    ), pointer);
    final stroke = _Stroke(registration);
    _activeByPointer[pointer] = stroke;
    _bySequence[registration.identity.strokeSequence] = stroke;
    _ordered.add(stroke);
    if (_ordered.length > _maximum) {
      overflowed = true;
      _retire(_ordered.first);
    }
    return registration;
  }

  bool markFinal(
    NativeInkStrokeIdentity identity,
    String elementId,
    int pointCount,
  ) {
    final stroke = _matching(identity);
    if (stroke == null || elementId.isEmpty || pointCount <= 1) return false;
    if (stroke.finalElementId != null &&
        (stroke.finalElementId != elementId ||
            stroke.finalPointCount != pointCount)) {
      overflowed = true;
      return false;
    }
    stroke.finalElementId = elementId;
    stroke.finalPointCount = pointCount;
    if (identical(_activeByPointer[stroke.registration.pointer], stroke)) {
      _activeByPointer.remove(stroke.registration.pointer);
    }
    return true;
  }

  // Cancel any pending final older than the immediately previous stroke,
  // keeping at most one unacknowledged final behind the newest stroke. Never
  // touches the newest two entries (the just-registered stroke and the one
  // before it), so a fast second stroke can't cause a visible gap while the
  // previous stroke's ack round trip is still in flight.
  List<_Registration> cancelStaleFinals() {
    if (_ordered.length < 3) return const [];
    final stale = <_Stroke>[
      for (var i = 0; i < _ordered.length - 2; i++)
        if (_ordered[i].finalElementId != null) _ordered[i],
    ];
    for (final stroke in stale) {
      _remove(stroke);
    }
    return stale.map((stroke) => stroke.registration).toList(growable: false);
  }

  List<Map<String, Object>> painted(
    Iterable<({String elementId, int? pointCount})> receipts,
  ) {
    final painted = <String, Set<int>>{};
    for (final receipt in receipts) {
      final count = receipt.pointCount;
      if (receipt.elementId.isNotEmpty && count != null) {
        (painted[receipt.elementId] ??= {}).add(count);
      }
    }
    final acknowledgements = <Map<String, Object>>[];
    for (final stroke in List<_Stroke>.of(_ordered)) {
      final id = stroke.finalElementId;
      final count = stroke.finalPointCount;
      if (id == null ||
          count == null ||
          !(painted[id]?.contains(count) ?? false)) {
        continue;
      }
      final identity = stroke.registration.identity;
      acknowledgements.add({
        'generation': identity.generation,
        'strokeSequence': identity.strokeSequence,
        'sourceTimestampUs': identity.sourceTimestampUs,
        'finalElementId': id,
        'finalPointCount': count,
      });
      _remove(stroke);
    }
    return acknowledgements;
  }

  _Registration? cancel(NativeInkStrokeIdentity identity) {
    final stroke = _matching(identity);
    if (stroke == null) return null;
    _remove(stroke);
    return stroke.registration;
  }

  List<_Registration> drainRetired() {
    final result = List<_Registration>.of(_retired);
    _retired.clear();
    return result;
  }

  List<_Registration> clear() {
    final result = <_Registration>[
      ..._retired,
      ..._ordered.map((stroke) => stroke.registration),
    ];
    _activeByPointer.clear();
    _bySequence.clear();
    _ordered.clear();
    _retired.clear();
    overflowed = false;
    return result;
  }

  _Stroke? _matching(NativeInkStrokeIdentity identity) {
    final stroke = _bySequence[identity.strokeSequence];
    final actual = stroke?.registration.identity;
    return actual == identity ? stroke : null;
  }

  void _retire(_Stroke stroke) {
    _remove(stroke);
    _retired.add(stroke.registration);
  }

  void _remove(_Stroke stroke) {
    _ordered.remove(stroke);
    _bySequence.remove(stroke.registration.identity.strokeSequence);
    if (identical(_activeByPointer[stroke.registration.pointer], stroke)) {
      _activeByPointer.remove(stroke.registration.pointer);
    }
  }
}

class NativeInkBridge {
  NativeInkBridge({MethodChannel? channel, bool? enabled})
    : _channel = channel ?? const MethodChannel(_channelName),
      _available =
          enabled ?? (nativeInkLabEnabled && !kIsWeb && Platform.isAndroid) {
    _channel.setMethodCallHandler(_handleMethodCall);
  }

  final MethodChannel _channel;
  final _Handoff _handoff = _Handoff();
  bool _available;
  bool _enabled = false;
  bool _disposed = false;
  int _generation = 0;
  int _controlEpoch = 0;
  int _activationTicket = 0;
  bool _activationInFlight = false;
  bool _retireForegrounds = false;
  int _consecutiveFailures = 0;
  static const _maxConsecutiveFailures = 3;
  NativeInkState? _failedState;
  NativeInkState? _state;
  ({
    Handler handler,
    ButterflySettings settings,
    Rect? canvasBounds,
    double devicePixelRatio,
    CameraTransform camera,
    bool allowNativeInk,
  })?
  _lastRequest;
  VoidCallback? _pendingFrameCallback;
  bool _frameCallbackScheduled = false;

  bool get enabled => _enabled;

  bool get awaitingPaintHandoff => _enabled && _handoff.hasFinals;

  bool get needsForegroundRetirement =>
      awaitingPaintHandoff || _retireForegrounds;

  void _clearHandoff() {
    _retireForegrounds |= _handoff.hasFinals;
    _handoff.clear();
  }

  Future<Object?> _handleMethodCall(MethodCall call) async {
    if (call.method == 'nativeFailure') {
      final generation = call.arguments;
      if (generation is int && _state?.generation == generation) {
        _failedState = _state;
        _consecutiveFailures++;
        _controlEpoch++;
        _enabled = false;
        _activationInFlight = false;
        _pendingFrameCallback = null;
        _state = null;
        _clearHandoff();
      }
      return null;
    }
    if (call.method != 'armChanged') return null;
    if (call.arguments == 'FLUTTER_ONLY') {
      unawaited(disable());
      return null;
    }
    disarm();
    final request = _lastRequest;
    if (request != null) {
      scheduleAfterFrame(
        () => unawaited(
          updateState(
            handler: request.handler,
            settings: request.settings,
            canvasBounds: request.canvasBounds,
            devicePixelRatio: request.devicePixelRatio,
            camera: request.camera,
            allowNativeInk: request.allowNativeInk,
          ),
        ),
      );
    }
    return null;
  }

  void scheduleAfterFrame(VoidCallback callback) {
    if (_disposed || !_available) return;
    _pendingFrameCallback = callback;
    if (_frameCallbackScheduled) return;
    _frameCallbackScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _frameCallbackScheduled = false;
      final pending = _pendingFrameCallback;
      _pendingFrameCallback = null;
      if (!_disposed && _available) pending?.call();
    });
  }

  Future<NativeInkState?> updateState({
    required Handler handler,
    required ButterflySettings settings,
    required Rect? canvasBounds,
    required double devicePixelRatio,
    required CameraTransform camera,
    bool allowNativeInk = true,
  }) async {
    if (_disposed || !_available) return null;
    _lastRequest = (
      handler: handler,
      settings: settings,
      canvasBounds: canvasBounds,
      devicePixelRatio: devicePixelRatio,
      camera: camera,
      allowNativeInk: allowNativeInk,
    );
    final pen = handler is PenHandler ? handler.data : null;
    final eligible =
        allowNativeInk &&
        pen != null &&
        _eligiblePen(pen, settings) &&
        canvasBounds != null &&
        _validRect(canvasBounds) &&
        devicePixelRatio.isFinite &&
        devicePixelRatio > 0;
    if (!eligible) {
      if (_state != null) await disable();
      return null;
    }
    final brush = NativeInkBrush.fromPen(pen, camera);
    if (!brush.width.isFinite || brush.width <= 0) {
      if (_state != null) await disable();
      return null;
    }
    final property = pen.property;
    final geometry = NativeInkGeometry(
      argb: brush.argb,
      width: brush.width,
      thinning: property.thinning,
      smoothing: property.smoothing,
      streamline: property.streamline,
      pressurePolicy: settings.ignorePressure,
    );
    final failed = _failedState;
    if (failed != null) {
      if (_sameConfiguration(
        failed,
        brush,
        geometry,
        canvasBounds,
        devicePixelRatio,
      )) {
        return null;
      }
      _failedState = null;
      _consecutiveFailures = 0;
    }
    final previous = _state;
    if (previous != null &&
        _sameConfiguration(
          previous,
          brush,
          geometry,
          canvasBounds,
          devicePixelRatio,
        ) &&
        (_enabled ||
            (_activationInFlight && _activationTicket == _controlEpoch))) {
      return previous;
    }

    final ticket = ++_controlEpoch;
    final state = NativeInkState(
      generation: ++_generation,
      argb: brush.argb,
      width: brush.width,
      geometry: geometry,
      canvasBounds: canvasBounds,
      devicePixelRatio: devicePixelRatio,
    );
    _enabled = false;
    _state = state;
    _clearHandoff();
    _activationTicket = ticket;
    _activationInFlight = true;
    try {
      final configured = await _invoke<Object>('configure', state.toMap());
      if (!_available ||
          ticket != _controlEpoch ||
          _state?.generation != state.generation) {
        return null;
      }
      if (configured != true) {
        _enabled = false;
        _state = null;
        _clearHandoff();
        return null;
      }
      final activated = await _invoke<Object>('enable', {
        'generation': state.generation,
      });
      if (!_available ||
          ticket != _controlEpoch ||
          _state?.generation != state.generation) {
        return null;
      }
      _enabled = activated == true;
      if (!_enabled) {
        await disable();
        return null;
      }
      return state;
    } finally {
      if (_activationTicket == ticket) _activationInFlight = false;
    }
  }

  static bool _sameConfiguration(
    NativeInkState state,
    NativeInkBrush brush,
    NativeInkGeometry geometry,
    Rect bounds,
    double dpr,
  ) =>
      state.argb == brush.argb &&
      state.width == brush.width &&
      state.geometry == geometry &&
      state.canvasBounds == bounds &&
      state.devicePixelRatio == dpr;

  void disarm({bool notifyNative = true}) {
    final generation = _state?.generation;
    _failedState = null;
    _controlEpoch++;
    _enabled = false;
    _pendingFrameCallback = null;
    if (notifyNative && generation != null) {
      unawaited(_invoke<void>('disable', {'generation': generation}));
    }
  }

  Future<void> disable() async {
    final generation = _state?.generation;
    _retireForegrounds |= _handoff.hasFinals;
    disarm(notifyNative: false);
    _clearHandoff();
    _state = null;
    if (!_available || generation == null) return;
    await _invoke<void>('disable', {'generation': generation});
  }

  NativeInkStrokeIdentity? registerStroke(PointerDownEvent event) {
    // Re-arm per stroke: a transient failure only blocks the stroke that
    // was active when it happened, unless failures keep recurring with no
    // successful stroke in between.
    if (_failedState != null && _consecutiveFailures < _maxConsecutiveFailures) {
      _failedState = null;
    }
    final state = _state;
    if (_disposed || !_available || !_enabled || state == null) return null;
    final registration = _handoff.register(
      generation: state.generation,
      pointer: event.pointer,
      sourceTimestampUs: event.timeStamp.inMicroseconds,
    );
    _flushRetired();
    // Keep at most one pending (unacknowledged) final behind the newest
    // stroke; anything older is gap-safe to cancel now.
    final staleFinals = _handoff.cancelStaleFinals();
    if (staleFinals.isNotEmpty) {
      _retireForegrounds = true;
      for (final stale in staleFinals) {
        unawaited(_invoke<void>('cancelStroke', stale.toMap()));
      }
    }
    if (_handoff.overflowed) {
      unawaited(disable());
      return null;
    }
    unawaited(_invoke<void>('registerStroke', registration.toMap()));
    return registration.identity;
  }

  bool markFinalStroke(
    NativeInkStrokeIdentity identity,
    String elementId,
    int pointCount,
  ) {
    if (!_enabled) return false;
    if (_handoff.markFinal(identity, elementId, pointCount)) return true;
    // Native may have fallen back this stroke while retaining the arm.
    _handoff.cancel(identity);
    return false;
  }

  void cancelStroke(NativeInkStrokeIdentity identity) {
    final registration = _handoff.cancel(identity);
    if (registration != null && _available) {
      unawaited(_invoke<void>('cancelStroke', registration.toMap()));
    }
  }

  void acknowledgePaintedElements(
    Iterable<({String elementId, int? pointCount})> receipts,
  ) {
    _retireForegrounds = false;
    if (!_enabled) return;
    final acknowledgements = _handoff.painted(receipts);
    if (acknowledgements.isNotEmpty) _consecutiveFailures = 0;
    for (final acknowledgement in acknowledgements) {
      unawaited(_invoke<void>('acknowledgeStroke', acknowledgement));
    }
  }

  Future<void> dispose() async {
    if (_disposed) return;
    _disposed = true;
    await disable();
  }

  void _flushRetired() {
    for (final registration in _handoff.drainRetired()) {
      unawaited(_invoke<void>('cancelStroke', registration.toMap()));
    }
  }

  Future<T?> _invoke<T>(String method, Object arguments) async {
    if (!_available) return null;
    try {
      return await _channel.invokeMethod<T>(method, arguments);
    } on MissingPluginException {
      _fail();
    } on PlatformException {
      _fail();
    } catch (_) {
      _fail();
    }
    return null;
  }

  void _fail() {
    _available = false;
    _enabled = false;
    _controlEpoch++;
    _clearHandoff();
  }

  static bool _validRect(Rect rect) =>
      rect.left.isFinite &&
      rect.top.isFinite &&
      rect.right.isFinite &&
      rect.bottom.isFinite &&
      rect.width > 0 &&
      rect.height > 0;

  static bool _eligiblePen(PenTool pen, ButterflySettings settings) {
    final input = settings.inputConfiguration;
    final property = pen.property;
    final paint = property.paint;
    final fill = property.fillPaint;
    const defaults = PenProperty();
    return input.pen.getCategory() == InputMappingCategory.activeTool &&
        input.holdShortcuts.isEmpty &&
        input.doublePenShortcut == null &&
        input.triplePenShortcut == null &&
        input.doubleInvertedPenShortcut == null &&
        input.tripleInvertedPenShortcut == null &&
        input.doubleFirstPenButtonShortcut == null &&
        input.tripleFirstPenButtonShortcut == null &&
        input.doubleSecondPenButtonShortcut == null &&
        input.tripleSecondPenButtonShortcut == null &&
        !pen.shapeDetectionEnabled &&
        !pen.combinePaths &&
        property.thinning == defaults.thinning &&
        property.smoothing == defaults.smoothing &&
        property.streamline == defaults.streamline &&
        settings.ignorePressure != IgnorePressure.always &&
        paint is SolidElementPaint &&
        paint.blur == 0 &&
        paint.color.a == 0xFF &&
        fill is SolidElementPaint &&
        fill.blur == 0 &&
        fill.color.a == 0;
  }
}
