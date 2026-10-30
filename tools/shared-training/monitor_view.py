# SPDX-License-Identifier: Apache-2.0
"""Small, read-only views of committed progress and live worker phases. No torch."""
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import time

from common import read_json

BEIJING = timezone(timedelta(hours=8))
STATES = {
    "shared-continuation-running": "训练运行中", "shared-continuation-loading": "恢复训练状态 / 加载数据",
    "queued-from-paused-checkpoint": "排队续训", "queued-independent-continuation": "排队续训",
    "waiting-new-reviewed-data": "等待新增审核数据", "waiting-reviewed-data": "等待下一批审核数据",
    "batch-complete": "本批已完成", "unique-target-complete": "累计原图目标已达到",
    "global-epoch-budget-complete": "累计训练轮数预算已用完", "paused": "已暂停", "failed": "运行失败",
    "running": "运行中", "starting": "启动中", "idle": "空闲",
}
PHASES = {
    "synthesis": "合成训练样本", "optimization": "检测模型训练", "repair-optimization": "修复模型训练",
    "validation": "验证评估", "evaluation-preparation": "准备验证数据", "checkpoint-saved": "断点已提交",
    "download": "读取训练原图", "network-wait": "等待原图网络读取",
}
STOP_REASONS = {
    "stage-early-stop-no-new-validation-improvement": "验证连续未改善，本阶段提前停止",
    "stage-budget": "本阶段轮数已完成", "user-pause": "收到暂停请求，已保存状态",
}


def optional(path):
    try:
        value = read_json(path)
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError):
        return {}


def timestamp(value):
    try:
        return float(value) if isinstance(value, (int, float)) else datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
    except (TypeError, ValueError, AttributeError):
        return None


def duration(value):
    if value is None:
        return "--"
    value = max(0, int(value))
    if value < 60:
        return f"{value}秒"
    if value < 3600:
        return f"{value // 60}分{value % 60:02d}秒"
    return f"{value // 3600}小时{value % 3600 // 60:02d}分"


def clock(value, now):
    instant = timestamp(value)
    if instant is None:
        return "--"
    return datetime.fromtimestamp(instant, BEIJING).strftime("%m-%d %H:%M:%S") + f"（{duration(now-instant)}前）"


def number(value):
    return "--" if value is None else f"{value:,}"


def metric(value):
    return "--" if not isinstance(value, (int, float)) else f"{value:.4f}"


def progress(done, total, width=26):
    if done is None or not total:
        return "[等待进度]"
    fraction = min(1, max(0, done / total))
    filled = int(width * fraction)
    return "[" + "#" * filled + "-" * (width - filled) + f"] {fraction*100:5.1f}%"


def tail_metrics(path):
    try:
        with Path(path).open("rb") as handle:
            handle.seek(0, 2)
            handle.seek(max(0, handle.tell() - 128 * 1024))
            lines = handle.read().splitlines()
        values = []
        for line in lines:
            try:
                row = json.loads(line)
                if isinstance(row, dict):
                    values.append(row)
            except (ValueError, UnicodeError):
                pass  # The first line may have been cut by the bounded tail read.
        return values
    except OSError:
        return []


def snapshot(root, consumer, now=None):
    root = Path(root)
    now = time.time() if now is None else now
    status = optional(root / f"{consumer}-status.json")
    scheduler = optional(root / "coordinator-state.json")
    audit = optional(root / "audit-status.json")
    registry = audit.get("canonical_registry") or optional(root / "publication.json").get("canonical_registry")
    pool = Path(registry).parent if registry else None
    acquisition = optional(pool / "continuous-acquisition.json") if pool else {}
    pause = (root / "PAUSE.json").exists() or bool(pool and any((pool / name).exists() for name in ("pause-all.json", "continuous-stop.json")))
    view = dict(consumer=consumer, now=now, status=status, scheduler=scheduler, audit=audit,
                gpu=optional(root / "gpu-owner.json"), acquisition=acquisition,
                collection=optional(root / "collection-status.json"), pause_requested=pause,
                collector=optional(root / "collector-process.json"))
    if consumer == "dust":
        checkpoint = status.get("checkpoint")
        # Tie counts to their actual checkpoint, including the short handoff to
        # a new batch. Old stream-summary.json never overrides the live phase.
        run = Path(checkpoint).parent if checkpoint else Path(scheduler["dust_run"]) if scheduler.get("dust_run") else None
        live = optional(run / "training-status.json") if run else {}
        config = optional(run / "config.json") if run else {}
        total = status.get("total_visits")
        if not total and config.get("manifest"):
            try:
                rows = [json.loads(line) for line in Path(config["manifest"]).read_text(encoding="utf-8").splitlines() if line.strip()]
                total = sum(row["split"] == "train" for row in rows) * config.get("stream", {}).get("source_passes", 1)
            except (OSError, ValueError, KeyError):
                pass
        done = status.get("cursor")
        values = tail_metrics(run / "metrics.jsonl") if run else []
        committed = {r["stream_cursor"]: r for r in values if isinstance(r.get("stream_cursor"), int) and done is not None and r["stream_cursor"] <= done}
        recent = list(committed.values())[-24:]
        timings = [r["seconds"] for r in recent if isinstance(r.get("seconds"), (int, float)) and r["seconds"] > 0]
        view.update(run=str(run) if run else None, live=live, config=config, total=total,
                    unique_target=config.get("stream", {}).get("max_training_images"),
                    recent=recent[-1] if recent else {}, average_seconds=sum(timings) / len(timings) if timings else None,
                    timing_samples=len(timings))
    else:
        checkpoint = status.get("checkpoint") or scheduler.get("metering_parent")
        run = Path(checkpoint).parent if checkpoint else None
        stage = status.get("stage")
        if stage and Path(stage).name == stage and (root / "metering" / stage).is_dir():
            run = root / "metering" / stage
        summary = optional(run / "summary.json") if run else {}
        result = summary or (optional(run / "latest-result.json") if run else {})
        runtime = optional(root / "controller-process.json").get("runtime")
        config_path = Path(runtime).parent.parent / "metering-training" / "config.json" if runtime else Path(__file__).parent.parent / "metering-training" / "config.json"
        view.update(run=str(run) if run else None, summary=summary, result=result, config=optional(config_path))
    return view


def collection_lines(view):
    source, observed, audit, now = view["acquisition"], view["collection"], view["audit"], view["now"]
    used, limit = source.get("used_bytes"), source.get("max_bytes")
    transfers = len(source.get("transfers", []))
    pending = audit.get("pending_new_review", 0)
    collection_state = "等待采集状态"
    if view["pause_requested"] or source.get("state") in ("paused", "stopped"):
        collection_state = "已请求暂停" if view["pause_requested"] else "已暂停"
    elif pending >= view["collector"].get("max_pending", 32):
        collection_state = "等待新增图片审核"
    elif transfers:
        collection_state = "正在传输原图"
    elif source.get("state"):
        blocked = observed.get("state") == "waiting-cache-capacity" and used == observed.get("used_bytes") and limit == observed.get("max_bytes")
        collection_state = "等待缓存容量" if blocked or (used is not None and limit is not None and used + source.get("reserved_bytes", 0) >= limit) else "采集运行 / 等待可用下载任务"
    rows = [f"采集状态：{collection_state}    实际网络传输：{transfers} 个"]
    if used is not None and limit:
        rows.append(f"原图缓存：{used/1024**3:.2f} / {limit/1024**3:.2f} GiB   剩余额度：{max(0,limit-used-source.get('reserved_bytes',0))/1024**2:.1f} MiB")
    rows.append(f"共享原图：可用 {number(audit.get('accepted'))}  |  待审 {number(audit.get('pending_new_review'))}  |  隔离 {number(audit.get('quarantined'))}")
    rows.append("新图：下载 → 共享审核 → 下一训练批次；验证/测试划分固定")
    instant = timestamp(source.get("updated_at"))
    if instant is not None and now - instant > 30:
        rows.append(f"注意：采集心跳距今 {duration(now-instant)}，请核查采集进程")
    return rows


def render(view):
    consumer, status, now = view["consumer"], view["status"], view["now"]
    state = status.get("state", "等待训练状态")
    name = "除尘模型 / DUST" if consumer == "dust" else "测光模型 / METERING"
    rows = [f"{name}   |   刷新 {datetime.fromtimestamp(now, BEIJING):%H:%M:%S} 北京时间", "=" * 88,
            f"训练状态：{STATES.get(state,state)}"]
    if view["pause_requested"]:
        rows.append("暂停请求：等待训练提交并保存当前断点")
    if consumer == "dust":
        live = view["live"]
        fresh_live = (timestamp(live.get("updated_at")) or 0) >= (timestamp(status.get("updated_at")) or 0)
        phase = live.get("phase") if fresh_live else None
        rows.append(f"当前阶段：{PHASES.get(phase,phase) if phase else STATES.get(state,state)}")
        rows.append(f"当前批次：{Path(view['run']).name if view['run'] else '--'}")
        done, total = status.get("cursor"), view["total"]
        rows.extend(["", f"本批已提交访问：{number(done)} / {number(total)}   {progress(done,total)}",
                     f"累计独立训练原图：{number(status.get('unique_originals'))} / {number(view['unique_target'])}   {progress(status.get('unique_originals'),view['unique_target'])}",
                     f"已提交优化步数：检测 {number(status.get('detector_steps'))}   修复 {number(status.get('repair_steps'))}"])
        recent = view["recent"]
        rows.append(f"最近训练损失：检测 {metric(recent.get('train_loss'))}   修复 {metric((recent.get('repair') or {}).get('loss'))}")
        if done is not None and total and view["average_seconds"] is not None and state == "shared-continuation-running":
            rows.append(f"本批剩余约 {duration((total-done)*view['average_seconds'])}（按最近 {view['timing_samples']} 次访问估算）")
        rows.append(f"最近断点提交：{clock(status.get('updated_at'),now)}")
        rows.append(f"阶段心跳：{clock(live.get('updated_at'),now)}")
        rows.append("本批进度按原图访问次数计；同一原图有多次访问")
    else:
        result, summary = view["result"], view["summary"]
        epoch = status.get("epoch", result.get("global_epoch"))
        maximum = view["config"].get("max_epochs")
        rows.append(f"当前阶段：{Path(view['run']).name if view['run'] else '--'}")
        rows.extend(["", f"累计训练轮数：{number(epoch)} / {number(maximum)} 轮预算   {progress(epoch,maximum)}"])
        if result:
            rows.append(f"本阶段训练：第 {result.get('parent_epoch','--')} → {result.get('global_epoch','--')} 轮，共 {result.get('stage_epochs','--')} 轮")
        rows.append(f"最近验证 MAE：{metric(status.get('validation_mae_ev',result.get('last_validation_mae_ev')))} EV")
        rows.append(f"历史最优验证 MAE：{metric(status.get('best_mae_ev',result.get('best_mae_ev')))} EV")
        if result.get("stop_reason"):
            rows.append(f"阶段结束原因：{STOP_REASONS.get(result['stop_reason'],result['stop_reason'])}")
        rows.append(f"最近训练提交：{clock(summary.get('finished_at') if summary else status.get('updated_at'),now)}")
        rows.append("等待新图时保留权重、优化器与随机状态；预算比例不代表精度达标")
    gpu = view["gpu"]
    owner = {"dust":"除尘", "metering":"测光"}.get(gpu.get("consumer"), gpu.get("consumer") or "空闲") if gpu.get("state") == "running" else "空闲"
    rows.extend(["", f"GPU 当前使用者：{owner}    本模型：{'使用中' if gpu.get('consumer')==consumer and gpu.get('state')=='running' else STATES.get(state,state)}",
                 f"调度器：{STATES.get(view['scheduler'].get('state'),view['scheduler'].get('state','--'))}", "-" * 88])
    rows.extend(collection_lines(view))
    if status.get("error"):
        rows.append("错误：" + str(status["error"]))
    rows.extend(["", f"断点：{Path(view['run']).name + '/last.pt' if view['run'] else '--'}", "每 3 秒刷新；仅观察，关闭窗口不停止训练。"])
    return "\n".join(rows)
