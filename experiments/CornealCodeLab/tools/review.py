"""从导出目录复核固定候选的后半段相关，并生成无联网的审阅/标注页。只用标准库。"""
from __future__ import annotations

import argparse
import array
import base64
import bisect
import html
import json
import math
import os
from pathlib import Path
import sys
from urllib.parse import quote


def correlation(x, y):
    if len(x) != len(y) or len(x) < 8:
        return None
    a, b = sum(x) / len(x), sum(y) / len(y)
    xx = sum((v-a)**2 for v in x)
    yy = sum((v-b)**2 for v in y)
    return sum((v-a)*(w-b) for v, w in zip(x, y)) / math.sqrt(xx*yy) if xx > 1e-7 and yy > 1e-7 else None


def replay(directory, summary, eye):
    """不用 summary 里的信号值，直接从二进制灰度重算已选位置的独立验证。"""
    point = eye.get("point")
    if not point or eye.get("lagSeconds") is None:
        return {"message": "无候选或延迟，不重算"}
    events = json.loads((directory / "display_events.json").read_text(encoding="utf-8-sig"))
    times = [e["hostTime"] for e in events]
    rows = [json.loads(s) for s in (directory / "frames.jsonl").read_text(encoding="utf-8-sig").splitlines() if s.strip()]
    data = (directory / "patches.f32").read_bytes()
    x, y = min(63, int(point["x"]*64)), min(31, int(point["y"]*32))
    signal, code = [], []
    midpoint = summary["startHostTime"] + 64 * summary["configuration"]["chipSeconds"] / 2
    for row in rows:
        if row.get("eye") != eye["eye"] or row.get("status") != "valid" or row["hostTime"] < midpoint:
            continue
        offset = row.get("patchOffset")
        if not isinstance(offset, int) or offset < 0 or offset+8192 > len(data):
            raise ValueError("patchOffset 超出二进制文件范围")
        pixels = array.array("f")
        pixels.frombytes(data[offset:offset+8192])
        if sys.byteorder != "little":
            pixels.byteswap()
        x0, x1, y0, y1 = max(0, x-3), min(64, x+4), max(0, y-3), min(32, y+4)
        local = [pixels[yy*64+xx] for yy in range(y0, y1) for xx in range(x0, x1)]
        value = pixels[y*64+x] - sum(local)/len(local)
        index = bisect.bisect_right(times, row["hostTime"]-eye["lagSeconds"])-1
        signal.append(value)
        code.append(events[index]["code"] if index >= 0 else 0)
    r = correlation(signal, code)
    stored = eye.get("heldOutCorrelation")
    return {"frames": len(signal), "recomputed_r": r, "stored_r": stored,
            "absolute_difference": abs(r-stored) if r is not None and stored is not None else None}


def plot(values):
    if len(values) < 2:
        return ""
    lo, hi = min(values), max(values)
    return " ".join(f"{i*600/(len(values)-1):.2f},{95-(v-lo)/max(hi-lo, 1e-6)*90:.2f}" for i, v in enumerate(values))


def make_report(root: Path, output: Path):
    files = [root / "summary.json"] if (root / "summary.json").exists() else sorted(root.rglob("summary.json"))
    if not files:
        raise ValueError("目录中没有 summary.json；请先导出完整实验目录")
    sections, audit = [], []
    for file in files:
        summary = json.loads(file.read_text(encoding="utf-8-sig"))
        config = summary["configuration"]
        label = html.escape(f'{summary["id"]} · {config["mode"]} · {config["dotDiameter"]} pt · {config["distanceCM"]} cm')
        notes = summary.get("eligibilityNotes", [])
        if summary.get("stoppedEarly") and "提前停止" not in notes:
            notes = [*notes, "提前停止"]
        sections.append(f'<section><h2>{label}</h2><p>{html.escape("；".join(notes) if notes else "完整性无已记录警告，仍需人工核验视频")}</p>')
        for eye in summary["results"]:
            key = f'{summary["id"]}/{eye["eye"]}'
            try:
                check = replay(file.parent, summary, eye)
            except (ValueError, OSError, KeyError) as error:
                check = {"error": str(error)}
            audit.append({"trial": summary["id"], "eye": eye["eye"], **check})
            image = file.parent / (eye["eye"]+".png")
            image_html = '<p>缺少参考眼区图像</p>'
            if image.exists():
                encoded = base64.b64encode(image.read_bytes()).decode("ascii")
                image_html = f'<div class="image"><img src="data:image/png;base64,{encoded}" alt="参考眼区"><b class="manual"></b><b class="pred"></b></div>'
            point = eye.get("point") or {}
            sections.append(f'<article data-key="{html.escape(key, quote=True)}" data-mode="{html.escape(config["mode"])}" data-x="{point.get("x", "")}" data-y="{point.get("y", "")}"><h3>{html.escape(eye["eye"])}</h3>{image_html}')
            sections.append('<p>'+html.escape(eye["message"])+"</p>")
            stats = {k: eye.get(k) for k in ["samples", "rawCandidateRate", "trainCorrelation", "heldOutCorrelation", "wrongCodeCorrelation", "amplitude", "lagSeconds", "codedEvidence"]}
            sections.append('<pre>'+html.escape(json.dumps(stats, ensure_ascii=False, indent=2))+'</pre><p>从 patches.f32 独立重算：</p><pre>'+html.escape(json.dumps(check, ensure_ascii=False, indent=2))+'</pre>')
            sections.append(f'<svg viewBox="0 0 600 100"><polyline points="{plot(eye.get("traceSignal", []))}" fill="none" stroke="#a45e28"/><polyline points="{plot(eye.get("traceCode", []))}" fill="none" stroke="#888"/></svg>')
            sections.append('<p class="small">两条曲线分别归一化，只检查时序；量化结果看上表。点击眼区中的人工参考点，再选择反光类型。</p><select><option>未标注</option><option>角膜</option><option>镜片</option><option>眼睑或其他</option><option>看不清</option></select><input placeholder="备注"><p class="distance"></p></article>')
        video = file.parent / "camera.mov"
        if video.exists():
            try:
                link = quote(Path(os.path.relpath(video, output.parent)).as_posix(), safe="/:")
            except ValueError:
                link = video.as_uri()
            sections.append(f'<video controls preload="metadata" src="{link}"></video><p><a href="{link}">打开原始彩色视频</a>，人工确认角膜与镜片位置；浏览器不支持时用本机播放器。</p>')
        sections.append('</section>')
    script = """
const rows=[...document.querySelectorAll('article')];
function draw(a,state){
 const box=a.querySelector('.image');if(!box)return;
 const pred=a.querySelector('.pred');if(a.dataset.x!==''){pred.style.left=(+a.dataset.x*100)+'%';pred.style.top=(+a.dataset.y*100)+'%'}else pred.hidden=true;
 const mark=a.querySelector('.manual');mark.hidden=state.x===undefined;
 if(!mark.hidden){mark.style.left=(state.x*100)+'%';mark.style.top=(state.y*100)+'%'}
 a.querySelector('select').value=state.type||'未标注';a.querySelector('input').value=state.note||'';
 const distance=state.x!==undefined&&a.dataset.x!==''?Math.hypot((state.x-a.dataset.x)*64,(state.y-a.dataset.y)*32):null;
 a.querySelector('.distance').textContent=distance===null?'尚未点选人工参考位置':'到聚合候选的距离：'+distance.toFixed(2)+' 重采样眼区像素';
}
rows.forEach(a=>{let state=JSON.parse(localStorage.getItem('corneal:'+a.dataset.key)||'{}');
 const save=()=>{state.type=a.querySelector('select').value;state.note=a.querySelector('input').value;localStorage.setItem('corneal:'+a.dataset.key,JSON.stringify(state));draw(a,state)};
 a.querySelector('.image')?.addEventListener('click',e=>{const b=e.currentTarget.getBoundingClientRect();state.x=(e.clientX-b.left)/b.width;state.y=(e.clientY-b.top)/b.height;save()});
 a.querySelector('select').onchange=save;a.querySelector('input').onchange=save;draw(a,state);
});
document.getElementById('export').onclick=()=>{
 const csv=[['trial_eye','mode','manual_type','manual_x_norm','manual_y_norm','candidate_x_norm','candidate_y_norm','patch_pixel_distance','note']];
 rows.forEach(a=>{const s=JSON.parse(localStorage.getItem('corneal:'+a.dataset.key)||'{}');const d=s.x!==undefined&&a.dataset.x!==''?Math.hypot((s.x-a.dataset.x)*64,(s.y-a.dataset.y)*32):'';csv.push([a.dataset.key,a.dataset.mode,s.type||'未标注',s.x??'',s.y??'',a.dataset.x,a.dataset.y,d,s.note||''])});
 const text='\\ufeff'+csv.map(r=>r.map(v=>'"'+String(v).replaceAll('"','""')+'"').join(',')).join('\\r\\n');
 const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([text],{type:'text/csv;charset=utf-8'}));a.download='corneal-annotations.csv';a.click();setTimeout(()=>URL.revokeObjectURL(a.href),1000);
};
"""
    style = "body{font:16px/1.6 system-ui,sans-serif;background:#eeece7;color:#25241f;margin:24px auto;max-width:1000px;padding:20px}section{background:white;padding:24px;margin:24px 0;border-radius:18px}article{border-top:1px solid #bbb;padding:20px 0}pre{overflow:auto;background:#f6f3ed;padding:12px;font-size:12px}svg{width:100%;height:100px}.image{position:relative;width:min(640px,100%);aspect-ratio:2;cursor:crosshair;background:#111}.image img{width:100%;height:100%;image-rendering:pixelated}.image b{position:absolute;width:14px;height:14px;border:2px solid #ffb040;border-radius:50%;transform:translate(-50%,-50%);pointer-events:none}.image .manual{border-color:#35dfff;width:8px;height:8px}video{width:100%;max-height:500px}select,input,button{font:inherit;padding:8px;margin:6px}.small{font-size:13px;color:#666}button{position:sticky;top:8px}"
    text = '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>光码映光离线复核</title><style>'+style+'</style><h1>光码映光离线复核</h1><p>橙圈为 App 聚合候选，蓝圈为人工标注。绿圈或相关性不能单独证明角膜来源。候选率不是检出率；未标注和不可判读轮次应保留。</p><button id="export">导出人工标注 CSV</button>'+''.join(sections)+'<script>'+script+'</script></html>'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(text, encoding="utf-8")
    output.with_suffix(".audit.json").write_text(json.dumps(audit, ensure_ascii=False, indent=2), encoding="utf-8")
    return len(files), audit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--output", type=Path, default=Path("review.html"))
    args = parser.parse_args()
    count, audit = make_report(args.directory.resolve(), args.output.resolve())
    print(json.dumps({"trials": count, "audit": audit, "output": str(args.output.resolve())}, ensure_ascii=True))


if __name__ == "__main__":
    main()
