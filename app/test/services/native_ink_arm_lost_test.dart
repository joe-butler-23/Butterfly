// Verifies the NativeInkBridge -> PenHandler wiring added for the direct-
// latency change: when native ink loses its arm mid-stroke, the bridge must
// tell the PenHandler it last configured (NativeInkBridge._lastRequest.
// handler) so Flutter's foreground preview resumes instead of staying
// frozen or missing for the rest of the stroke. See
// NativeInkBridge._notifyArmLost / disable() and the nativeFailure branch of
// _handleMethodCall in lib/services/native_ink.dart, and
// PenHandler.handleNativeInkArmLost in lib/handlers/pen.dart.
//
// This mirrors the style and fake channel of native_ink_test.dart, but uses
// a recording PenHandler subclass as "the smallest reachable seam": driving
// a real stroke through PenHandler.onPointerDown/addPoint needs a widget
// tree with document/editor/transform/settings providers (see
// test/handlers/pen_native_ink_test.dart), which is unnecessary just to
// prove the bridge calls handleNativeInkArmLost() on the right handler at
// the right time.
import 'dart:async';

import 'package:butterfly/cubits/settings.dart';
import 'package:butterfly/cubits/transform.dart';
import 'package:butterfly/handlers/handler.dart';
import 'package:butterfly/services/native_ink.dart';
import 'package:butterfly_api/butterfly_api.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

const _testChannel = MethodChannel('test.linwood.dev/native_ink_arm_lost');

class _RecordingPenHandler extends PenHandler {
  _RecordingPenHandler(super.data);

  int armLostCalls = 0;

  @override
  void handleNativeInkArmLost() {
    armLostCalls++;
    super.handleNativeInkArmLost();
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Object? Function(MethodCall call) respond;

  setUp(() {
    respond = (call) {
      switch (call.method) {
        case 'configure':
        case 'enable':
          return true;
        default:
          return null;
      }
    };
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_testChannel, (call) async => respond(call));
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_testChannel, null);
  });

  Future<void> deliverIncoming(String method, Object? arguments) async {
    final message = const StandardMethodCodec().encodeMethodCall(
      MethodCall(method, arguments),
    );
    await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .handlePlatformMessage(_testChannel.name, message, (_) {});
  }

  Future<NativeInkState?> arm(NativeInkBridge bridge, Handler handler) =>
      bridge.updateState(
        handler: handler,
        settings: const ButterflySettings(autosave: false),
        canvasBounds: const Rect.fromLTWH(0, 0, 100, 100),
        devicePixelRatio: 1,
        camera: const CameraTransform(),
      );

  test(
    'armChanged -> FLUTTER_ONLY calls handleNativeInkArmLost on the last '
    'configured PenHandler',
    () async {
      final bridge = NativeInkBridge(channel: _testChannel, enabled: true);
      final handler = _RecordingPenHandler(PenTool(id: 'pen'));
      final state = await arm(bridge, handler);
      expect(state, isNotNull);
      expect(bridge.enabled, isTrue);

      await deliverIncoming('armChanged', 'FLUTTER_ONLY');
      // disable() awaits an async invoke to native before returning, but
      // _notifyArmLost runs synchronously before that await.
      expect(handler.armLostCalls, 1);
      expect(bridge.enabled, isFalse);
    },
  );

  test(
    'a nativeFailure for the current generation calls '
    'handleNativeInkArmLost on the last configured PenHandler',
    () async {
      final bridge = NativeInkBridge(channel: _testChannel, enabled: true);
      final handler = _RecordingPenHandler(PenTool(id: 'pen'));
      final state = await arm(bridge, handler);
      expect(state, isNotNull);

      await deliverIncoming('nativeFailure', state!.generation);
      expect(handler.armLostCalls, 1);
      expect(bridge.enabled, isFalse);

      // A failure reported again for the same (now-stale, already-cleared)
      // generation is ignored by the existing generation check, so it must
      // not notify the handler a second time.
      await deliverIncoming('nativeFailure', state.generation);
      expect(handler.armLostCalls, 1);
    },
  );

  test(
    'a non-PenHandler last request is tolerated (no-op) when arm is lost',
    () async {
      final bridge = NativeInkBridge(channel: _testChannel, enabled: true);
      // updateState only treats the handler as a pen when it is a
      // PenHandler; any other Handler is a legitimate (if unusual) caller
      // and must not crash the arm-lost notification.
      final state = await bridge.updateState(
        handler: HandHandler(),
        settings: const ButterflySettings(autosave: false),
        canvasBounds: const Rect.fromLTWH(0, 0, 100, 100),
        devicePixelRatio: 1,
        camera: const CameraTransform(),
      );
      expect(state, isNull); // ineligible: not a PenHandler
      await deliverIncoming('armChanged', 'FLUTTER_ONLY');
    },
  );
}
