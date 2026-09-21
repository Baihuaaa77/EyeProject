# -*- coding: utf-8 -*-
"""数据集管线对比：DEIMv2+OCEC vs BlazeFace+几何裁剪+OCEC（GT 框上限参考）。"""
import numpy as np, cv2, pyarrow.parquet as pq, onnxruntime as ort, time, json
from ai_edge_litert.interpreter import Interpreter

SO = ort.SessionOptions(); SO.log_severity_level = 3
DEIM = ort.InferenceSession(r"C:\Users\djj\AppData\Local\Temp\deimv2_s.onnx", SO, providers=["CPUExecutionProvider"])
OCEC = ort.InferenceSession(r"C:\Users\djj\AppData\Local\Temp\ocec_p.onnx", SO, providers=["CPUExecutionProvider"])
BLAZE = Interpreter(model_path=r"C:\Users\djj\AppData\Local\Temp\blazeface_sr.tflite"); BLAZE.allocate_tensors()
BIN = BLAZE.get_input_details()[0]; BOUT = BLAZE.get_output_details()

def ocec(crop_bgr):
    rgb = cv2.cvtColor(crop_bgr, cv2.COLOR_BGR2RGB)
    inp = cv2.resize(rgb, (40, 24), interpolation=cv2.INTER_LINEAR).astype(np.float32)/255.0
    inp = np.ascontiguousarray(inp.transpose(2,0,1))[None]
    return float(np.squeeze(OCEC.run(None, {"images": inp})[0]))

def sigmoid(x): return 1.0/(1.0+np.exp(-np.clip(x,-100,100)))

# blazeface anchors
min_s, max_s, layers, strides = 0.1484375, 0.75, 4, [8,16,16,16]
cs = [min_s + (max_s-min_s)*k/(layers-1) for k in range(layers)]
A = []
li = 0
while li < layers:
    j = li
    while j < layers and strides[j]==strides[li]: j += 1
    grid = 128//strides[li]
    for yy in range(grid):
        for xx in range(grid):
            for k in range(li, j):
                A.append(((yy+0.5)/grid,(xx+0.5)/grid))
                sn = 1.0 if k==layers-1 else cs[k+1]
                A.append(((yy+0.5)/grid,(xx+0.5)/grid))
    li = j
A = np.array(A)

def blazeface(img):
    h, w = img.shape[:2]
    scale = 128.0/max(h,w); nw, nh = round(w*scale), round(h*scale)
    canvas = np.zeros((128,128,3), np.float32); dx, dy = (128-nw)//2, (128-nh)//2
    canvas[dy:dy+nh, dx:dx+nw] = cv2.resize(img, (nw,nh))
    BLAZE.set_tensor(BIN['index'], ((canvas-127.5)/127.5).astype(np.float32)[None]); BLAZE.invoke()
    regs = BLAZE.get_tensor(BOUT[0]['index'])[0]; clss = BLAZE.get_tensor(BOUT[1]['index'])[0]
    scores = sigmoid(clss[:,0])
    i = int(np.argmax(scores)); sc = float(scores[i])
    if sc < 0.5: return None, 0.0
    cx = A[i][1] + regs[i,0]/128; cy = A[i][0] + regs[i,1]/128
    fw = regs[i,2]/128
    kx = [A[i][1] + regs[i,4]/128, A[i][1] + regs[i,6]/128]
    ky = [A[i][0] + regs[i,5]/128, A[i][0] + regs[i,7]/128]
    def sx_(v): return (v*128 - dx)/scale
    def sy_(v): return (v*128 - dy)/scale
    sfw = fw*128/scale
    eyes = []
    for kx_, ky_ in zip(kx, ky):
        ex, ey = sx_(kx_), sy_(ky_)
        ew, eh = sfw*0.24, sfw*0.11
        ey += sfw*0.005
        x1 = int(max(0, ex-ew/2)); y1 = int(max(0, ey-eh/2))
        x2 = int(min(w, ex+ew/2)); y2 = int(min(h, ey+eh/2))
        if x2-x1 >= 2 and y2-y1 >= 2: eyes.append(img[y1:y2, x1:x2])
    return eyes, sc

def deim_eyes(img):
    h, w = img.shape[:2]
    x = np.ascontiguousarray(img.transpose(2,0,1).astype(np.float32))[None]
    out = DEIM.run(None, {"input_bgr": x})[0][0]
    eyes = []
    for row in out:
        cid, score = int(row[0]), float(row[5])
        if cid == 17 and score > 0.5:
            x1, y1, x2, y2 = int(row[1]*w), int(row[2]*h), int(row[3]*w), int(row[4]*h)
            crop = img[max(0,y1):y2, max(0,x1):x2]
            if crop.size: eyes.append(crop)
    return eyes

def judge(prob, is_open):
    pred = prob >= 0.5
    if is_open: return "TP" if pred else "FN"
    return "FP" if pred else "TN"

shards = [("001", False), ("002", False), ("900", True), ("1200", True)]
res = {"blaze": {"TP":0,"FN":0,"FP":0,"TN":0,"miss":0,"lat":[]},
       "deim":  {"TP":0,"FN":0,"FP":0,"TN":0,"miss":0,"lat":[]}}
t_blaze = t_deim = 0.0
n_img = 0
for shard, is_open in shards:
    t = pq.read_table(rf"C:\Users\djj\AppData\Local\Temp\cew_shard_{shard}.parquet").to_pandas()
    for _, row in t.iterrows():
        img = cv2.imdecode(np.frombuffer(row["Image_data"]["file"], np.uint8), cv2.IMREAD_COLOR)
        if img is None: continue
        n_img += 1
        # BlazeFace 管线
        t0 = time.perf_counter()
        eyes, _ = blazeface(img)
        t_blaze += time.perf_counter()-t0
        if not eyes:
            res["blaze"]["miss"] += 1
        else:
            probs = [ocec(e) for e in eyes]
            res["blaze"][judge(float(np.mean(probs)), is_open)] += 1
        # DEIMv2 管线
        t0 = time.perf_counter()
        eyes = deim_eyes(img)
        t_deim += time.perf_counter()-t0
        if not eyes:
            res["deim"]["miss"] += 1
        else:
            probs = [ocec(e) for e in eyes]
            res["deim"][judge(float(np.mean(probs)), is_open)] += 1

def metrics(r, total, lat_s):
    tp, fn, fp, tn = r["TP"], r["FN"], r["FP"], r["TN"]
    det = total - r["miss"]
    prec = tp/max(1, tp+fp); rec = tp/max(1, tp+fn)
    f1 = 2*prec*rec/max(1e-9, prec+rec)
    return {"detect_rate": f"{det}/{total}", "accuracy": round((tp+tn)/max(1,det)*100, 2),
            "precision": round(prec, 4), "recall": round(rec, 4), "F1": round(f1, 4),
            "miss": r["miss"], "latency_ms_per_img": round(lat_s/max(1,total)*1000, 1)}

out = {"images": n_img,
       "blazeface_geo_ocec": metrics(res["blaze"], n_img, t_blaze),
       "deimv2_ocec": metrics(res["deim"], n_img, t_deim)}
print(json.dumps(out, indent=2))
with open(r"C:\Users\djj\xd-workspace\projects\EyeProject\results\dataset_eval\dataset_pipeline_comparison.json", "w") as f:
    json.dump(out, f, indent=2)
