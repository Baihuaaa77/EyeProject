"""只读比较独立快照与主仓库共用文件；不覆盖用户正在修改的模块。"""
import argparse
import hashlib
import json
from pathlib import Path


def compare(snapshot, upstream):
    result=[]
    for relative in ['shared','iosApp/Sources/EyeCamera.swift','core/camera/src/main/assets/face_landmarker.task','core/designsystem/ios/CornealLabStyle.swift']:
        p=snapshot/relative
        files=sorted(p.rglob('*')) if p.is_dir() else [p]
        for source in files:
            if not source.is_file() or any(x in source.parts for x in ['build','.gradle','__pycache__']):continue
            target=upstream/source.relative_to(snapshot)
            if not target.is_file() or hashlib.sha256(source.read_bytes()).digest()!=hashlib.sha256(target.read_bytes()).digest():
                result.append(dict(path=source.relative_to(snapshot).as_posix(),status='上游缺失' if not target.exists() else '内容不同'))
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('upstream',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    snapshot=Path(__file__).resolve().parents[3]
    a.output.write_text(json.dumps(compare(snapshot,a.upstream),ensure_ascii=False,indent=2),encoding='utf8')
