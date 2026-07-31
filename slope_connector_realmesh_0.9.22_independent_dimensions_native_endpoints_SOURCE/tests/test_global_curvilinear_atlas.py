#!/usr/bin/env python3
import math
import random
from pathlib import Path

EPS=1e-7

# A quarter circle split into randomly ordered holder segments must reconstruct one monotonic S axis.
radius=9.0
count=72
points=[]
for i in range(count+1):
    a=math.pi-i*(math.pi*.5/count)
    points.append((radius*math.cos(a),radius*math.sin(a)))
segments=[(points[i],points[i+1]) for i in range(count)]
random.Random(92021).shuffle(segments)

# Geometry-only chain reconstruction equivalent to the Java implementation.
start=min((p for seg in segments for p in seg), key=lambda p:(p[1],p[0]))
remaining=segments[:]
chain=[]
current=start
while remaining:
    best=None
    for i,(a,b) in enumerate(remaining):
        for rev,p in ((False,a),(True,b)):
            d=(p[0]-current[0])**2+(p[1]-current[1])**2
            if best is None or d<best[0]:best=(d,i,rev)
    d,i,rev=best
    assert not chain or d<1e-8, d
    a,b=remaining.pop(i)
    if rev:a,b=b,a
    chain.append((a,b));current=b

s=0.0
stations=[0.0]
for a,b in chain:
    length=math.hypot(b[0]-a[0],b[1]-a[1])
    assert length>0
    s+=length;stations.append(s)
assert all(stations[i+1]>stations[i] for i in range(len(stations)-1))
expected=math.pi*radius*.5
assert abs(s-expected)<.01,(s,expected)

# Holder positions must have no influence on S/W.  Moving the same local prism to a different
# holder while preserving its world vertices must return the same coordinates.
def coords(point, centre, tangent, width, s0):
    ox=point[0]-centre[0];oy=point[1]-centre[1]
    along=ox*tangent[0]+oy*tangent[1]
    return s0+along, ox*width[0]+oy*width[1]
centre=(2.0,3.0);tangent=(1.0,0.0);width=(0.0,1.0);world=(2.75,3.4)
a=coords(world,centre,tangent,width,5.0)
# Different holder/local representations of the exact same world vertex.
holder1=(2,3);local1=(world[0]-holder1[0],world[1]-holder1[1])
holder2=(-7,11);local2=(world[0]-holder2[0],world[1]-holder2[1])
world1=(holder1[0]+local1[0],holder1[1]+local1[1])
world2=(holder2[0]+local2[0],holder2[1]+local2[1])
for value,expected_value in zip(coords(world1,centre,tangent,width,5.0),a):
    assert abs(value-expected_value)<1e-12
for value,expected_value in zip(coords(world2,centre,tangent,width,5.0),a):
    assert abs(value-expected_value)<1e-12

# Checkerboard phase follows real arc length and real transverse distance.
def checker(s,w):return (math.floor(s)+math.floor(w))&1
for station in [0.1,1.1,2.1,7.1,11.1]:
    assert checker(station,.1)!=checker(station,1.1)
    assert checker(station,.1)!=checker(station+1.0,.1)
# The same physical station on adjacent holder blocks must have exactly one phase.
assert checker(3.999999,.25)==checker(3.999999,.25)

source=Path(__file__).parents[1]/'src/main/java/com/slopeconnector/hotfix/client/UnifiedSurfaceArcRenderer.java'
text=source.read_text(encoding='utf-8')
for legacy in ('prism.u0()', 'prism.u1()', 'prism.w0()', 'prism.w1()', 'prism.n0()', 'prism.n1()'):
    assert legacy not in text, legacy
for required in ('accumulated real arc length','ComponentAtlas','CoordinatePair','phaseW','phaseN'):
    assert required in text, required
print('global geometry-driven curvilinear atlas tests passed')
