#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 "$ROOT/tests/test_geometry_and_uv.py"
python3 "$ROOT/tests/test_global_curvilinear_atlas.py"
python3 "$ROOT/tests/test_dimensions_native_endpoints.py"
python3 - "$ROOT" <<'PY'
import json,pathlib,sys
root=pathlib.Path(sys.argv[1])
for path in root.rglob('*.json'):
    json.loads(path.read_text(encoding='utf-8'))
for path in root.rglob('*.java'):
    text=path.read_text(encoding='utf-8')
    if text.count('{')!=text.count('}'):
        raise SystemExit(f'brace mismatch: {path}')
print('JSON and Java structure checks passed')
PY
# Verify every base API referenced by reflection or records exists in the bundled jars.
J19="$ROOT/libs/slopeconnector-0.9.19.jar"
J17="$ROOT/libs/slopeconnector-0.9.17.jar"
javap -classpath "$J19" -p com.slopeconnector.connected.ConnectedArcBlockEntity | grep -q 'setData'
javap -classpath "$J19" -p 'com.slopeconnector.connected.ConnectedArcBlockEntity$Section' | grep -q 'Section(float\[\])'
javap -classpath "$J19" -p com.slopeconnector.connected.ConnectedArcGenerator | grep -q 'generate('
javap -classpath "$J19" -p com.slopeconnector.connected.ConnectedBlockClassifier | grep -q 'straightState'
javap -classpath "$J19" -p com.slopeconnector.hotfix.client.UvSafeArcRibbonRenderer | grep -q 'renderReplacement'
javap -classpath "$J17" -p com.slopeconnector.hotfix.ArcRibbonBlockEntity | grep -q 'getSurfaces'
javap -classpath "$J17" -p 'com.slopeconnector.hotfix.ArcRibbonBlockEntity$Prism' | grep -q 'materialHint'
# Ordinary client classes may never live inside our declared mixin package.
MIXIN_PACKAGE=$(python3 -c 'import json;print(json.load(open("'"$ROOT"'/src/main/resources/slopeconnector_surface_refine.mixins.json"))["package"])')
ENTRY_MAIN=$(python3 -c 'import json;print(json.load(open("'"$ROOT"'/src/main/resources/fabric.mod.json"))["entrypoints"]["main"][0])')
ENTRY_CLIENT=$(python3 -c 'import json;print(json.load(open("'"$ROOT"'/src/main/resources/fabric.mod.json"))["entrypoints"]["client"][0])')
[[ "$ENTRY_MAIN" != "$MIXIN_PACKAGE".* ]]
[[ "$ENTRY_CLIENT" != "$MIXIN_PACKAGE".* ]]
echo 'bundled API and mixin isolation checks passed'

# Source-level regression guards for the four requested changes.
GEN="$ROOT/src/main/java/com/slopeconnector/surface/RefinedConnectedGenerator.java"
SURFACE="$ROOT/src/main/java/com/slopeconnector/hotfix/client/UnifiedSurfaceArcRenderer.java"
MIXINS="$ROOT/src/main/resources/slopeconnector_surface_refine.mixins.json"
grep -q 'sampleWithMandatoryLeads' "$GEN"
grep -q 'largest visible area' "$GEN"
grep -q 'ComponentAtlas' "$SURFACE"
grep -q 'accumulated real arc length' "$SURFACE"
! grep -Eq 'prism\.(u0|u1|w0|w1|n0|n1)\(\)' "$SURFACE"
grep -q 'intValue = 82' "$ROOT/src/main/java/com/slopeconnector/surface/mixin/ArcPanelKeyMixin.java"
grep -q 'return 71' "$ROOT/src/main/java/com/slopeconnector/surface/mixin/ArcPanelKeyMixin.java"
grep -q 'ArcWandHoldingMixin' "$MIXINS"
grep -q 'ArcDimensionScreenMixin' "$MIXINS"
grep -q 'ArcRibbonDimensionMixin' "$MIXINS"
grep -q 'ConnectedNeighborStateMixin' "$MIXINS"
grep -q 'visibleTiles' "$SURFACE"
! grep -q 'for (ArcRibbonBlockEntity.SurfaceQuad surface' "$SURFACE"
grep -q 'SUPPORTED_CACHE' "$ROOT/src/main/java/com/slopeconnector/surface/ConnectionStateHelper.java"
echo 'requested-feature source guards passed'

# The legacy GUI/HUD classes are runtime-nested inside 0.9.17, not visible as normal Gradle
# dependencies. Verify the nested target jar and require @Pseudo on all soft-target mixins.
TMP_CORE="$(mktemp -d)"
trap 'rm -rf "$TMP_CORE"' EXIT
unzip -p "$J17" META-INF/jars/slopeconnector-0.9.10.jar > "$TMP_CORE/slopeconnector-0.9.10.jar"
test -s "$TMP_CORE/slopeconnector-0.9.10.jar"
jar tf "$TMP_CORE/slopeconnector-0.9.10.jar" | grep -q '^com/slopeconnector/client/ArcWandHud.class$'
jar tf "$TMP_CORE/slopeconnector-0.9.10.jar" | grep -q '^com/slopeconnector/client/SlopeConnectorClient.class$'
javap -classpath "$TMP_CORE/slopeconnector-0.9.10.jar" -p com.slopeconnector.client.ArcWandHud | grep -q 'isHoldingArcWand'
javap -classpath "$TMP_CORE/slopeconnector-0.9.10.jar" -p com.slopeconnector.client.ArcWandHud | grep -q 'render('
javap -classpath "$TMP_CORE/slopeconnector-0.9.10.jar" -p com.slopeconnector.client.SlopeConnectorClient | grep -q 'onInitializeClient'
for file in ArcPanelKeyMixin.java ArcHudPromptMixin.java ArcWandHoldingMixin.java ArcDimensionScreenMixin.java ArcRibbonDimensionMixin.java; do
  grep -q '@Pseudo' "$ROOT/src/main/java/com/slopeconnector/surface/mixin/$file"
done
grep -q '@Mixin(value = UvSafeArcRibbonRenderer.class' "$ROOT/src/main/java/com/slopeconnector/surface/mixin/ArcRibbonRendererMixin.java"
grep -q '@Mixin(value = ConnectedArcRenderer.class' "$ROOT/src/main/java/com/slopeconnector/surface/mixin/ConnectedArcRendererMixin.java"
echo 'runtime-nested Mixin target checks passed'
