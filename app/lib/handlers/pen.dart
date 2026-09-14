part of 'handler.dart';

// This class represents the handler for the PenTool.
class PenHandler extends Handler<PenTool> with ColoredHandler {
  bool _hideCursorWhileDrawing = false;
  // Map to store the PenElements.
  final Map<int, PenElement> elements = {};
  final Map<int, List<PathPoint>> _elementPoints = {};
  final List<PenElement> _submittedElements = [];
  final Set<String> _nativeOwnedElementIds = {};
  final Map<int, bool Function(String elementId, int pointCount)>?
  _nativeInkFinalizers = nativeInkLabEnabled ? {} : null;
  final Map<int, VoidCallback>? _nativeInkCancellers = nativeInkLabEnabled
      ? {}
      : null;
  // Map to store the last positions of each element.
  final Map<int, Offset> lastPosition = {};
  // List for shapeDetection
  final points = <Offset>[];
  // For control if the pointer has not moved
  bool isDrawing = false;
  // Timer to initiate shape detection after a period of inactivity
  Timer? _positionCheckTimer;
  // Variable to store the current position of the pointer
  Offset? lastPosit;
  Offset? localPos;

  PenHandler(super.data);

  void _clearShapeDetection() {
    _positionCheckTimer?.cancel();
    _positionCheckTimer = null;
    if (points.isNotEmpty) points.clear();
  }

  void _cancelNativeInk(int pointer) {
    _nativeInkCancellers?.remove(pointer)?.call();
    _nativeInkFinalizers?.remove(pointer);
  }

  // Create foregrounds for rendering the PenRendere
  @override
  List<Renderer> createForegrounds(
    EditorController editorController,
    NoteData document,
    DocumentPage page,
    DocumentInfo info, [
    Area? currentArea,
  ]) => [...elements.values, ..._submittedElements]
      .map((e) => e.points.length > 1 ? PenRenderer(e) : null)
      .whereType<Renderer>()
      .toList();

  // Reset the input for the handler.
  @override
  void resetInput(DocumentBloc bloc) {
    for (final pointer
        in _nativeInkCancellers?.keys.toList() ?? const <int>[]) {
      _cancelNativeInk(pointer);
    }
    submitElements(bloc, elements.keys.toList());
    _clearShapeDetection();
    elements.clear();
    _elementPoints.clear();
    lastPosition.clear();
    lastPosit = null;
  }

  // Handle the pointer release event.
  @override
  void onPointerUp(PointerUpEvent event, EventContext context) {
    isDrawing = false;
    // Cancel shape detection before finalizing the stroke.
    _clearShapeDetection();
    addPoint(
      context.buildContext,
      event.pointer,
      event.localPosition,
      context.viewportSize,
      getPressureOfEvent(event),
      event.kind,
      refresh: false,
    );
    lastPosition.remove(event.pointer);
    submitElements(context.getDocumentBloc(), [event.pointer]);
    lastPosit = null;
  }

  @override
  void onPointerCancel(PointerCancelEvent event, EventContext context) {
    _clearShapeDetection();
    _cancelNativeInk(event.pointer);
    elements.remove(event.pointer);
    _elementPoints.remove(event.pointer);
    lastPosition.remove(event.pointer);
    isDrawing = elements.isNotEmpty;
    lastPosit = null;
    unawaited(context.refreshForegrounds());
  }

  DocumentBloc? _bloc;

  // Submit elements for processing and rendering.
  Future<void> submitElements(DocumentBloc bloc, List<int> indexes) async {
    _bloc = bloc;
    final submitted = <(int, PenElement)>[];
    for (final pointer in indexes) {
      final element = elements.remove(pointer);
      final points = _elementPoints.remove(pointer);
      if (element == null) continue;
      submitted.add((
        pointer,
        points == null
            ? element
            : element.copyWith(points: List<PathPoint>.of(points)),
      ));
    }
    final submittedElements = submitted.map((entry) => entry.$2).toList();
    if (submittedElements.isEmpty) return;
    _submittedElements.addAll(submittedElements);
    for (final (pointer, element) in submitted) {
      final cancel = _nativeInkCancellers?.remove(pointer);
      final finalize = _nativeInkFinalizers?.remove(pointer);
      final id = element.id;
      final nativeOwned =
          finalize != null &&
          id != null &&
          element.points.length > 1 &&
          finalize(id, element.points.length);
      if (nativeOwned) {
        _nativeOwnedElementIds.add(id);
      } else {
        cancel?.call();
      }
    }
    lastPosition.removeWhere((key, value) => indexes.contains(key));
    bloc.add(ElementsCreated(submittedElements));
    unawaited(bloc.refreshForegroundsOnly());
  }

  @override
  bool onRenderersCreated(DocumentPage page, List<Renderer> renderers) {
    if (_submittedElements.isEmpty) return false;
    final createdIds = renderers
        .map((renderer) => renderer.element)
        .whereType<PenElement>()
        .map((element) => element.id)
        .nonNulls
        .toSet();
    if (createdIds.isEmpty) return false;
    final previousLength = _submittedElements.length;
    _submittedElements.removeWhere(
      (element) =>
          createdIds.contains(element.id) &&
          !_nativeOwnedElementIds.contains(element.id),
    );
    final changed = previousLength != _submittedElements.length;
    if (changed && _submittedElements.isEmpty && elements.isEmpty) {
      unawaited(_bloc?.delayedBake());
    }
    return changed;
  }

  @override
  bool onForegroundPaintedElements(
    Iterable<({String elementId, int? pointCount})> elements,
  ) {
    if (_nativeOwnedElementIds.isEmpty || _submittedElements.isEmpty) {
      return false;
    }
    final painted = <String, Set<int>>{};
    for (final receipt in elements) {
      if (!_nativeOwnedElementIds.contains(receipt.elementId)) continue;
      final pointCount = receipt.pointCount;
      if (pointCount != null) {
        (painted[receipt.elementId] ??= {}).add(pointCount);
      }
    }
    final previousLength = _submittedElements.length;
    _submittedElements.removeWhere((element) {
      final id = element.id;
      final removed =
          id != null && (painted[id]?.contains(element.points.length) ?? false);
      if (removed) _nativeOwnedElementIds.remove(id);
      return removed;
    });
    final changed = previousLength != _submittedElements.length;
    if (changed && _submittedElements.isEmpty && this.elements.isEmpty) {
      unawaited(_bloc?.delayedBake());
    }
    return changed;
  }

  @override
  Future<void> onViewportUpdated(
    CameraViewport currentViewport,
    CameraViewport newViewport,
  ) async {
    if (_submittedElements.isEmpty || nativeInkLabEnabled) return;
    final submittedIds = _submittedElements.map((e) => e.id).nonNulls.toSet();
    final viewportIds = [
      ...newViewport.bakedElements,
      ...newViewport.unbakedElements,
    ].map((renderer) => renderer.element.id).nonNulls.toSet();
    // Renderer creation is the authoritative handoff from the foreground
    // preview to the document. A rapid sequence can produce an intermediate
    // viewport containing only some submitted strokes; clearing all previews
    // then makes the remaining strokes disappear until their events finish.
    if (submittedIds.intersection(viewportIds).isNotEmpty) return;
    _submittedElements.clear();
    await _bloc?.refresh(allowBake: false);
    // If already started a new element, we don't bake yet
    if (elements.isEmpty) {
      await _bloc?.delayedBake();
    }
  }

  // Add a point to the element.
  void addPoint(
    BuildContext context,
    int pointer,
    Offset localPos,
    Size viewportSize,
    double pressure,
    PointerDeviceKind kind, {
    bool refresh = true,
    bool shouldCreate = false,
  }) {
    final bloc = context.read<DocumentBloc>();
    final editorController = context.read<EditorController>();
    final transform = context.read<TransformCubit>().state;
    localPos = PointerManipulationHandler.calculatePointerPosition(
      editorController.toolCubit.state,
      localPos,
      viewportSize,
      transform,
    );
    final globalPos = transform.localToGlobal(localPos);
    if (!bloc.isInBounds(globalPos)) return;
    final state = bloc.state as DocumentLoadSuccess;
    final settings = context.read<SettingsCubit>().state;
    final penOnlyInput = editorController.inputCubit.effectivePenOnlyInput;
    if (lastPosition[pointer] == localPos) return;
    lastPosition[pointer] = localPos;
    if (penOnlyInput && (kind != .stylus && kind != .invertedStylus)) {
      return;
    }
    if (settings.ignorePressure == .always) {
      pressure = 1;
    }
    double zoom = data.zoomDependent ? transform.size : 1;
    final createNew = !elements.containsKey(pointer);
    if (createNew && !shouldCreate) return;
    final element = elements[pointer];
    final point = PathPoint.fromPoint(globalPos.toPoint(), pressure);
    if (element != null) {
      final points = _elementPoints[pointer] ?? element.points.toList();
      points.add(point);
      if (points.length == 2 && settings.ignorePressure == .first) {
        points[0] = points[0].copyWith(pressure: pressure);
      }
      _elementPoints[pointer] = points;
      elements[pointer] = element.copyWith(points: points);
    } else {
      final points = [point];
      _elementPoints[pointer] = points;
      elements[pointer] = PenElement(
        id: createUniqueId(),
        zoom: transform.size,
        combineId: data.combinePaths ? data.id : null,
        collection: state.currentCollection,
        property: data.property.copyWith(
          strokeWidth: data.property.strokeWidth / zoom,
        ),
        points: points,
      );
    }
    if (refresh) unawaited(bloc.delayedRefreshForegroundsOnly());
  }

  // This function is called when the pointer is pressed down.
  @override
  void onPointerDown(PointerDownEvent event, EventContext context) {
    final cubit = context.getEditorController();
    cubit.rendererCubit.cancelDelayedBake();
    isDrawing = true;
    changeStartedDrawing(context);
    _hideCursorWhileDrawing = context.getSettings().hideCursorWhileDrawing;
    if (cubit.inputCubit.moveEnabled && event.kind != .stylus) {
      elements.clear();
      context.refreshForegrounds();
      return;
    }
    elements.remove(event.pointer);
    _elementPoints.remove(event.pointer);
    addPoint(
      context.buildContext,
      event.pointer,
      event.localPosition,
      context.viewportSize,
      getPressureOfEvent(event),
      event.kind,
      shouldCreate: true,
    );
    final register = context.registerNativeInkStroke;
    final finalize = context.markNativeInkFinalStroke;
    if (elements.containsKey(event.pointer) &&
        event.kind == PointerDeviceKind.stylus &&
        event.buttons & ~kStylusContact == 0 &&
        !context.isShiftPressed &&
        !context.isAltPressed &&
        !context.isCtrlPressed &&
        register != null &&
        finalize != null) {
      final identity = register(event);
      if (identity != null) {
        _nativeInkFinalizers?[event.pointer] = (elementId, pointCount) =>
            finalize(identity, elementId, pointCount);
        final cancel = context.cancelNativeInkStroke;
        if (cancel != null) {
          _nativeInkCancellers?[event.pointer] = () => cancel(identity);
        }
      }
    }
  }

  @override
  void onPointerMove(PointerMoveEvent event, EventContext context) {
    if (!isDrawing) return;
    // Check if the pointer has not moved
    if (lastPosit == event.localPosition) return;
    // Update the current position with the new position of the pointer
    lastPosit = event.localPosition;
    if (data.shapeDetectionEnabled) {
      _positionCheckTimer?.cancel();
      _positionCheckTimer = Timer(
        Duration(milliseconds: (data.shapeDetectionTime * 1000).round()),
        () {
          _positionCheckTimer = null;
          _tickShapeDetection(event.pointer, context, event.localPosition);
        },
      );
      points.add(event.localPosition);
    } else {
      _clearShapeDetection();
    }
    // Call the addPoint function to add a point to the current brush stroke.
    addPoint(
      context.buildContext,
      event.pointer,
      event.localPosition,
      context.viewportSize,
      getPressureOfEvent(event),
      event.kind,
    );
  }

  void showMessage(EventContext context, String recognizedShape) {
    // show SnackBar with recognized shape
    ScaffoldMessenger.of(context.buildContext).showSnackBar(
      SnackBar(
        width: MediaQuery.sizeOf(context.buildContext).width * 0.1,
        behavior: SnackBarBehavior.floating,
        content: Text(textAlign: .center, recognizedShape),
        duration: const Duration(milliseconds: 300),
      ),
    );
  }

  // Detects shapes and draws them
  void _tickShapeDetection(
    int pointer,
    EventContext context,
    Offset localPosition,
  ) {
    if (!data.shapeDetectionEnabled) {
      _clearShapeDetection();
      return;
    }
    final element = elements[pointer];
    if (element == null || points.length > 600 || points.isEmpty) {
      return;
    }

    final transform = context.getCameraTransform();
    final creationTransform = CameraTransform(
      transform.pixelRatio,
      transform.position.rotate(Offset.zero, transform.rotation),
      transform.size,
    );
    // Create recognizeUnistroke
    final recognized = recognizeUnistroke(points);
    final state = context.getState();
    final currentCollection = state!.currentCollection;

    if (recognized == null) {
      return;
    }
    PadElement? shapeElement;
    switch (recognized.name) {
      case .line:
        double startX = points.first.dx;
        double startY = points.first.dy;
        double endX = points.last.dx;
        double endY = points.last.dy;

        Point<double> firstPosition = Point(startX, startY);
        Point<double> secondPosition = Point(endX, endY);

        // Convert coordinates from the document coordinate system to the view coordinate system
        Offset firstPositionInView = creationTransform.localToGlobal(
          firstPosition.toOffset(),
        );
        Offset secondPositionInView = creationTransform.localToGlobal(
          secondPosition.toOffset(),
        );

        // Create new shape element
        shapeElement = PadElement.shape(
          id: createUniqueId(),
          firstPosition: firstPositionInView.toPoint(),
          secondPosition: secondPositionInView.toPoint(),
          property: ShapeProperty(
            shape: const LineShape(),
            paint: data.property.paint,
            strokeWidth: data.property.strokeWidth,
          ),
          collection: currentCollection,
        );

        // Show dialog
        showMessage(context, AppLocalizations.of(context.buildContext).line);
      case .circle:
        // Calculate the center of the circle as the average of the points
        double centerX =
            points.map((p) => p.dx).reduce((a, b) => a + b) / points.length;
        double centerY =
            points.map((p) => p.dy).reduce((a, b) => a + b) / points.length;
        Offset center = .new(centerX, centerY);

        // Calculate the radius as the average of the distances of the points from the center
        double radius =
            points.map((p) => (p - center).distance).reduce((a, b) => a + b) /
            points.length;

        Point<double> firstPosition = Point<double>(
          center.dx - radius,
          center.dy - radius,
        );
        Point<double> secondPosition = Point<double>(
          center.dx + radius,
          center.dy + radius,
        );

        // Convert coordinates from the document coordinate system to the view coordinate system
        Offset firstPositionInView = creationTransform.localToGlobal(
          firstPosition.toOffset(),
        );
        Offset secondPositionInView = creationTransform.localToGlobal(
          secondPosition.toOffset(),
        );

        // Create new ShapeElement
        shapeElement = PadElement.shape(
          id: createUniqueId(),
          firstPosition: firstPositionInView.toPoint(),
          secondPosition: secondPositionInView.toPoint(),
          property: ShapeProperty(
            shape: const CircleShape(),
            paint: data.property.paint,
            strokeWidth: data.property.strokeWidth,
          ),
          collection: currentCollection,
        );

        // Show dialog
        showMessage(context, AppLocalizations.of(context.buildContext).circle);
      case .rectangle:
        double minX = points.map((p) => p.dx).reduce(min);
        double maxX = points.map((p) => p.dx).reduce(max);
        double minY = points.map((p) => p.dy).reduce(min);
        double maxY = points.map((p) => p.dy).reduce(max);

        Point<double> firstPosition = Point(minX, minY);
        Point<double> secondPosition = Point(maxX, maxY);

        // Convert coordinates from the document coordinate system to the view coordinate system
        Offset firstPositionInView = creationTransform.localToGlobal(
          firstPosition.toOffset(),
        );
        Offset secondPositionInView = creationTransform.localToGlobal(
          secondPosition.toOffset(),
        );

        // Create new ShapeElement
        shapeElement = PadElement.shape(
          id: createUniqueId(),
          firstPosition: firstPositionInView.toPoint(),
          secondPosition: secondPositionInView.toPoint(),
          property: ShapeProperty(
            shape: const RectangleShape(),
            paint: data.property.paint,
            strokeWidth: data.property.strokeWidth,
          ),
          collection: currentCollection,
        );
      case .triangle:
        double minX = points.map((p) => p.dx).reduce(min);
        double maxX = points.map((p) => p.dx).reduce(max);
        double minY = points.map((p) => p.dy).reduce(min);
        double maxY = points.map((p) => p.dy).reduce(max);

        Point<double> firstPosition = Point(minX, minY);
        Point<double> secondPosition = Point(maxX, maxY);

        // Convert coordinates from the document coordinate system to the view coordinate system
        Offset firstPositionInView = creationTransform.localToGlobal(
          firstPosition.toOffset(),
        );
        Offset secondPositionInView = creationTransform.localToGlobal(
          secondPosition.toOffset(),
        );

        // Create new ShapeElement
        shapeElement = PadElement.shape(
          id: createUniqueId(),
          firstPosition: firstPositionInView.toPoint(),
          secondPosition: secondPositionInView.toPoint(),
          property: ShapeProperty(
            shape: const TriangleShape(),
            paint: data.property.paint,
            strokeWidth: data.property.strokeWidth,
          ),
          collection: currentCollection,
        );
      default:
    }
    if (shapeElement != null) {
      // Add element on document
      context.getDocumentBloc().add(
        ElementsCreated([
          orientCreatedElement(shapeElement, transform.rotation),
        ]),
      );

      elements.clear();
      context.refresh();
    }
    // Reset the points list for the next shape detection
    points.clear();
  }

  @override
  void onScaleStartAbort(ScaleStartDetails details, EventContext context) {
    _clearShapeDetection();
    for (final pointer
        in _nativeInkCancellers?.keys.toList() ?? const <int>[]) {
      _cancelNativeInk(pointer);
    }
    elements.clear();
    lastPosition.clear();
    lastPosit = null;
    context.refresh();
  }

  @override
  void dispose(DocumentBloc bloc) {
    _clearShapeDetection();
    for (final pointer
        in _nativeInkCancellers?.keys.toList() ?? const <int>[]) {
      _cancelNativeInk(pointer);
    }
    elements.clear();
    _submittedElements.clear();
    _nativeOwnedElementIds.clear();
    lastPosition.clear();
    isDrawing = false;
    lastPosit = null;
    _bloc = null;
  }

  @override
  SRGBColor getColor() => data.property.paint.previewColor;

  @override
  PenTool setColor(SRGBColor color) => data.copyWith(
    property: data.property.copyWith(
      paint: ElementPaint.solid(
        color: color.withValues(a: getColor().a),
        blur: data.property.paint.blur,
      ),
    ),
  );

  @override
  double getStrokeWidth() => data.property.strokeWidth;

  @override
  PenTool setStrokeWidth(double width) =>
      data.copyWith(property: data.property.copyWith(strokeWidth: width));

  @override
  MouseCursor get cursor => (_hideCursorWhileDrawing && elements.isNotEmpty)
      ? SystemMouseCursors.none
      : SystemMouseCursors.precise;

  @override
  Map<String, RendererState> get rendererStates => Map.fromEntries(
    _submittedElements.map((e) => MapEntry(e.id!, RendererState.hidden)),
  );
}
