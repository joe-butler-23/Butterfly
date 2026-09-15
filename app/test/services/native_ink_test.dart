import 'package:butterfly/cubits/settings.dart';
import 'package:butterfly/cubits/transform.dart';
import 'package:butterfly/handlers/handler.dart';
import 'package:butterfly/services/native_ink.dart';
import 'package:butterfly_api/butterfly_api.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

// A fixed test channel: NativeInkBridge is given this channel directly so no
// real platform plugin is ever touched. Outgoing Flutter -> native calls
// (channel.invokeMethod) are intercepted via setMockMethodCallHandler.
// Incoming native -> Flutter calls (armChanged, nativeFailure) are simulated
// by delivering an encoded platform message to the same channel name, which
// dispatches to whatever handler NativeInkBridge registered with
// channel.setMethodCallHandler in its constructor.
const _testChannel = MethodChannel('test.linwood.dev/native_ink');

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late List<MethodCall> calls;
  late Object? Function(MethodCall call) respond;

  setUp(() {
    calls = <MethodCall>[];
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
        .setMockMethodCallHandler(_testChannel, (call) async {
          calls.add(call);
          return respond(call);
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_testChannel, null);
  });

  PointerDownEvent pointerDown({required int pointer, int micros = 0}) =>
      PointerDownEvent(
        pointer: pointer,
        timeStamp: Duration(microseconds: micros),
      );

  // A configuration that satisfies NativeInkBridge._eligiblePen: a default
  // PenTool/PenProperty on default ButterflySettings input mapping.
  Future<NativeInkState?> arm(
    NativeInkBridge bridge, {
    Rect canvasBounds = const Rect.fromLTWH(0, 0, 100, 100),
  }) {
    return bridge.updateState(
      handler: PenHandler(PenTool(id: 'pen')),
      settings: const ButterflySettings(autosave: false),
      canvasBounds: canvasBounds,
      devicePixelRatio: 1,
      camera: const CameraTransform(),
    );
  }

  // WidgetsBinding.addPostFrameCallback only fires on a pumped frame that was
  // actually scheduled; a bare tester.pump() in a widget-less test does not
  // schedule one on its own, so scheduleFrame() is called explicitly first.
  Future<void> pumpFrame(WidgetTester tester) async {
    WidgetsBinding.instance.scheduleFrame();
    await tester.pump();
  }

  Future<void> deliverIncoming(String method, Object? arguments) async {
    final message = const StandardMethodCodec().encodeMethodCall(
      MethodCall(method, arguments),
    );
    await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .handlePlatformMessage(_testChannel.name, message, (_) {});
  }

  testWidgets(
    'registerStroke returns null when disabled or not configured, '
    'and invokes registerStroke with generation/pointer/timestamp when armed',
    (tester) async {
      // Not available at all (bridge disabled).
      final disabledBridge = NativeInkBridge(
        channel: _testChannel,
        enabled: false,
      );
      expect(disabledBridge.registerStroke(pointerDown(pointer: 1)), isNull);
      expect(calls, isEmpty);

      // Available, but never configured/armed.
      final unconfiguredBridge = NativeInkBridge(
        channel: _testChannel,
        enabled: true,
      );
      expect(
        unconfiguredBridge.registerStroke(pointerDown(pointer: 1)),
        isNull,
      );
      expect(calls, isEmpty);

      // Armed: configure + enable both succeed.
      final bridge = NativeInkBridge(channel: _testChannel, enabled: true);
      final state = await arm(bridge);
      expect(state, isNotNull);
      expect(bridge.enabled, isTrue);
      calls.clear();

      final identity = bridge.registerStroke(
        pointerDown(pointer: 7, micros: 12345),
      );
      expect(identity, isNotNull);
      expect(identity!.generation, state!.generation);

      final registerCall = calls.singleWhere(
        (c) => c.method == 'registerStroke',
      );
      final args = registerCall.arguments as Map;
      expect(args['generation'], state.generation);
      expect(args['pointer'], 7);
      expect(args['sourceTimestampUs'], 12345);
    },
  );

  testWidgets(
    'markFinalStroke returns true for a registered identity and false for '
    'an unknown one',
    (tester) async {
      final bridge = NativeInkBridge(channel: _testChannel, enabled: true);
      await arm(bridge);
      final identity = bridge.registerStroke(pointerDown(pointer: 1))!;
      calls.clear();

      expect(bridge.markFinalStroke(identity, 'element-1', 3), isTrue);

      // An identity that was never registered with this bridge.
      const unknown = (
        generation: 999,
        strokeSequence: 999,
        sourceTimestampUs: 0,
      );
      calls.clear();
      expect(bridge.markFinalStroke(unknown, 'element-2', 3), isFalse);
      // NOTE: markFinalStroke's fallback calls the private _Handoff.cancel
      // directly (native_ink.dart:589) instead of the public cancelStroke()
      // method (native_ink.dart:593-598), so no channel invocation is made
      // for an unmatched identity. This looks like a bug (native is never
      // told to drop a stroke it may still be tracking) but is the actual,
      // current behaviour, so this test asserts what the code does rather
      // than what the docstring comment implies it should do.
      expect(calls, isEmpty);
    },
  );

  testWidgets(
    'acknowledgePaintedElements sends acknowledgeStroke only for finals '
    'whose elementId and pointCount match the receipt, and resets the '
    'consecutive-failure counter',
    (tester) async {
      final bridge = NativeInkBridge(channel: _testChannel, enabled: true);

      final matched = <(int pointer, String elementId, int pointCount)>[
        (1, 'match-a', 3),
        (2, 'match-b', 5),
      ];
      final mismatched = <(int pointer, String elementId, int pointCount)>[
        (3, 'wrong-count', 4), // registered with pointCount 4
        (4, 'not-painted', 2), // never in receipts at all
      ];

      // --- Selective acknowledgement -----------------------------------
      await arm(bridge);
      final identities = <int, NativeInkStrokeIdentity>{};
      for (final s in [...matched, ...mismatched]) {
        identities[s.$1] = bridge.registerStroke(
          pointerDown(pointer: s.$1),
        )!;
      }
      for (final s in matched) {
        expect(
          bridge.markFinalStroke(identities[s.$1]!, s.$2, s.$3),
          isTrue,
        );
      }
      // "wrong-count" stroke reports element id "wrong-count" with a
      // different point count (4) than the receipt below (5) will claim.
      expect(
        bridge.markFinalStroke(identities[3]!, 'wrong-count', 4),
        isTrue,
      );
      // "not-painted" is left pending (never marked final).

      calls.clear();
      bridge.acknowledgePaintedElements(const [
        (elementId: 'match-a', pointCount: 3),
        (elementId: 'match-b', pointCount: 5),
        (elementId: 'wrong-count', pointCount: 5), // count mismatch
      ]);

      final ackCalls = calls
          .where((c) => c.method == 'acknowledgeStroke')
          .toList();
      expect(ackCalls, hasLength(2));
      final ackedIds = ackCalls
          .map((c) => (c.arguments as Map)['finalElementId'])
          .toSet();
      expect(ackedIds, {'match-a', 'match-b'});

      // --- Consecutive-failure counter reset ----------------------------
      // registerStroke only pre-emptively forgives a failed configuration
      // while consecutiveFailures < 3 (native_ink.dart:553). Drive the
      // bridge through 3 full success/fail cycles, acknowledging a real
      // final after every success. If acknowledgePaintedElements resets the
      // counter each time (native_ink.dart:606), the counter can never
      // reach the cap, so a 4th configuration attempt for the very same
      // eligible pen must still call `configure` instead of being silently
      // short-circuited by a stale `_failedState`.
      for (var cycle = 0; cycle < 3; cycle++) {
        final cycleState = await arm(bridge);
        expect(cycleState, isNotNull, reason: 'cycle $cycle should arm');
        final id = bridge.registerStroke(
          pointerDown(pointer: 100 + cycle),
        )!;
        expect(bridge.markFinalStroke(id, 'cycle-$cycle', 2), isTrue);
        bridge.acknowledgePaintedElements([
          (elementId: 'cycle-$cycle', pointCount: 2),
        ]);
        await deliverIncoming('nativeFailure', cycleState!.generation);
        await tester.pump();
        // A real pointer-down after a single failure pre-emptively forgives
        // the failed configuration (native_ink.dart:553), letting the next
        // updateState attempt for the same config run fresh rather than
        // being short-circuited by `_failedState`.
        bridge.registerStroke(pointerDown(pointer: 200 + cycle));
      }

      calls.clear();
      final finalState = await arm(bridge);
      expect(
        finalState,
        isNotNull,
        reason:
            'consecutive failures should have been reset by '
            'acknowledgePaintedElements, so this configuration is not '
            'considered permanently failed',
      );
      expect(calls.map((c) => c.method), contains('configure'));
    },
  );

  testWidgets(
    'registering more than the maximum (16) pending strokes evicts the '
    'oldest, sets overflow, and triggers disable',
    (tester) async {
      final bridge = NativeInkBridge(channel: _testChannel, enabled: true);
      await arm(bridge);

      for (var pointer = 1; pointer <= 16; pointer++) {
        calls.clear();
        final identity = bridge.registerStroke(pointerDown(pointer: pointer));
        expect(identity, isNotNull, reason: 'pointer $pointer should arm');
        expect(
          calls.where((c) => c.method == 'registerStroke'),
          hasLength(1),
        );
      }

      // The 17th pending stroke pushes the handoff past its bound.
      calls.clear();
      final overflowIdentity = bridge.registerStroke(
        pointerDown(pointer: 17),
      );
      expect(overflowIdentity, isNull);
      expect(calls.where((c) => c.method == 'registerStroke'), isEmpty);
      final disableCalls = calls
          .where((c) => c.method == 'disable')
          .toList();
      expect(disableCalls, hasLength(1));

      // disable() clears the arm; the bridge no longer treats itself as
      // enabled or configured.
      await tester.pump();
      expect(bridge.enabled, isFalse);
    },
  );

  testWidgets(
    'cancelStaleFinals keeps the newest two entries and cancels older '
    'unacknowledged finals when a new stroke registers',
    (tester) async {
      final bridge = NativeInkBridge(channel: _testChannel, enabled: true);
      await arm(bridge);

      final identityA = bridge.registerStroke(pointerDown(pointer: 1))!;
      final identityB = bridge.registerStroke(pointerDown(pointer: 2))!;
      expect(bridge.markFinalStroke(identityA, 'element-a', 3), isTrue);

      calls.clear();
      final identityC = bridge.registerStroke(pointerDown(pointer: 3));
      expect(identityC, isNotNull);

      // A (older than the newest two: B and C) had a pending final and must
      // be cancelled towards native.
      final cancelCalls = calls
          .where((c) => c.method == 'cancelStroke')
          .toList();
      expect(cancelCalls, hasLength(1));
      final cancelledArgs = cancelCalls.single.arguments as Map;
      expect(
        cancelledArgs['strokeSequence'],
        identityA.strokeSequence,
      );

      // B (one of the newest two) is untouched even though it has no final
      // yet, and remains registered.
      expect(bridge.markFinalStroke(identityB, 'element-b', 4), isTrue);
      // A is gone from the handoff: re-marking it now fails since it is no
      // longer tracked.
      expect(bridge.markFinalStroke(identityA, 'element-a', 3), isFalse);
    },
  );

  testWidgets(
    "armChanged with 'FLUTTER_ONLY' from native disarms and schedules "
    'reconfiguration without throwing; a second armChanged during the '
    'pending reconfiguration does not double-invoke',
    (tester) async {
      final bridge = NativeInkBridge(channel: _testChannel, enabled: true);
      await arm(bridge);
      expect(bridge.enabled, isTrue);

      // 'FLUTTER_ONLY' tells the bridge native ink is unavailable: it must
      // fully disable without throwing.
      calls.clear();
      await expectLater(
        deliverIncoming('armChanged', 'FLUTTER_ONLY'),
        completes,
      );
      await pumpFrame(tester);
      expect(bridge.enabled, isFalse);
      expect(calls.where((c) => c.method == 'disable'), isNotEmpty);

      // Re-arm so the pending-reconfiguration path (any other armChanged
      // argument) has a `_lastRequest` to replay.
      await arm(bridge);
      expect(bridge.enabled, isTrue);

      // Any armChanged argument other than 'FLUTTER_ONLY' disarms locally
      // and schedules a reconfiguration for after the next frame.
      calls.clear();
      await expectLater(
        deliverIncoming('armChanged', 'REARM_REQUESTED'),
        completes,
      );
      expect(bridge.enabled, isFalse);

      // A second armChanged arriving before the scheduled frame fires must
      // not cause the reconfiguration to run (or invoke configure/enable)
      // twice.
      await expectLater(
        deliverIncoming('armChanged', 'REARM_REQUESTED'),
        completes,
      );

      await pumpFrame(tester);

      expect(
        calls.where((c) => c.method == 'configure'),
        hasLength(1),
        reason: 'the coalesced post-frame reconfiguration runs only once',
      );
      expect(
        calls.where((c) => c.method == 'enable'),
        hasLength(1),
      );
    },
  );

  testWidgets(
    'disable() clears handoff state and invokes disable with the last '
    'generation exactly once',
    (tester) async {
      final bridge = NativeInkBridge(channel: _testChannel, enabled: true);
      final state = await arm(bridge);
      final identity = bridge.registerStroke(pointerDown(pointer: 1))!;
      expect(bridge.markFinalStroke(identity, 'element-1', 2), isTrue);
      expect(bridge.awaitingPaintHandoff, isTrue);

      calls.clear();
      await bridge.disable();

      final disableCalls = calls
          .where((c) => c.method == 'disable')
          .toList();
      expect(disableCalls, hasLength(1));
      expect(
        (disableCalls.single.arguments as Map)['generation'],
        state!.generation,
      );

      expect(bridge.enabled, isFalse);
      expect(bridge.awaitingPaintHandoff, isFalse);

      // A stroke identity that existed before disable() is no longer
      // tracked by the handoff.
      expect(bridge.markFinalStroke(identity, 'element-1', 2), isFalse);
    },
  );
}
