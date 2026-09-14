import 'dart:async';
import 'dart:convert';
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
          enabled ?? (nativeInkLabEnabled && !kIsWeb && Platform.isAndroid);

  final MethodChannel _channel;
  final _Handoff _handoff = _Handoff();
  bool _available;
  bool _enabled = false;
  bool _disposed = false;
  int _generation = 0;
  int _controlEpoch = 0;
  String? _fingerprint;
  NativeInkState? _state;
  VoidCallback? _pendingFrameCallback;
  bool _frameCallbackScheduled = false;

  bool get enabled => _enabled;

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
    final fingerprint = jsonEncode({
      'argb': brush.argb,
      'width': brush.width,
      'geometry': geometry.toMap(),
      'bounds': [
        canvasBounds.left,
        canvasBounds.top,
        canvasBounds.right,
        canvasBounds.bottom,
      ],
      'dpr': devicePixelRatio,
    });
    if (fingerprint == _fingerprint && _enabled) return _state;

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
    _fingerprint = fingerprint;
    _state = state;
    _handoff.clear();

    final configured = await _invoke<Object>('configure', state.toMap());
    if (!_available ||
        ticket != _controlEpoch ||
        _state?.generation != state.generation) {
      return null;
    }
    if (configured != true) {
      _available = false;
      _state = null;
      _fingerprint = null;
      _handoff.clear();
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
    if (!_enabled) await disable();
    return _enabled ? state : null;
  }

  void disarm() {
    _controlEpoch++;
    _enabled = false;
    _pendingFrameCallback = null;
  }

  Future<void> disable() async {
    final generation = _state?.generation;
    disarm();
    final registrations = _handoff.clear();
    _state = null;
    _fingerprint = null;
    if (!_available) return;
    for (final registration in registrations) {
      await _invoke<void>('cancelStroke', registration.toMap());
    }
    if (generation != null) {
      await _invoke<void>('disable', {'generation': generation});
    }
  }

  NativeInkStrokeIdentity? registerStroke(PointerDownEvent event) {
    final state = _state;
    if (_disposed || !_available || !_enabled || state == null) return null;
    final registration = _handoff.register(
      generation: state.generation,
      pointer: event.pointer,
      sourceTimestampUs: event.timeStamp.inMicroseconds,
    );
    _flushRetired();
    if (_handoff.overflowed) {
      unawaited(disable());
      return null;
    }
    unawaited(_invoke<void>('registerStroke', registration.toMap()));
    return registration.identity;
  }

  void markFinalStroke(
    NativeInkStrokeIdentity identity,
    String elementId,
    int pointCount,
  ) {
    if (!_enabled || !_handoff.markFinal(identity, elementId, pointCount)) {
      unawaited(disable());
    }
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
    if (!_enabled) return;
    for (final acknowledgement in _handoff.painted(receipts)) {
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
    _handoff.clear();
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
