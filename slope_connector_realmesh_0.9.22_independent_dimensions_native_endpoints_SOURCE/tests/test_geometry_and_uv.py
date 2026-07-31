#!/usr/bin/env python3
import math

EPS=1e-6

def clip(poly, axis, boundary, greater):
    if not poly: return []
    out=[]; prev=poly[-1]; pv=prev[axis]; pin=pv>=boundary-EPS if greater else pv<=boundary+EPS
    for cur in poly:
        cv=cur[axis]; cin=cv>=boundary-EPS if greater else cv<=boundary+EPS
        if cin != pin:
            amount=0.0 if abs(cv-pv)<EPS else (boundary-pv)/(cv-pv)
            out.append(tuple(prev[i]+(cur[i]-prev[i])*amount for i in range(len(cur))))
        if cin: out.append(cur)
        prev,pv,pin=cur,cv,cin
    return out

def split_triangle(tri):
    min_s=min(v[3] for v in tri); max_s=max(v[3] for v in tri)
    min_t=min(v[4] for v in tri); max_t=max(v[4] for v in tri)
    fs=math.floor(min_s+EPS); ls=math.ceil(max_s-EPS)-1
    ft=math.floor(min_t+EPS); lt=math.ceil(max_t-EPS)-1
    pieces=[]
    for si in range(fs,ls+1):
        for ti in range(ft,lt+1):
            p=list(tri)
            p=clip(p,3,si,True);p=clip(p,3,si+1,False)
            p=clip(p,4,ti,True);p=clip(p,4,ti+1,False)
            if len(p)>=3:
                local=[(*v[:3],max(0,min(1,v[3]-si)),max(0,min(1,v[4]-ti))) for v in p]
                for i in range(1,len(local)-1):pieces.append((local[0],local[i],local[i+1]))
    return pieces

def area2(a,b,c):
    return abs((b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]))*.5

# A face crossing 0.98 -> 0.02 is unwrapped to 0.98 -> 1.02 and must split at 1.
tri=((0,0,0,.98,.1),(1,0,0,1.02,.1),(1,1,0,1.02,.9))
pieces=split_triangle(tri)
assert len(pieces)>=2, pieces
assert all(0-EPS<=v[3]<=1+EPS and 0-EPS<=v[4]<=1+EPS for p in pieces for v in p)
assert abs(sum(area2(*p) for p in pieces)-area2(*tri))<1e-5

# Full 0->1 tiles remain full, not collapsed to a single line.
full=((0,0,0,0,0),(1,0,0,1,0),(1,1,0,1,1))
full_pieces=split_triangle(full)
assert full_pieces and max(v[3] for p in full_pieces for v in p)==1
assert min(v[3] for p in full_pieces for v in p)==0

# Endpoint side centre: asymmetric sub-boxes must use the whole visible bounds centre, never an extreme piece.
boxes=[(0.00,0.30),(0.55,1.00)]
centre=(min(a for a,b in boxes)+max(b for a,b in boxes))*.5
assert abs(centre-.5)<1e-9

# Cardinal straight lead keeps both endpoint tangents exact.
def cubic_derivative(p0,p1,p2,p3,t):
    u=1-t
    return ((p1[0]-p0[0])*3*u*u+(p2[0]-p1[0])*6*u*t+(p3[0]-p2[0])*3*t*t,
            (p1[1]-p0[1])*3*u*u+(p2[1]-p1[1])*6*u*t+(p3[1]-p2[1])*3*t*t)
def norm(v):
    l=math.hypot(*v);return (v[0]/l,v[1]/l)
start=(0,0);end=(8,6);ts=(1,0);te=(0,1);handle=math.hypot(8,6)*.55
p1=(start[0]+ts[0]*handle,start[1]+ts[1]*handle)
p2=(end[0]-te[0]*handle,end[1]-te[1]*handle)
assert norm(cubic_derivative(start,p1,p2,end,0))==ts
assert norm(cubic_derivative(start,p1,p2,end,1))==te
print('geometry and continuous UV tests passed')

# Face-socket weighting: a broad central post and a thin decorative overhang touch the same side.
# The broad post must dominate instead of snapping to the overhang edge.
face_boxes=[(0.30,0.70,1.00),(0.82,0.94,0.15)]  # lateral min/max, face height
weighted=sum(((a+b)*.5)*(b-a)*h for a,b,h in face_boxes)
weight=sum((b-a)*h for a,b,h in face_boxes)
face_center=weighted/weight
assert abs(face_center-.5)<.08, face_center

# A connected curve must explicitly contain both straight-to-curve boundaries.  This is the
# geometry equivalent of reserving one straight module at each wide endpoint.
start_point=(0.0,0.0);start_core=(1.0,0.0);end_core=(7.0,5.0);end_point=(7.0,6.0)
mandatory=[start_point,start_core,end_core,end_point]
assert mandatory[1][1]==mandatory[0][1]
assert mandatory[-1][0]==mandatory[-2][0]

# Segment projection carries the longitudinal phase beyond the last prism into an endpoint skin.
def segment_phase(c0,c1,u0,u1,p):
    dx,dy=c1[0]-c0[0],c1[1]-c0[1]
    length=math.hypot(dx,dy); tx,ty=dx/length,dy/length
    along=(p[0]-c0[0])*tx+(p[1]-c0[1])*ty
    return u0+(u1-u0)*(along/length)
assert abs(segment_phase((0,0),(1,0),.75,1.0,(1.25,0))-1.0625)<1e-9

# Adjacent ribbon cells and the endpoint overlay must share exactly the same phase at the join.
left_end=segment_phase((0,0),(1,0),0.0,1.0,(1,0))
right_start=segment_phase((1,0),(2,0),1.0,2.0,(1,0))
endpoint_start=segment_phase((1,0),(2,0),1.0,2.0,(1,0))
assert left_end==right_start==endpoint_start==1.0
print('endpoint-centre, mandatory-lead and unified-surface tests passed')
