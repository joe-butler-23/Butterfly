part of '../view.dart';

Handler _getViewportHandler(DocumentLoaded state, EditorController cubit) =>
    state is DocumentPresentationState
    ? state.handler
    : cubit.toolCubit.getHandler(
        editable: cubit.saveCubit.state.embedding?.editable != false,
      );

Object? _nativeInkToolKey(ToolRuntimeState state) {
  final handler = state.temporaryHandler ?? state.handler;
  if (handler is! PenHandler) return null;
  final pen = handler.data;
  final property = pen.property;
  return (
    handlerType: handler.runtimeType,
    zoomDependent: pen.zoomDependent,
    shapeDetectionEnabled: pen.shapeDetectionEnabled,
    combinePaths: pen.combinePaths,
    strokeWidth: property.strokeWidth,
    thinning: property.thinning,
    smoothing: property.smoothing,
    streamline: property.streamline,
    paint: property.paint,
    fillPaint: property.fillPaint,
  );
}

bool _nativeInkInputEqual(
  InputConfiguration previous,
  InputConfiguration current,
) =>
    previous.pen == current.pen &&
    const DeepCollectionEquality().equals(
      previous.holdShortcuts,
      current.holdShortcuts,
    ) &&
    previous.doublePenShortcut == current.doublePenShortcut &&
    previous.triplePenShortcut == current.triplePenShortcut &&
    previous.doubleInvertedPenShortcut == current.doubleInvertedPenShortcut &&
    previous.tripleInvertedPenShortcut == current.tripleInvertedPenShortcut &&
    previous.doubleFirstPenButtonShortcut ==
        current.doubleFirstPenButtonShortcut &&
    previous.tripleFirstPenButtonShortcut ==
        current.tripleFirstPenButtonShortcut &&
    previous.doubleSecondPenButtonShortcut ==
        current.doubleSecondPenButtonShortcut &&
    previous.tripleSecondPenButtonShortcut ==
        current.tripleSecondPenButtonShortcut;

bool _nativeInkSettingsChanged(
  ButterflySettings previous,
  ButterflySettings current,
) =>
    previous.ignorePressure != current.ignorePressure ||
    !_nativeInkInputEqual(
      previous.inputConfiguration,
      current.inputConfiguration,
    );

bool _hasPointerManipulator(ToolRuntimeState state) => state
    .toggleableHandlers
    .values
    .any((handler) => handler is PointerManipulationHandler);

class _LoadedViewport extends StatelessWidget {
  const _LoadedViewport({
    required this.bloc,
    required this.state,
    required this.viewportSize,
    required this.input,
    required this.canvasKey,
    required this.nativeInk,
    required this.nativeInkLifecycleActive,
    required this.bake,
    required this.delayBake,
  });

  final DocumentBloc bloc;
  final DocumentLoaded state;
  final Size viewportSize;
  final _ViewportInputCoordinator input;
  final GlobalKey<_ViewportCanvasState> canvasKey;
  final NativeInkBridge? nativeInk;
  final ValueGetter<bool> nativeInkLifecycleActive;
  final VoidCallback bake;
  final VoidCallback delayBake;

  Widget _buildInputSurface(
    BuildContext context,
    EditorController cubit,
    RendererRuntimeState rendererState,
    _HandlerGetter getHandler,
  ) {
    EventContext getEventContext() =>
        input.createEventContext(context, viewportSize, nativeInk: nativeInk);
    final pointerInput = (
      context: context,
      cubit: cubit,
      state: state,
      bloc: bloc,
      getHandler: getHandler,
      getEventContext: getEventContext,
      delayBake: delayBake,
    );

    return GestureDetector(
      onTapUp: (details) async {
        getHandler().onTapUp(details, getEventContext());
        cubit.inputCubit.removeButtons();
      },
      onTapDown: (details) =>
          getHandler().onTapDown(details, getEventContext()),
      onSecondaryTapUp: (details) =>
          getHandler().onSecondaryTapUp(details, getEventContext()),
      onScaleStart: (details) => input.handleScaleStart(details, pointerInput),
      onScaleUpdate: (details) =>
          input.handleScaleUpdate(details, pointerInput),
      onScaleEnd: (details) => input.handleScaleEnd(details, pointerInput),
      onLongPressDown: (details) =>
          getHandler().onLongPressDown(details, getEventContext()),
      onLongPressStart: (details) =>
          getHandler().onLongPressStart(details, getEventContext()),
      onLongPressEnd: (details) =>
          getHandler().onLongPressEnd(details, getEventContext()),
      child: Listener(
        behavior: .translucent,
        onPointerSignal: (event) =>
            input.handlePointerSignal(event, pointerInput),
        onPointerPanZoomStart: (_) => input.beginTrackpadGesture(),
        onPointerDown: (event) => input.handlePointerDown(event, pointerInput),
        onPointerMove: (event) => input.handlePointerMove(event, pointerInput),
        onPointerUp: (event) => input.handlePointerUp(event, pointerInput),
        onPointerCancel: (event) =>
            input.handlePointerCancel(event, pointerInput),
        onPointerHover: (event) {
          cubit.inputCubit.updateLastPosition(event.localPosition);
          getHandler().onPointerHover(event, getEventContext());
        },
        child: _ViewportCanvas(
          key: canvasKey,
          rendererState: rendererState,
          documentState: state,
          delayBake: delayBake,
          onForegroundPaintedElements: nativeInk == null
              ? null
              : (receipts) {
                  final bridge = nativeInk;
                  if (bridge == null || !bridge.needsForegroundRetirement) {
                    return;
                  }
                  final painted = receipts.toList(growable: false);
                  WidgetsBinding.instance.addPostFrameCallback((_) {
                    if (!context.mounted) return;
                    bridge.acknowledgePaintedElements(painted);
                    if (!getHandler().onForegroundPaintedElements(painted)) {
                      return;
                    }
                    unawaited(bloc.refreshForegroundsOnly());
                  });
                },
        ),
      ),
    );
  }

  void _scheduleNativeInk(
    BuildContext context,
    EditorController cubit,
    _HandlerGetter getHandler,
  ) {
    final nativeInk = this.nativeInk;
    if (nativeInk == null) return;
    nativeInk.scheduleAfterFrame(() {
      if (!context.mounted || !nativeInkLifecycleActive()) return;
      final renderObject = canvasKey.currentContext?.findRenderObject();
      if (renderObject is! RenderBox || !renderObject.hasSize) {
        unawaited(nativeInk.disable());
        return;
      }
      Rect bounds;
      try {
        bounds = Rect.fromPoints(
          renderObject.localToGlobal(Offset.zero),
          renderObject.localToGlobal(
            renderObject.size.bottomRight(Offset.zero),
          ),
        );
      } catch (_) {
        unawaited(nativeInk.disable());
        return;
      }
      final currentState = context.read<DocumentBloc>().state;
      final handler = currentState is DocumentLoaded
          ? _getViewportHandler(currentState, cubit)
          : getHandler();
      final hasPointerManipulator = _hasPointerManipulator(
        cubit.toolCubit.state,
      );
      final allowed =
          currentState is DocumentLoadSuccess &&
          currentState.currentArea == null &&
          currentState.currentAreaName.isEmpty &&
          !hasPointerManipulator &&
          cubit.saveCubit.state.embedding?.editable != false;
      unawaited(
        nativeInk.updateState(
          handler: handler,
          settings: context.read<SettingsCubit>().state,
          canvasBounds: bounds,
          devicePixelRatio: MediaQuery.devicePixelRatioOf(context),
          camera: cubit.transformCubit.state,
          allowNativeInk: allowed,
        ),
      );
    });
  }

  @override
  Widget build(BuildContext context) {
    final cubit = context.read<EditorController>();
    Handler getHandler() => _getViewportHandler(state, cubit);
    if (nativeInk != null) {
      _scheduleNativeInk(context, cubit, getHandler);
    }

    final viewport = BlocBuilder<RendererCubit, RendererRuntimeState>(
      buildWhen: (previous, current) =>
          previous.cameraViewport != current.cameraViewport ||
          previous.rendererStates != current.rendererStates ||
          previous.temporaryRendererStates != current.temporaryRendererStates,
      builder: (context, rendererState) =>
          BlocBuilder<ToolCubit, ToolRuntimeState>(
            buildWhen: (previous, current) =>
                previous.handler != current.handler ||
                previous.temporaryHandler != current.temporaryHandler ||
                previous.cursor != current.cursor ||
                previous.temporaryCursor != current.temporaryCursor,
            builder: (context, toolState) {
              final realSize = rendererState.cameraViewport.toRealSize();
              final viewportMatches =
                  (realSize.width - viewportSize.width).abs() < 2 &&
                  (realSize.height - viewportSize.height).abs() < 2;
              if (state is DocumentLoadSuccess && !viewportMatches) bake();
              return Actions(
                actions: getHandler().getActions(context),
                child: DefaultTextEditingShortcuts(
                  child: Focus(
                    child: MouseRegion(
                      cursor: toolState.currentCursor,
                      child: Builder(
                        builder: (context) => _buildInputSurface(
                          context,
                          cubit,
                          rendererState,
                          getHandler,
                        ),
                      ),
                    ),
                  ),
                ),
              );
            },
          ),
    );
    if (nativeInk == null) return viewport;
    return MultiBlocListener(
      listeners: [
        BlocListener<TransformCubit, CameraTransform>(
          listenWhen: (previous, current) => previous.size != current.size,
          listener: (context, _) {
            nativeInk?.disarm();
            _scheduleNativeInk(context, cubit, getHandler);
          },
        ),
        BlocListener<SettingsCubit, ButterflySettings>(
          listenWhen: _nativeInkSettingsChanged,
          listener: (context, _) {
            nativeInk?.disarm();
            _scheduleNativeInk(context, cubit, getHandler);
          },
        ),
        BlocListener<ToolCubit, ToolRuntimeState>(
          listenWhen: (previous, current) =>
              _nativeInkToolKey(previous) != _nativeInkToolKey(current) ||
              _hasPointerManipulator(previous) !=
                  _hasPointerManipulator(current),
          listener: (context, _) {
            nativeInk?.disarm();
            _scheduleNativeInk(context, cubit, getHandler);
          },
        ),
      ],
      child: viewport,
    );
  }
}
