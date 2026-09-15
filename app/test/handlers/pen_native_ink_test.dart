// Tests for the native-ink move-refresh optimisation in PenHandler.addPoint
// (app/lib/handlers/pen.dart): while a stroke is native-owned, Flutter's own
// foreground preview must not be repainted on every pointer move (that work
// is redundant -- native already draws the wet stroke on its own overlay --
// and was found to be contributing to render-thread stalls). The refresh
// must resume immediately for Flutter-owned strokes and once native ink
// loses its arm mid-stroke (PenHandler.handleNativeInkArmLost).
//
// PenHandler.addPoint reads DocumentBloc/EditorController/TransformCubit/
// SettingsCubit straight off the BuildContext it is given (not through
// EventContext's own convenience getters, unlike most other handlers), so
// exercising it needs a real widget tree with real providers rather than a
// fully mocked EventContext. DocumentBloc itself (and the DocumentLoadSuccess
// it reports) are mocked since constructing a real one needs a document,
// file system and window cubit unrelated to this test; every other
// dependency (TransformCubit, SettingsCubit, EditorController and its
// internal cubits) is real, which sidesteps having to stub the (potentially
// large) surface those internals touch on each other.
import 'package:butterfly/bloc/document_bloc.dart';
import 'package:butterfly/cubits/editor_controller.dart';
import 'package:butterfly/cubits/settings.dart';
import 'package:butterfly/cubits/transform.dart';
import 'package:butterfly/handlers/handler.dart';
import 'package:butterfly/models/viewport.dart';
import 'package:butterfly_api/butterfly_api.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _MockDocumentBloc extends Mock implements DocumentBloc {}

class _MockDocumentLoadSuccess extends Mock implements DocumentLoadSuccess {}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() {
    registerFallbackValue(Offset.zero);
    registerFallbackValue(ElementsCreated(const []));
  });

  group('PenHandler native-ink move refresh gating', () {
    late _MockDocumentBloc bloc;
    late TransformCubit transformCubit;
    late SettingsCubit settingsCubit;
    late EditorController editorController;
    late PenHandler handler;
    late BuildContext buildContext;
    late int refreshCount;
    var nextPointer = 1;

    setUp(() async {
      bloc = _MockDocumentBloc();
      final state = _MockDocumentLoadSuccess();
      when(() => state.currentCollection).thenReturn('');
      when(() => bloc.state).thenReturn(state);
      when(() => bloc.isInBounds(any())).thenReturn(true);
      refreshCount = 0;
      when(() => bloc.delayedRefreshForegroundsOnly()).thenAnswer((_) async {
        refreshCount++;
      });
      when(() => bloc.refreshForegroundsOnly()).thenAnswer((_) async {});
      when(() => bloc.add(any())).thenReturn(null);
      when(() => bloc.stream).thenAnswer((_) => const Stream.empty());

      transformCubit = TransformCubit(1);
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();
      settingsCubit = SettingsCubit(prefs);
      editorController = EditorController(
        settingsCubit,
        transformCubit,
        const CameraViewport.unbaked(),
      );
      // A fresh pointer id per test avoids collisions with lastPosition
      // bookkeeping left over from a previous case in the same handler.
      handler = PenHandler(PenTool(id: 'pen'));
    });

    tearDown(() {
      editorController.rendererCubit.close();
      editorController.toolCubit.close();
      editorController.inputCubit.close();
      editorController.saveCubit.close();
      editorController.viewCubit.close();
      transformCubit.close();
      settingsCubit.close();
    });

    Future<void> pump(WidgetTester tester) => tester.pumpWidget(
      MultiBlocProvider(
        providers: [
          BlocProvider<DocumentBloc>.value(value: bloc),
          BlocProvider<TransformCubit>.value(value: transformCubit),
          BlocProvider<SettingsCubit>.value(value: settingsCubit),
        ],
        child: RepositoryProvider<EditorController>.value(
          value: editorController,
          child: MaterialApp(
            home: Builder(
              builder: (context) {
                buildContext = context;
                return const SizedBox();
              },
            ),
          ),
        ),
      ),
    );

    EventContext eventContext({
      NativeInkStrokeIdentity? Function(PointerDownEvent event)? register,
      bool Function(NativeInkStrokeIdentity identity, String elementId, int pointCount)?
      finalize,
      void Function(NativeInkStrokeIdentity identity)? cancel,
    }) => EventContext(
      buildContext,
      const Size(400, 400),
      false,
      false,
      false,
      registerNativeInkStroke: register,
      markNativeInkFinalStroke: finalize,
      cancelNativeInkStroke: cancel,
    );

    PointerDownEvent down(int pointer, Offset position) => PointerDownEvent(
      pointer: pointer,
      position: position,
      kind: PointerDeviceKind.stylus,
      buttons: kStylusContact,
    );

    PointerMoveEvent move(int pointer, Offset position) => PointerMoveEvent(
      pointer: pointer,
      position: position,
      kind: PointerDeviceKind.stylus,
    );

    testWidgets(
      'a native-owned stroke does not schedule a foreground refresh on move',
      (tester) async {
        await pump(tester);
        final pointer = nextPointer++;
        final context = eventContext(
          register: (_) => (
            generation: 1,
            strokeSequence: 1,
            sourceTimestampUs: 0,
          ),
          finalize: (_, _, _) => true,
          cancel: (_) {},
        );

        handler.onPointerDown(down(pointer, const Offset(0, 0)), context);
        // The very first point of the stroke is painted before native
        // ownership is known, so it always refreshes once.
        expect(refreshCount, 1);

        handler.onPointerMove(move(pointer, const Offset(5, 5)), context);
        handler.onPointerMove(move(pointer, const Offset(10, 10)), context);
        expect(
          refreshCount,
          1,
          reason:
              'native owns the wet stroke; Flutter must not repaint its '
              'own foreground preview on every move as well',
        );
        expect(handler.elements[pointer]?.points, hasLength(3));
      },
    );

    testWidgets('a Flutter-owned stroke still refreshes on every move', (
      tester,
    ) async {
      await pump(tester);
      final pointer = nextPointer++;
      // registerNativeInkStroke is provided but declines to hand back an
      // identity (as it would once the pen is ineligible for native ink,
      // or nativeInk is simply unavailable), so the stroke stays Flutter-
      // owned end to end.
      final context = eventContext(
        register: (_) => null,
        finalize: (_, _, _) => true,
        cancel: (_) {},
      );

      handler.onPointerDown(down(pointer, const Offset(0, 0)), context);
      expect(refreshCount, 1);

      handler.onPointerMove(move(pointer, const Offset(5, 5)), context);
      expect(refreshCount, 2);
      handler.onPointerMove(move(pointer, const Offset(10, 10)), context);
      expect(refreshCount, 3);
    });

    testWidgets(
      'a native fallback mid-stroke (handleNativeInkArmLost) resumes the '
      'foreground refresh and paints the accumulated points',
      (tester) async {
        await pump(tester);
        final pointer = nextPointer++;
        var cancelled = false;
        final context = eventContext(
          register: (_) => (
            generation: 1,
            strokeSequence: 1,
            sourceTimestampUs: 0,
          ),
          finalize: (_, _, _) => true,
          cancel: (_) => cancelled = true,
        );

        handler.onPointerDown(down(pointer, const Offset(0, 0)), context);
        handler.onPointerMove(move(pointer, const Offset(5, 5)), context);
        expect(
          refreshCount,
          1,
          reason: 'still native-owned so far, matching the previous case',
        );

        // Simulate NativeInkBridge telling this handler that native fell
        // back mid-stroke (armChanged -> FLUTTER_ONLY, or a native
        // failure): see NativeInkBridge._notifyArmLost in native_ink.dart.
        handler.handleNativeInkArmLost();
        expect(
          refreshCount,
          2,
          reason:
              'losing native ownership must paint the points accumulated '
              'while native was drawing in exactly one refresh',
        );
        // handleNativeInkArmLost routes through the existing
        // _cancelNativeInk cleanup, which always calls the stored
        // canceller; telling native to cancel an identity it already
        // dropped itself (the whole reason arm was lost) is a harmless
        // no-op there (NativeInkBridge.cancelStroke finds nothing to
        // cancel once the handoff was cleared), so this stays true.
        expect(cancelled, isTrue);

        handler.onPointerMove(move(pointer, const Offset(15, 15)), context);
        expect(
          refreshCount,
          3,
          reason: 'the pointer is Flutter-owned again, so moves resume',
        );
      },
    );
  });
}
