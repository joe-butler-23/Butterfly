import 'dart:async';

import 'package:collection/collection.dart';
import 'package:butterfly/bloc/document_bloc.dart';
import 'package:butterfly/cubits/editor_controller.dart';
import 'package:butterfly/cubits/settings.dart';
import 'package:butterfly/cubits/transform.dart';
import 'package:butterfly/handlers/handler.dart';
import 'package:butterfly/services/native_ink.dart';
import 'package:butterfly/services/pointer_shortcuts.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../view_painter.dart';

part 'view/canvas.dart';
part 'view/content.dart';
part 'view/input.dart';

class MainViewViewport extends StatefulWidget {
  const MainViewViewport({super.key});

  @override
  MainViewViewportState createState() => MainViewViewportState();
}

typedef _HandlerGetter = Handler Function();
typedef _EventContextGetter = EventContext Function();
typedef _PointerInputContext = ({
  BuildContext context,
  EditorController cubit,
  DocumentLoaded state,
  DocumentBloc bloc,
  _HandlerGetter getHandler,
  _EventContextGetter getEventContext,
  VoidCallback delayBake,
});

class MainViewViewportState extends State<MainViewViewport>
    with WidgetsBindingObserver {
  final GlobalKey<_ViewportCanvasState> _canvasKey = GlobalKey();
  late final _ViewportInputCoordinator _input;
  NativeInkBridge? _nativeInk;
  Future<void>? _nativeInkTeardown;
  bool _nativeInkLifecycleActive = true;
  int _nativeInkLifecycleEpoch = 0;
  bool _initialBakePending = false;

  void openContextMenu() {
    final bloc = context.read<DocumentBloc>();
    final state = bloc.state;
    if (state is! DocumentLoaded) return;
    final cubit = context.read<EditorController>();
    final handler = _getViewportHandler(state, cubit);
    final renderBox = context.findRenderObject();
    if (renderBox is! RenderBox) return;
    final position =
        cubit.inputCubit.state.lastPosition ??
        renderBox.size.center(Offset.zero);
    handler.onContextMenu(
      position,
      EventContext(
        context,
        renderBox.size,
        HardwareKeyboard.instance.isShiftPressed,
        HardwareKeyboard.instance.isAltPressed,
        HardwareKeyboard.instance.isControlPressed,
      ),
    );
  }

  Future<void> _bake(Size viewportSize) => context.read<DocumentBloc>().bake(
    viewportSize: viewportSize,
    pixelRatio: MediaQuery.devicePixelRatioOf(context),
  );

  void _scheduleInitialBake(Size viewportSize) {
    if (_initialBakePending) return;
    _initialBakePending = true;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      try {
        if (mounted) await _bake(viewportSize);
      } finally {
        _initialBakePending = false;
      }
    });
  }

  void _delayBake(Size viewportSize) =>
      context.read<DocumentBloc>().delayedBake(
        viewportSize: viewportSize,
        pixelRatio: MediaQuery.devicePixelRatioOf(context),
        testTransform: false,
      );

  @override
  void initState() {
    super.initState();
    if (nativeInkLabEnabled) _nativeInk = NativeInkBridge();
    _input = _ViewportInputCoordinator((transformCubit) {
      _canvasKey.currentState?.settleSlide(transformCubit);
    });
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    if (nativeInkLabEnabled) {
      _nativeInk?.disarm();
      unawaited(_nativeInk?.dispose());
    }
    _input.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (nativeInkLabEnabled) {
      final epoch = ++_nativeInkLifecycleEpoch;
      if (state == AppLifecycleState.resumed) {
        final teardown = _nativeInkTeardown;
        if (teardown == null) {
          _nativeInkLifecycleActive = true;
          if (mounted) setState(() {});
        } else {
          _nativeInkLifecycleActive = false;
          unawaited(
            teardown.whenComplete(() {
              if (mounted && epoch == _nativeInkLifecycleEpoch) {
                _nativeInkLifecycleActive = true;
                setState(() {});
              }
            }),
          );
        }
        return;
      }
      _nativeInkLifecycleActive = false;
      _nativeInk?.disarm();
      _nativeInkTeardown = _nativeInk?.disable();
    } else if (state == AppLifecycleState.resumed) {
      return;
    }
    final bloc = context.read<DocumentBloc>();
    if (bloc.state is! DocumentLoadSuccess) return;
    _input.reset();
    final controller = context.read<EditorController>();
    controller.toolCubit.resetInput(bloc, controller.inputCubit);
    unawaited(bloc.save());
  }

  @override
  Widget build(BuildContext context) {
    return SizedBox.expand(
      child: RepaintBoundary(
        child: LayoutBuilder(
          builder: (context, constraints) {
            final viewportSize = constraints.biggest;
            final bloc = context.read<DocumentBloc>();

            return BlocBuilder<DocumentBloc, DocumentState>(
              builder: (context, state) {
                if (state is! DocumentLoaded) {
                  _nativeInk?.scheduleAfterFrame(
                    () => unawaited(_nativeInk?.disable()),
                  );
                  return const Center(child: CircularProgressIndicator());
                }
                return _LoadedViewport(
                  bloc: bloc,
                  state: state,
                  viewportSize: viewportSize,
                  input: _input,
                  canvasKey: _canvasKey,
                  nativeInk: _nativeInk,
                  nativeInkLifecycleActive: () => _nativeInkLifecycleActive,
                  bake: () => _scheduleInitialBake(viewportSize),
                  delayBake: () => _delayBake(viewportSize),
                );
              },
            );
          },
        ),
      ),
    );
  }
}
