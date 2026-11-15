"""使用实测虹膜像素计算凸面镜近轴光学预算；输出预测，不替代PSF或相机MTF实测。"""
import argparse
import json
import math
from pathlib import Path
import statistics


def budget(iris_px, distance_mm, iris_mm=11.7, screen_mm=(65,140), dot_pt=160, screen_pt=390, radius_mm=7.8):
    values=[iris_px,distance_mm,iris_mm,*screen_mm,dot_pt,screen_pt,radius_mm]
    if not all(math.isfinite(v) and v>0 for v in values):raise ValueError("预算参数须为正有限值")
    focal=radius_mm/2
    magnification=focal/(distance_mm+focal)
    px_per_mm=iris_px/iris_mm
    width=screen_mm[0]*magnification*px_per_mm
    return dict(iris_pixels=iris_px,distance_mm=distance_mm,assumed_iris_mm=iris_mm,
                screen_native_pixels=[width,screen_mm[1]*magnification*px_per_mm],
                dot_native_pixels=width*dot_pt/screen_pt,displacement_0_1mm_pixels=0.1*px_per_mm,
                motion_16mm_pixels=16*magnification*px_per_mm,
                assumed_radius_mm=radius_mm,assumed_screen_mm=screen_mm,
                assumptions="近轴凸面镜与虹膜尺度估计；曲率不是个人实测，不包含失焦、像差、ISP、倾斜")


def summarize(directory):
    s=json.loads((directory/'summary.json').read_text(encoding='utf-8-sig'))
    rows=[json.loads(line) for line in (directory/'frames.jsonl').read_text(encoding='utf-8-sig').splitlines() if line.strip()]
    report={"trial":directory.name,"configuration":s['configuration'],"eyes":[]}
    for eye in ['image_left','image_right']:
        selected=[r for r in rows if r.get('eye')==eye and r.get('iris') and r.get('roi')]
        diameters=[2*r['iris']['boundaryRadiusX']*r['roi']['width'] for r in selected if r['iris'].get('boundaryRadiusX')]
        if not diameters:continue
        config=s['configuration']
        native=statistics.median(diameters)
        resampling=statistics.median(r['roi']['width']/128 for r in selected)
        distances=[r['distanceCM'] for r in selected if r.get('distanceCM') and r.get('status')=='valid']
        distance=statistics.median(distances) if distances else config['distanceCM']
        result=budget(native,distance*10,dot_pt=config.get('probeDiameter',config['dotDiameter']) if config['mode']=='motion' else config['dotDiameter'],
                      screen_pt=config.get('displayWidth',390),screen_mm=(config.get('screenWidthMM',65),config.get('screenHeightMM',140)),
                      radius_mm=config.get('cornealRadiusMM',7.8))
        result['distance_source']='自动虹膜测距' if distances else '旧记录距离'
        result.update(eye=eye,frames=len(selected),native_pixels_per_analysis_pixel=resampling,
                      old_result=next((r for r in s['results'] if r['eye']==eye),{}))
        result['old_result']={k:v for k,v in result['old_result'].items() if k not in ['heatmap','traceTime','traceSignal','traceCode','motionResults']}
        report['eyes'].append(result)
    return report


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('directory',type=Path);p.add_argument('--output',type=Path,required=True)
    args=p.parse_args()
    dirs=[args.directory] if (args.directory/'summary.json').exists() else sorted(x.parent for x in args.directory.rglob('summary.json'))
    args.output.write_text(json.dumps([summarize(d) for d in dirs],ensure_ascii=False,indent=2),encoding='utf8')
