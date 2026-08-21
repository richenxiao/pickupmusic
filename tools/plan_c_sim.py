import sys,io,math,random
sys.stdout=io.TextIOWrapper(sys.stdout.buffer,encoding='utf-8')

def unpack(rgb): return ((rgb>>16)&0xFF)/255.0, ((rgb>>8)&0xFF)/255.0, (rgb&0xFF)/255.0
def chroma(rgb):
    r,g,b=unpack(rgb); rg=r-g; yb=(r+g)/2.0-b; return min(math.sqrt(rg*rg+yb*yb)*255.0,255.0)
def lum(rgb):
    r,g,b=unpack(rgb)
    def ln(c): return c/12.92 if c<=0.03928 else ((c+0.055)/1.055)**2.4
    return 0.2126*ln(r)+0.7152*ln(g)+0.0722*ln(b)
def hsv(rgb):
    r,g,b=unpack(rgb); mx,mn=max(r,g,b),min(r,g,b); d=mx-mn
    v=mx; s=d/mx if mx>0 else 0
    if d==0: h=0
    elif mx==r: h=((g-b)/d)%6
    elif mx==g: h=(b-r)/d+2
    else: h=(r-g)/d+4
    return h*60,s,v
def merge(swatches,threshold=1500):
    clusters=[]
    for sw_rgb,sw_pop in sorted(swatches,key=lambda x:-x[1]):
        r=(sw_rgb>>16)&0xFF; g=(sw_rgb>>8)&0xFF; b=sw_rgb&0xFF
        host=None
        for i,(seed,wrgb,pop) in enumerate(clusters):
            dr=(seed>>16&0xFF)-r; dg=(seed>>8&0xFF)-g; db=(seed&0xFF)-b
            if dr*dr+dg*dg+db*db<threshold: host=i; break
        if host is not None:
            seed,wrgb,pop=clusters[host]; new_pop=pop+sw_pop
            wr=int(((wrgb>>16&0xFF)*pop+r*sw_pop)/new_pop)
            wg=int(((wrgb>>8&0xFF)*pop+g*sw_pop)/new_pop)
            wb=int(((wrgb&0xFF)*pop+b*sw_pop)/new_pop)
            clusters[host]=(seed,(wr<<16)|(wg<<8)|wb,new_pop)
        else: clusters.append((sw_rgb,sw_rgb,sw_pop))
    return [(wrgb,pop) for seed,wrgb,pop in clusters]
def floor_v(rgb,min_v=0.45):
    h,s,v=hsv(rgb)
    if v<min_v: v=min_v
    c=v*s; x=c*(1-abs((h/60)%2-1)); m=v-c
    if h<60: r,g,b=c,x,0
    elif h<120: r,g,b=x,c,0
    elif h<180: r,g,b=0,c,x
    elif h<240: r,g,b=0,x,c
    elif h<300: r,g,b=x,0,c
    else: r,g,b=c,0,x
    return (int((r+m)*255),int((g+m)*255),int((b+m)*255))

def selectC(swatches):
    merged=merge(swatches); totalPop=sum(p for _,p in merged)
    foundation=max(merged,key=lambda x:x[1]); found_rgb=foundation[0]
    bgLum=lum(found_rgb); bgCh=chroma(found_rgb)
    bestScore=0.0; bestRgb=None; bestDetail=None
    # Strong-color foundation raises the accent population threshold from 5% to 10%.
    # This prevents tiny accents (e.g. 5% blue text on 85% red) from stealing a vivid theme.
    # But doesn't hard-block — 10%+ accent can still replace (e.g. 14% pink on 70% purple).
    found_chroma = chroma(found_rgb)
    pop_threshold = 0.10 if found_chroma >= 80 else 0.05
    for rgb,pop in merged:
        if rgb==found_rgb: continue
        share=min(pop/totalPop,1.0)
        cn=min(chroma(rgb)/255.0,1.0)
        bc=min(abs(lum(rgb)-bgLum),1.0)
        v=hsv(rgb)[2]; s=hsv(rgb)[1]
        ep=0.0 if (v<0.08 or (v>0.95 and s<0.05)) else 1.0
        # Hard gate: below pop_threshold → sa=0 (not a candidate), >= → sa=1
        # No linear ramp — a candidate either qualifies or doesn't.
        sa = 1.0 if share >= pop_threshold else 0.0
        # Weighted sum (not product) to avoid score deflation:
        raw_score = 0.40*share + 0.35*cn + 0.25*bc
        score = raw_score * ep * sa
        if score>bestScore:
            bestScore=score; bestRgb=rgb
            bestDetail={'share':round(share,3),'cn':round(cn,3),'bc':round(bc,3),'ep':ep,'sa':round(sa,3),'raw':round(raw_score,3),'score':round(score,4),'pop_thr':pop_threshold,'found_chr':round(found_chroma,1)}
    has_accent=bestRgb is not None and bestScore>0.05
    accent_rgb=bestRgb if has_accent else found_rgb
    if not has_accent:
        bg=floor_v(found_rgb)
    else:
        fH,fS,fV=hsv(found_rgb); aH,aS,aV=hsv(accent_rgb)
        bgH=aH; bgS=min(aS*0.6,0.85); bgV=max(fV*0.7,0.35)
        c=bgV*bgS; x=c*(1-abs((bgH/60)%2-1)); m=bgV-c
        if bgH<60: r,g,b=c,x,0
        elif bgH<120: r,g,b=x,c,0
        elif bgH<180: r,g,b=0,c,x
        elif bgH<240: r,g,b=0,x,c
        elif bgH<300: r,g,b=x,0,c
        else: r,g,b=c,0,x
        bg=floor_v((int((r+m)*255)<<16)|(int((g+m)*255)<<8)|int((b+m)*255))
    return merged,found_rgb,bgLum,bgCh,bestRgb,bestDetail,has_accent,bg

def selectB1(swatches):
    merged=merge(swatches); totalPop=sum(p for _,p in merged)
    best=max(merged,key=lambda x:4.0*(x[1]/totalPop)+1.5*(chroma(x[0])/255.0))
    best_rgb=best[0]
    if chroma(best_rgb)<40 and hsv(best_rgb)[2]>0.50:
        colored=[(rgb,pop) for rgb,pop in merged if chroma(rgb)>=40 and pop/totalPop>=0.05]
        if colored:
            colored.sort(key=lambda x:-(x[1]/totalPop*chroma(x[0])))
            best_rgb=colored[0][0]
    return floor_v(best_rgb)

scenarios=[
    ('A.deepBlack80+pink10',[(0x14141E,5000),(0x1A1A2E,2000),(0xC850B4,625),(0xE670C8,375),(0xB48C6E,500)]),
    ('B.deepBlue90+yellow2',[(0x1A2A4E,4500),(0x1E3258,2250),(0x162548,1500),(0xFADC28,180)]),
    ('C.deepBlue85+yellow12',[(0x1A2A4E,4000),(0x1E3258,2000),(0x162548,1000),(0xFADC28,1000)]),
    ('D.cream70+warm15',[(0xF5E6D3,3500),(0xE8D5C0,2500),(0xF0DECC,1500),(0xD4A880,750),(0xC09060,500),(0xE89070,400)]),
    ('E.white97+red3',[(0xF0F0F0,4850),(0xE0E0E0,2000),(0xFF1010,200)]),
    ('F.pureBlue90',[(0x285AC8,2500),(0x2C5ECC,2000)]),
    ('G.pureBlack',[(0x141414,3000),(0x1E1E1E,2000)]),
    ('H.red85+blue5',[(0xC02020,4000),(0xD03030,1500),(0x4060C0,300)]),
    ('I.purple70+pink14',[(0x3D1850,3500),(0x4A1C5C,1500),(0xFF60C0,600),(0xE050B0,400),(0x808080,500)]),
    ('J.white+pinks',[(0xF0F0F0,2000),(0xE89090,400),(0xE88080,350),(0xF0A0A0,300),(0xE07070,250),(0xF5B0B0,200)]),
]

print("="*85)
for name,sw in scenarios:
    merged,fr,bl,bc,ar,ad,ha,bg=selectC(sw)
    b1=selectB1(sw)
    tp=sum(p for _,p in merged)
    found_pop=max(merged,key=lambda x:x[1])[1]
    print(name)
    print("  found=#%06X pop=%d share=%.2f lum=%.3f chr=%.0f" % (fr&0xFFFFFF,found_pop,found_pop/tp,bl,bc))
    if ha:
        h,s,v=hsv(ar)
        print("  accent=#%06X H=%.0f S=%.2f V=%.2f" % (ar&0xFFFFFF,h,s,v))
        print("    detail: %s" % ad)
    else:
        print("  accent: None")
    print("  PlanC bg=#%02X%02X%02X  B1 bg=#%02X%02X%02X" % (bg[0],bg[1],bg[2],b1[0],b1[1],b1[2]))
    print()

print("="*85)
print("Shuffle test:")
random.seed(42)
base=[(0x14141E,5000),(0x1A1A2E,2000),(0xC850B4,625),(0xE670C8,375),(0xB48C6E,500)]
for i in range(3):
    sh=list(base); random.shuffle(sh)
    _,_,_,_,ar2,_,ha2,bg2=selectC(sh)
    acc_str = "#%06X" % (ar2&0xFFFFFF) if ha2 else "None"
    print("  #%d: bg=#%02X%02X%02X accent=%s" % (i,bg2[0],bg2[1],bg2[2],acc_str))

print()
print("="*85)
print("Boundary tests:")

# Strong foundation (red, chroma~179) + blue at 9.9% → should NOT replace
# Need blue share < 10% of total. total = red + blue. blue/total < 0.10
# red=9010, blue=990 → 990/10000 = 9.9% < 10% → sa=0 → no accent
_,_,_,_,ar_b99,_,ha_b99,bg_b99=selectC([(0xC02020,9010),(0x4060C0,990)])
print("  H1. red90.1%%+blue9.9%%: accent=%s bg=#%02X%02X%02X (expect None)" %
      ("#%06X"%(ar_b99&0xFFFFFF) if ha_b99 else "None", bg_b99[0],bg_b99[1],bg_b99[2]))

# Red 90% + blue 10.0% → blue share = 0.10 ≥ 0.10 → can be accent candidate
_,_,_,_,ar_b10,_,ha_b10,bg_b10=selectC([(0xC02020,9000),(0x4060C0,1000)])
print("  H2. red90+blue10%%: accent=%s bg=#%02X%02X%02X" %
      ("#%06X"%(ar_b10&0xFFFFFF) if ha_b10 else "None", bg_b10[0],bg_b10[1],bg_b10[2]))

# Weak foundation (black, chroma~13) + accent at 5.0% → should be candidate
# black=9500, pink=500 → 500/10000 = 5.0% ≥ 5% → sa=1 → accent candidate
_,_,_,_,ar_w5,_,ha_w5,bg_w5=selectC([(0x14141E,9500),(0xC850B4,500)])
print("  W1. black95+pink5%%: accent=%s bg=#%02X%02X%02X (expect accent)" %
      ("#%06X"%(ar_w5&0xFFFFFF) if ha_w5 else "None", bg_w5[0],bg_w5[1],bg_w5[2]))

# Weak foundation + accent at 4.9% → should NOT be candidate (below 5%)
# black=9510, pink=490 → 490/10000 = 4.9% < 5% → sa=0 → no accent
_,_,_,_,ar_w49,_,ha_w49,bg_w49=selectC([(0x14141E,9510),(0xC850B4,490)])
print("  W2. black95.1+pink4.9%%: accent=%s bg=#%02X%02X%02X (expect None)" %
      ("#%06X"%(ar_w49&0xFFFFFF) if ha_w49 else "None", bg_w49[0],bg_w49[1],bg_w49[2]))
