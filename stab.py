# Стабилизация + бесшовная петля: python3 stab.py in.mp4 out.mp4 y0 y1 x0 x1  (область неподвижных объектов на кадре 480x854)
import sys, glob, os, subprocess, shutil
import numpy as np
from PIL import Image
src, out = sys.argv[1], sys.argv[2]
reg = tuple(int(v) for v in sys.argv[3:7])
def probe(k):
    return subprocess.check_output(['ffprobe','-v','error','-select_streams','v:0','-show_entries',k,'-of','csv=p=0',src]).decode().strip().split('\n')[0]
W, H = [int(v) for v in probe('stream=width,height').split(',')]
D = float(subprocess.check_output(['ffprobe','-v','error','-show_entries','format=duration','-of','csv=p=0',src]).decode())
L = round(D - 0.15, 3)
shutil.rmtree('fr', ignore_errors=True); os.makedirs('fr')
subprocess.check_call(['ffmpeg','-v','error','-i',src,'-t',str(L),'-vf','fps=10,scale=480:854,format=gray','fr/%04d.png'])
fs = sorted(glob.glob('fr/*.png'))
def load(f): return np.asarray(Image.open(f), dtype=np.float64)
def shift(a, b):
    y0,y1,x0,x1 = reg; ra=a[y0:y1,x0:x1]; rb=b[y0:y1,x0:x1]
    ra=ra-ra.mean(); rb=rb-rb.mean()
    w=np.outer(np.hanning(ra.shape[0]),np.hanning(ra.shape[1]))
    F=np.fft.fft2(ra*w)*np.conj(np.fft.fft2(rb*w)); F/=np.abs(F)+1e-9
    r=np.fft.ifft2(F).real; y,x=np.unravel_index(r.argmax(),r.shape)
    if y>r.shape[0]//2: y-=r.shape[0]
    if x>r.shape[1]//2: x-=r.shape[1]
    return x, y
ref = load(fs[0])
s = np.array([shift(ref, load(f)) for f in fs], dtype=float)
sx = -s[:,0] * (W/480.0); sy = -s[:,1] * (H/854.0)
k = np.ones(7)/7
sm = lambda a: np.convolve(np.pad(a,3,mode='edge'), k, 'valid')
sx, sy = sm(sx), sm(sy)
t = np.arange(len(sx))/10.0
knots = np.arange(0, L+0.001, 0.5)
kx = np.interp(knots, t, sx); ky = np.interp(knots, t, sy)
m = 8
X0 = -kx.min()+m; Y0 = -ky.min()+m
cw = int((W-(kx.max()-kx.min())-2*m)//2*2); ch = int((H-(ky.max()-ky.min())-2*m)//2*2)
def expr(base, kv):
    e = f"{base+kv[-1]:.2f}"
    for i in range(len(knots)-2, -1, -1):
        a,b = knots[i], knots[i+1]; va,vb = base+kv[i], base+kv[i+1]
        e = f"if(lt(t\\,{b:.3f})\\,{va:.2f}+({vb-va:.3f})*(t-{a:.3f})/{b-a:.3f}\\,{e})"
    return e
ow = 1440; oh = int(round(ow*ch/cw/2))*2
off = round(L-1.5-1.5, 3)
fc = (f"[0:v]trim=0:{L},setpts=PTS-STARTPTS,fps=30,crop=w={cw}:h={ch}:x='{expr(X0,kx)}':y='{expr(Y0,ky)}',"
      f"scale={ow}:{oh}:flags=lanczos,format=yuv420p,split[a][b];"
      f"[a]trim=start=1.5,setpts=PTS-STARTPTS[main];[b]trim=0:1.5,setpts=PTS-STARTPTS[head];"
      f"[main][head]xfade=transition=fade:duration=1.5:offset={off}[out]")
open('fc.txt','w').write(fc)
print('src', W, H, D, 'drift x', kx.min(), kx.max(), 'y', ky.min(), ky.max(), 'crop', cw, ch, 'out', ow, oh)
subprocess.check_call(['ffmpeg','-v','error','-y','-i',src,'-filter_complex_script','fc.txt','-map','[out]','-an',
    '-c:v','libx264','-profile:v','high','-level','5.1','-preset','slower','-tune','film','-crf','18',
    '-maxrate','20M','-bufsize','40M','-pix_fmt','yuv420p','-movflags','+faststart',out])
# проверка остаточного дрожания
shutil.rmtree('fr', ignore_errors=True); os.makedirs('fr')
subprocess.check_call(['ffmpeg','-v','error','-i',out,'-vf','fps=10,scale=480:854,format=gray','fr/%04d.png'])
fs = sorted(glob.glob('fr/*.png')); ref = load(fs[0])
print('residual', [tuple(int(v) for v in shift(ref, load(f))) for f in fs[::6]], 'seam', shift(load(fs[-1]), ref))
