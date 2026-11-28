#!/usr/bin/env python3
"""
StrabismusLab 电脑端离线数据集校验与批量算法评测工具。

用法示例：
    # 1. 解压从 iPhone 迁移过来的 ZIP 或直接指向 StrabismusDataset 目录进行完整性核验与基线统计
    python experiments/StrabismusLab/tools/dataset_eval.py --root D:/data/StrabismusDataset

    # 2. 直接读取 iPhone 导出的整包 .zip（自动解压到同级目录并核验）
    python experiments/StrabismusLab/tools/dataset_eval.py --zip D:/data/StrabismusDataset_20261128.zip

    # 3. 导出结构化 JSON 评测报告（供 AI Agent 对比多版本算法指标）
    python experiments/StrabismusLab/tools/dataset_eval.py --root D:/data/StrabismusDataset --report out/eval_report.json
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
import zipfile
from collections import defaultdict
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional


def write_utf8(text: str) -> None:
    """Windows GBK 控制台安全输出（遵循仓库规范，避免 print() 编码崩溃）。"""
    sys.stdout.buffer.write((text + "\n").encode("utf-8", errors="replace"))
    sys.stdout.buffer.flush()


def read_jsonl(path: Path) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    if not path.is_file():
        return rows
    with path.open("r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            rows.append(json.loads(line))
    return rows


def probe_mp4_frame_count(video_path: Path) -> Optional[int]:
    """若本机装有 opencv-python，则读取 MP4 实际帧数用于与 video_frames.jsonl 1:1 校验。"""
    try:
        import cv2  # type: ignore
    except ImportError:
        return None
    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        return -1
    count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    cap.release()
    return count


@dataclass
class SampleAudit:
    sample_id: str
    subject_id: str
    target_distance_cm: int
    glasses: str
    glasses_label: str
    valid: bool
    issues: List[str] = field(default_factory=list)
    video_frames_logged: int = 0
    video_frames_mp4: Optional[int] = None
    display_events: int = 0
    duration_span_sec: float = 0.0
    exposure_locked_ratio: float = 0.0
    max_abs_distance_error_cm: float = 0.0
    # 来自端侧 session_summary.json 的基线指标（方便与后续新算法横向对比）
    baseline_left_verified: Optional[bool] = None
    baseline_right_verified: Optional[bool] = None
    baseline_left_heldout_r: Optional[float] = None
    baseline_right_heldout_r: Optional[float] = None
    baseline_hde_3d_prism: Optional[float] = None
    baseline_hde_linear_prism: Optional[float] = None
    baseline_hde_3d_sd_prism: Optional[float] = None
    custom_metrics: Dict[str, Any] = field(default_factory=dict)


def audit_sample(
    sample_dir: Path,
    custom_evaluator: Optional[Callable[[Path, Dict[str, Any], List[Dict[str, Any]], List[Dict[str, Any]]], Dict[str, Any]]] = None,
) -> SampleAudit:
    issues: List[str] = []
    meta_path = sample_dir / "dataset_meta.json"
    video_path = sample_dir / "original.mp4"
    vframes_path = sample_dir / "video_frames.jsonl"
    devents_path = sample_dir / "display_events.jsonl"
    summary_path = sample_dir / "session_summary.json"

    if not meta_path.is_file():
        return SampleAudit(
            sample_id=sample_dir.name,
            subject_id="UNKNOWN",
            target_distance_cm=0,
            glasses="unknown",
            glasses_label="未知",
            valid=False,
            issues=["缺少 dataset_meta.json"],
        )

    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    target_cm = int(meta.get("targetDistanceCm", 0))
    tolerance_cm = float(meta.get("distanceToleranceCm", 2.0))
    if target_cm not in (25, 33):
        issues.append(f"目标距离非标准档位: {target_cm} cm")

    if not video_path.is_file() or video_path.stat().st_size == 0:
        issues.append("缺少或空的 original.mp4")
    if not vframes_path.is_file():
        issues.append("缺少 video_frames.jsonl")
    if not devents_path.is_file():
        issues.append("缺少 display_events.jsonl")

    vframes = read_jsonl(vframes_path)
    devents = read_jsonl(devents_path)
    mp4_count = probe_mp4_frame_count(video_path) if video_path.is_file() else None

    if not vframes:
        issues.append("video_frames.jsonl 为空")
    if not devents:
        issues.append("display_events.jsonl 为空")

    if mp4_count is not None:
        if mp4_count < 0:
            issues.append("original.mp4 无法解码")
        elif abs(mp4_count - len(vframes)) > 2:
            issues.append(f"MP4 帧数({mp4_count})与 video_frames.jsonl({len(vframes)})不一致")

    # 校验单调时钟与 0-based videoFrameIndex 连续性
    host_times = [int(r["hostTimeMs"]) for r in vframes if r.get("hostTimeMs") is not None]
    duration_span_sec = (max(host_times) - min(host_times)) / 1000.0 if len(host_times) >= 2 else 0.0
    if duration_span_sec < 6.5:
        issues.append(f"有效采集时长偏短: {duration_span_sec:.2f}s (< 6.5s)")

    for expected_idx, row in enumerate(vframes):
        if row.get("videoFrameIndex") != expected_idx:
            issues.append(f"videoFrameIndex 在第 {expected_idx} 行不连续")
            break

    # 校验距离监督是否全程满足 ±2 cm
    dists = [float(r["distanceCm"]) for r in vframes if r.get("distanceCm") is not None]
    max_abs_err = max((abs(d - target_cm) for d in dists), default=0.0) if target_cm > 0 else 0.0
    if max_abs_err > tolerance_cm + 0.15:
        issues.append(f"存在距离越界帧: 最大偏差 {max_abs_err:.2f} cm > {tolerance_cm:.1f} cm")

    # 校验曝光锁定比例
    locked_flags = [bool(r.get("exposureLocked", False)) for r in vframes]
    locked_ratio = (sum(locked_flags) / len(locked_flags)) if locked_flags else 0.0
    if locked_ratio < 0.90:
        issues.append(f"曝光锁定比例偏低: {locked_ratio * 100:.1f}%")

    # 读取端侧基线结果（若存在）
    b_l_ver = b_r_ver = None
    b_l_r = b_r_r = None
    b_hde3d = b_hde_lin = b_hde3d_sd = None
    if summary_path.is_file():
        try:
            summary = json.loads(summary_path.read_text(encoding="utf-8"))
            nl = summary.get("nearLeftCode") or {}
            nr = summary.get("nearRightCode") or {}
            nm = summary.get("nearMetrics") or {}
            b_l_ver = nl.get("codeVerified")
            b_r_ver = nr.get("codeVerified")
            b_l_r = nl.get("heldOutCorrelation")
            b_r_r = nr.get("heldOutCorrelation")
            b_hde3d = nm.get("hde3dPrism")
            b_hde_lin = nm.get("hdePrism")
            b_hde3d_sd = nm.get("hde3dSdPrism")
        except Exception as exc:
            issues.append(f"session_summary.json 解析异常: {exc}")

    custom_metrics: Dict[str, Any] = {}
    if custom_evaluator is not None and not issues:
        custom_metrics = custom_evaluator(sample_dir, meta, vframes, devents)

    return SampleAudit(
        sample_id=str(meta.get("sampleId", sample_dir.name)),
        subject_id=str(meta.get("subjectId", "UNKNOWN")),
        target_distance_cm=target_cm,
        glasses=str(meta.get("glasses", "unknown")),
        glasses_label=str(meta.get("glassesLabel", "未知")),
        valid=len(issues) == 0,
        issues=issues,
        video_frames_logged=len(vframes),
        video_frames_mp4=mp4_count,
        display_events=len(devents),
        duration_span_sec=round(duration_span_sec, 3),
        exposure_locked_ratio=round(locked_ratio, 4),
        max_abs_distance_error_cm=round(max_abs_err, 3),
        baseline_left_verified=b_l_ver,
        baseline_right_verified=b_r_ver,
        baseline_left_heldout_r=b_l_r,
        baseline_right_heldout_r=b_r_r,
        baseline_hde_3d_prism=b_hde3d,
        baseline_hde_linear_prism=b_hde_lin,
        baseline_hde_3d_sd_prism=b_hde3d_sd,
        custom_metrics=custom_metrics,
    )


def discover_sample_dirs(root: Path) -> List[Path]:
    """自动兼容直接指向单个样本目录或整个 StrabismusDataset 根目录。"""
    if (root / "dataset_meta.json").is_file():
        return [root]
    nested = root / "StrabismusDataset"
    scan_root = nested if nested.is_dir() else root
    return sorted(p.parent for p in scan_root.rglob("dataset_meta.json"))


def summarize_buckets(audits: List[SampleAudit]) -> Dict[str, Dict[str, Any]]:
    buckets: Dict[str, List[SampleAudit]] = defaultdict(list)
    for a in audits:
        buckets["ALL"].append(a)
        buckets[f"{a.target_distance_cm}cm_{a.glasses}"].append(a)

    out: Dict[str, Dict[str, Any]] = {}
    for key, items in sorted(buckets.items()):
        valid_items = [x for x in items if x.valid]
        both_ver = [x for x in valid_items if x.baseline_left_verified and x.baseline_right_verified]
        any_ver = [x for x in valid_items if x.baseline_left_verified or x.baseline_right_verified]
        r_vals = [
            v
            for x in valid_items
            for v in (x.baseline_left_heldout_r, x.baseline_right_heldout_r)
            if v is not None and math.isfinite(v)
        ]
        hde_vals = [x.baseline_hde_3d_prism for x in valid_items if x.baseline_hde_3d_prism is not None]
        sd_vals = [x.baseline_hde_3d_sd_prism for x in valid_items if x.baseline_hde_3d_sd_prism is not None]
        out[key] = {
            "total": len(items),
            "valid": len(valid_items),
            "subjects": len({x.subject_id for x in valid_items}),
            "baseline_both_eye_verified_rate": round(len(both_ver) / len(valid_items), 4) if valid_items else None,
            "baseline_any_eye_verified_rate": round(len(any_ver) / len(valid_items), 4) if valid_items else None,
            "baseline_median_heldout_r": round(statistics.median(r_vals), 4) if r_vals else None,
            "baseline_median_hde_3d_prism": round(statistics.median(hde_vals), 3) if hde_vals else None,
            "baseline_median_hde_3d_sd_prism": round(statistics.median(sd_vals), 3) if sd_vals else None,
        }
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description="StrabismusLab 数据集完整性核验与离线批量评测工具")
    parser.add_argument("--root", type=Path, help="StrabismusDataset 目录路径")
    parser.add_argument("--zip", type=Path, dest="zip_path", help="从 iPhone 导出的 ZIP 路径（自动解压后校验）")
    parser.add_argument("--report", type=Path, help="输出 JSON 汇总报告路径")
    args = parser.parse_args()

    target_root: Optional[Path] = args.root
    if args.zip_path is not None:
        if not args.zip_path.is_file():
            write_utf8(f"[错误] 找不到 ZIP 文件: {args.zip_path}")
            return 1
        extract_dir = args.zip_path.with_suffix("")
        extract_dir.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(args.zip_path, "r") as zf:
            zf.extractall(extract_dir)
        write_utf8(f"[已解压] {args.zip_path} -> {extract_dir}")
        target_root = extract_dir

    if target_root is None or not target_root.exists():
        write_utf8("[错误] 请通过 --root 或 --zip 指定有效的数据集路径。")
        return 1

    sample_dirs = discover_sample_dirs(target_root)
    if not sample_dirs:
        write_utf8(f"[提示] 在 {target_root} 下未找到任何包含 dataset_meta.json 的样本目录。")
        return 1

    audits = [audit_sample(d) for d in sample_dirs]
    summary = summarize_buckets(audits)

    write_utf8(f"=== StrabismusLab 数据集核验报告（共 {len(audits)} 条样本） ===")
    for a in audits:
        status = "PASS" if a.valid else "FAIL"
        ver_str = f"L:{a.baseline_left_verified} R:{a.baseline_right_verified}"
        write_utf8(
            f"[{status}] {a.sample_id} | {a.subject_id} | {a.target_distance_cm}cm {a.glasses_label} | "
            f"{a.video_frames_logged}帧 ({a.duration_span_sec:.2f}s) | maxErr={a.max_abs_distance_error_cm:.2f}cm | "
            f"AE锁={a.exposure_locked_ratio * 100:.0f}% | {ver_str}"
        )
        for issue in a.issues:
            write_utf8(f"       -> 问题: {issue}")

    write_utf8("\n=== 分桶统计汇总（距离 × 戴镜） ===")
    for bucket, stats in summary.items():
        write_utf8(
            f"  {bucket:<14} valid={stats['valid']}/{stats['total']} (受试者 {stats['subjects']} 人) | "
            f"双眼通过率={stats['baseline_both_eye_verified_rate']} | "
            f"中位heldOutR={stats['baseline_median_heldout_r']} | "
            f"中位3D_HDE={stats['baseline_median_hde_3d_prism']}Δ (±{stats['baseline_median_hde_3d_sd_prism']}Δ)"
        )

    if args.report is not None:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "root": str(target_root),
            "total_samples": len(audits),
            "valid_samples": sum(1 for a in audits if a.valid),
            "buckets": summary,
            "samples": [asdict(a) for a in audits],
        }
        args.report.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        write_utf8(f"\n[已导出报告] {args.report}")

    return 0 if all(a.valid for a in audits) else 2


if __name__ == "__main__":
    raise SystemExit(main())
