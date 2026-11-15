"""从独立人工标注的score/label构造ROC；保留同分组，不自动把App通过与否当作真值。"""
import argparse
import json
import math
from pathlib import Path


def roc(rows):
    if not rows or any(r['label'] not in [0,1] or not math.isfinite(r['score']) for r in rows):raise ValueError('需要有限分数和0/1人工真值')
    positive=sum(r['label'] for r in rows);negative=len(rows)-positive
    if not positive or not negative:raise ValueError('需要阳性和阴性标注样本')
    points=[dict(threshold=None,tpr=0.,fpr=0.)]
    for threshold in sorted({r['score'] for r in rows},reverse=True):
        points.append(dict(threshold=threshold,tpr=sum(r['label']==1 and r['score']>=threshold for r in rows)/positive,
                           fpr=sum(r['label']==0 and r['score']>=threshold for r in rows)/negative))
    auc=sum((b['fpr']-a['fpr'])*(a['tpr']+b['tpr'])/2 for a,b in zip(points,points[1:]))
    return dict(auc=auc,positive=positive,negative=negative,points=points,
                scope='描述性ROC；阈值在开发集冻结后于独立受试者/机型验证，逐帧样本不视为独立受试者')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('labels',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    result=roc(json.loads(a.labels.read_text(encoding='utf-8-sig')));a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
    import matplotlib.pyplot as plt
    plt.plot([r['fpr'] for r in result['points']],[r['tpr'] for r in result['points']],marker='o',label=f"AUC={result['auc']:.3f}")
    plt.plot([0,1],[0,1],'--',color='gray');plt.xlabel('False positive rate');plt.ylabel('True positive rate');plt.legend();plt.savefig(a.output.with_suffix('.png'),dpi=160,bbox_inches='tight');plt.close()
