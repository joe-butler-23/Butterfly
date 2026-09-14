// Generates JVM-testable parity fixtures for PerfectFreehandGeometry.java from the real
// `package:perfect_freehand` implementation the app depends on (see pubspec.yaml). Run with:
//   flutter test test/tool/generate_perfect_freehand_fixtures.dart
// from the app/ directory (a plain `dart run` cannot load this file: perfect_freehand imports
// dart:ui, which only the Flutter tester provides -- flutter_test is used here purely as a
// runner, not for any widget testing). The output is consumed by
// android/app/src/testLatencyLab/java/dev/linwood/butterfly/PerfectFreehandGeometryParityTest.java
// and must not be hand-edited; regenerate instead. This file intentionally does not end in
// "_test.dart" so a plain `flutter test` run never picks it up as part of the suite.
//
// Options mirror exactly what SharedGeometryInkOverlay.java passes to the Java port: size,
// thinning, smoothing and streamline come from Butterfly's PenProperty defaults (see
// lib/selections/properties/pen.dart), and simulatePressure mirrors
// PenRenderer.shouldSimulatePressure (see lib/renderers/elements/pen.dart) -- true when the
// stroke's pressure is constant from the second point onward, false when it genuinely varies.
// isComplete, start-cap and end-cap are left at perfect_freehand's own defaults (isComplete:
// false, both caps enabled, no taper), because PenRenderer never overrides them either.
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:perfect_freehand/perfect_freehand.dart';

const double kSize = 12.0;
const double kThinning = 0.4;
const double kSmoothing = 0.5;
const double kStreamline = 0.3;

void main() {
  test('generate perfect_freehand parity fixtures', () {
    final fixtures = [
      _fixture('short_3pt', _shortThreePoint()),
      _fixture('curve_60pt_varying_pressure', _curveWithVaryingPressure()),
      _fixture('sharp_right_angle_corner', _sharpRightAngleCorner()),
      _fixture('long_400pt', _longStroke()),
    ];

    final outFile = File(
      'android/app/src/testLatencyLab/resources/dev/linwood/butterfly/'
      'perfect_freehand_fixtures.json',
    );
    outFile.createSync(recursive: true);
    outFile.writeAsStringSync(
      const JsonEncoder.withIndent('  ').convert({'fixtures': fixtures}),
    );
    // ignore: avoid_print
    print('Wrote ${fixtures.length} fixtures to ${outFile.path}');
  });
}

List<PointVector> _shortThreePoint() => const [
      PointVector(0, 0, 0.5),
      PointVector(10, 2, 0.7),
      PointVector(18, 10, 0.9),
    ];

List<PointVector> _curveWithVaryingPressure() => [
      for (int i = 0; i < 60; i++)
        PointVector(
          i * 5.0,
          50 * sin(i / 59 * pi * 1.5),
          (0.5 + 0.4 * sin(i / 59 * pi * 3)).clamp(0.05, 0.95),
        ),
    ];

List<PointVector> _sharpRightAngleCorner() => [
      for (int i = 0; i < 10; i++) PointVector(i * 6.0, 0, 0.6),
      for (int i = 1; i < 10; i++) PointVector(54.0, i * 6.0, 0.6),
    ];

List<PointVector> _longStroke() => [
      for (int i = 0; i < 400; i++)
        PointVector(i * 2.0, 40 * sin(i * 0.15), 0.55),
    ];

/// Mirrors PenRenderer.shouldSimulatePressure() in lib/renderers/elements/pen.dart.
bool _shouldSimulatePressure(List<PointVector> points) {
  if (points.length < 2) return true;
  final pressure = points[1].pressure;
  for (var i = 2; i < points.length; i++) {
    if (points[i].pressure != pressure) return false;
  }
  return true;
}

double _round(double value) => (value * 10000).round() / 10000;

Map<String, dynamic> _fixture(String name, List<PointVector> rawPoints) {
  // Round the input first so both languages compute the outline from the exact same values.
  final points = [
    for (final p in rawPoints)
      PointVector(_round(p.x), _round(p.y), _round(p.pressure ?? 0.5)),
  ];
  final simulatePressure = _shouldSimulatePressure(points);
  final options = StrokeOptions(
    size: kSize,
    thinning: kThinning,
    smoothing: kSmoothing,
    streamline: kStreamline,
    simulatePressure: simulatePressure,
  );
  final outline = getStroke(points, options: options);
  return {
    'name': name,
    'options': {
      'size': kSize,
      'thinning': kThinning,
      'smoothing': kSmoothing,
      'streamline': kStreamline,
      'simulatePressure': simulatePressure,
    },
    'points': [
      for (final p in points) [p.x, p.y, p.pressure],
    ],
    'outline': [
      for (final o in outline) [_round(o.dx), _round(o.dy)],
    ],
    'path': _pathOperations(outline),
  };
}

/// Mirrors both PenRenderer._computePaths()'s stroke-path construction (lib/renderers/elements
/// /pen.dart) and PerfectFreehandGeometry.buildFilledQuadraticPath/filledQuadraticPathOperations
/// (the Java port), so the fixture independently verifies the path-conversion step too.
List<List<dynamic>> _pathOperations(List<Offset> outline) {
  if (outline.isEmpty) return const [];
  if (outline.length < 2) {
    final p = outline[0];
    return [
      ['O', _round(p.dx), _round(p.dy), 1.0],
    ];
  }
  final operations = <List<dynamic>>[
    ['M', _round(outline[0].dx), _round(outline[0].dy)],
  ];
  for (var i = 1; i < outline.length - 1; i++) {
    final control = outline[i];
    final end = outline[i + 1];
    operations.add([
      'Q',
      _round(control.dx),
      _round(control.dy),
      _round((control.dx + end.dx) / 2),
      _round((control.dy + end.dy) / 2),
    ]);
  }
  return operations;
}
