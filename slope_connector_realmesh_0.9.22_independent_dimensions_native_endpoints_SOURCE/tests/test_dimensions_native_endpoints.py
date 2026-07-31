#!/usr/bin/env python3
"""Regression checks for 0.9.22 independent dimensions and native endpoints."""
from pathlib import Path
import math

ROOT = Path(__file__).resolve().parents[1]

# The texture atlas must align both visible endpoint planes to integer tile coordinates.
def solve_phase(total_length: float, start_inset: float, end_inset: float):
    visible = max(1.0e-6, total_length - start_inset - end_inset)
    tiles = max(1, round(visible))
    scale = tiles / visible
    phase = -start_inset * scale
    return scale, phase

for total, start, end in [(10.25, .125, .125), (8.40, .20, .20), (15.75, .125, .125)]:
    scale, phase = solve_phase(total, start, end)
    start_uv = start * scale + phase
    end_uv = (total - end) * scale + phase
    assert abs(start_uv - round(start_uv)) < 1.0e-8
    assert abs(end_uv - round(end_uv)) < 1.0e-8

# Up/down scaling is independent of left/right position.
def scale_radial(point, center, radial, amount):
    rel = [point[i] - center[i] for i in range(3)]
    component = sum(rel[i] * radial[i] for i in range(3))
    return [point[i] + radial[i] * component * (amount - 1.0) for i in range(3)]

assert scale_radial([2, .5, 4], [2, 0, 4], [0, 1, 0], 3) == [2, 1.5, 4]
assert scale_radial([5, 2, -.5], [5, 2, 0], [0, 0, 1], 2) == [5, 2, -1.0]

renderer = (ROOT / 'src/main/java/com/slopeconnector/hotfix/client/UnifiedSurfaceArcRenderer.java').read_text()
dimension_mixin = (ROOT / 'src/main/java/com/slopeconnector/surface/mixin/ArcRibbonDimensionMixin.java').read_text()
screen_mixin = (ROOT / 'src/main/java/com/slopeconnector/surface/mixin/ArcDimensionScreenMixin.java').read_text()
neighbor_mixin = (ROOT / 'src/main/java/com/slopeconnector/surface/mixin/ConnectedNeighborStateMixin.java').read_text()
generator = (ROOT / 'src/main/java/com/slopeconnector/surface/RefinedConnectedGenerator.java').read_text()

# Native endpoint blocks must not be covered by endpoint SurfaceQuad overlays.
assert 'for (ArcRibbonBlockEntity.SurfaceQuad surface' not in renderer
assert 'visibleTiles' in renderer and 'startInset' in renderer and 'endInset' in renderer
assert 'c.s * a.scaleS + a.phaseS' in renderer

# Geometry, collision and trim share the same prism-level up/down transform.
assert '@Inject(method = "prism"' in dimension_mixin
assert 'component * (scale - 1.0)' in dimension_mixin
assert 'getSurfaces().isEmpty()' in dimension_mixin

# The G panel exposes the two dimensions independently.
assert '左右 -' in screen_mixin and '左右 +' in screen_mixin
assert '上下 -' in screen_mixin and '上下 +' in screen_mixin
assert '上下厚度' in screen_mixin

# Connected endpoints are forced toward the arc and keep reacting to later ordinary railings.
assert 'ConnectionStateHelper.forceWorldConnection(world, startPos, startDirection)' in generator
assert 'ConnectionStateHelper.forceWorldConnection(world, endPos, endDirection.getOpposite())' in generator
assert 'getStateForNeighborUpdate' in neighbor_mixin

print('independent dimensions, native endpoint UV and connected endpoint tests passed')
