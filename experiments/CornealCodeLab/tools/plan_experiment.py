"""生成可复现的随机分块顺序；输出条件表，不自动修改或上传实验数据。"""
import argparse
import json
from pathlib import Path
import random


def conditions(protocol):
    if protocol == "screen":
        return [dict(mode=mode,dotDiameter=size,dotY=y,screenLevel=0.7)
                for size,y in [(96,0.25),(160,0.25),(160,0.5)]
                for mode in ["screenTarget","screenBlank"]]
    return [dict(mode=mode,dotDiameter=64,dotY=0.25,mean=0.525,modulation=0.475)
            for mode in ["steady","coded","dark"]]


def plan(protocol, blocks, seed):
    if not 1 <= blocks <= 20:
        raise ValueError("重复分块应为 1–20")
    rng=random.Random(seed); rows=[]
    for block in range(1,blocks+1):
        group=conditions(protocol)
        for config in group:
            config.update(prefer4K=True,captureFPS=30,screenBrightness=1.0,exposureMS=20,exposureISO=100,automaticDistance=True,
                          chipSeconds=0.1,chipCount=320,transitionGuardMS=40)
        rng.shuffle(group)
        for config in group:
            rows.append(dict(order=len(rows)+1,block=block,configuration=config))
    return dict(protocol=protocol,seed=seed,blocks=blocks,rounds=rows)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--protocol",choices=["screen","coded"],default="screen")
    p.add_argument("--blocks",type=int,default=3)
    p.add_argument("--seed",type=int,default=20261114)
    p.add_argument("--output",type=Path,default=Path("实验顺序.json"))
    args=p.parse_args()
    data=plan(args.protocol,args.blocks,args.seed)
    # 已确定顺序不覆盖，防止实验中途重抽顺序而丢失原记录。
    with args.output.open("x",encoding="utf8") as f:
        json.dump(data,f,ensure_ascii=False,indent=2)
    print(json.dumps({"rounds":len(data["rounds"]),"output":str(args.output)},ensure_ascii=True))


if __name__=="__main__":main()
