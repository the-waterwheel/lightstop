# SPDX-License-Identifier: Apache-2.0
"""Bridge for the existing shared ops CLI and its single terminal monitor."""
import json
from datetime import datetime, timedelta, timezone
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import unicodedata

WORKSPACE=Path(__file__).resolve().parents[2]
CONTROL=Path('C:/Users/15449/.codex/worktrees/5b24/lightmeter a/outputs/shared-training-20261025')
POOL=Path('D:/Project/film2/training-data/liu4k-v2-pool')
OLD=CONTROL.parent/'metering-training/raw-adaptation-v3-epoch200-to260-20261030-v2-io'
RUN=WORKSPACE/'outputs/metering-training/candidates-abc-400-192x128-20261106-v3'


def read(p):
    try:return json.loads(Path(p).read_text(encoding='utf-8-sig'))
    except (OSError,ValueError):return {}


def write(p,v):
    from common import write_json
    write_json(p,v)


def panel():
    pointer=read(CONTROL/'training-ops/metering-candidates.json')
    if not pointer:return ''
    run=Path(pointer['folder']);s=read(run/'training-status.json');c=read(run/'config.json')
    cache=read(Path(c['owned_cache'])/'cache-status.json') if c else {}
    replay=Path(c['owned_cache'])/'replay' if c else None
    prepared=sum((replay/(sha+'.npy')).is_file() for sha in c.get('selected_train',[])) if replay else 0
    phase=s.get('state',pointer.get('state','-'))
    phases={'training':'训练中','validation':'验证中','validation-initial':'初始验证',
        'epoch-complete':'本轮完成，准备下一轮','preparing-fixed-validation':'准备固定验证',
        'complete':'训练完成','budget-complete-partial-source-coverage':'轮数用完，原图尚未全部覆盖',
        'failed':'训练失败','paused-checkpoint-saved':'已保存断点并暂停'}
    epoch=s.get('epoch',0);active=s.get('active_sources',0);batch=s.get('batch',0);batches=s.get('batches',0)
    progress=f"第 {epoch+1} 轮，已提交源批次 {batch}/{batches}" if active else '轮次边界'
    cache_phase=cache.get('state','-')
    cache_phase={'preparing':'准备图源','ready':'全部准备完成',
        'capacity-wait-training-continues':'容量不足，暂停下载，训练继续',
        'source-retry-training-continues':'图源重试，训练继续',
        'waiting-capacity-or-source':'等待容量或图源，训练继续'}.get(cache_phase,cache_phase)
    lines=['测光 A/B/C 实时监视 | 192×128 | '+datetime.now(timezone(timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S'),
        f"状态：{phases.get(phase,phase)} | 已完成 {epoch}/{c.get('epochs',200)} 轮 | {progress}",
        f"原图：本轮 {active} 张 | 训练缓存已准备 {prepared}/{c.get('train_count',400)} 张（下轮加入新图）",
        f"缓存：{cache.get('pool_bytes',0)/1024**3:.2f}/{c.get('pool_limit_bytes',1)/1024**3:.1f} GiB | {cache_phase} | 重试源 {len(cache.get('failures',{}))}",
        f"GPU：{s.get('gpu_name','-')} | 设备 {s.get('device','-')} | PID {s.get('pid',pointer.get('pid','-'))}"]
    perf=s.get('performance') or {}
    if perf:
        lines.append(f"取景样本：{s.get('roi_cursor','-')}/{s.get('roi_total','-')} | 联合更新滚动均时 {perf['abc_step_seconds']:.3f}s | 最近小批 {perf['batch_rois']} 个样本")
    lines+=['='*88,'模型                  更新数    损失    最佳MAE   RAW/预览MAE    RAW/预览P90   >1EV%(R/P)']
    loss=s.get('loss',{});val=s.get('last_validation') or {};domains=val.get('domains',{})
    def number(v):return f'{v:.4f}' if isinstance(v,(int,float)) else '-'
    def pad(label):return label+' '*max(0,21-sum(2 if unicodedata.east_asian_width(x) in 'WF' else 1 for x in label))
    for k,label in (('A','A 统计+TinyDS'),('B','B Wide TinyDS'),('C','C MobileNetV3 0.5'),
                    ('epoch219','现用模型219轮'),('uniform_ev','平均EV'),('uniform_linear','平均亮度')):
        raw=domains.get('synthetic_raw',{}).get(k,{}).get('all',{})
        processed=domains.get('synthetic_processed',{}).get(k,{}).get('all',{})
        best=s.get('best',{}).get(k)
        best=best if best is not None and best<100 else None
        tails=f"{raw['over_1ev']*100:4.1f}/{processed['over_1ev']*100:4.1f}" if raw and processed else '-'
        lines.append(f"{pad(label)} {s.get('updates',{}).get(k,'-'):>6} {number(loss.get(k,{}).get('compensation')):>7} {number(best):>8} {number(raw.get('mae_ev')):>6}/{number(processed.get('mae_ev')):>6} {number(raw.get('p90_ev')):>6}/{number(processed.get('p90_ev')):>6} {tails}")
    lines.append('最近完整验证；当前验证尚未结束。' if phase.startswith('validation') else '表中RAW/预览为最近完整验证；最佳MAE为该模型两域平均的历史最低值。')
    lines.append('固定16张验证图 ×16取景 ×9曝光；合成RAW/预览，MAE单位EV，越低越好。')
    lines.append('训练曝光：25%保持，75%随机±3EV；每次重新抽样，A/B/C共用增强样本。')
    lines.append('每3秒刷新 | 历史详情：ops.py status | 关闭窗口不停止训练。')
    if s.get('error'):lines.append('错误：'+s['error'][:160])
    return '\n'.join(lines)


def prepare(settings):
    from common import digest
    if (RUN/'config.json').exists():return dict(state='already-prepared',folder=str(RUN))
    RUN.mkdir(parents=True,exist_ok=True);code=RUN/'code';code.mkdir()
    for path in (WORKSPACE/'tools/metering-training').glob('*.py'):shutil.copyfile(path,code/path.name)
    for name in ('common.py','ledger.py'):shutil.copyfile(WORKSPACE/'tools/shared-training'/name,code/name)
    shutil.copyfile(WORKSPACE/'app/src/main/java/com/lightmeter/rawmeter/EvaluativeMetering.kt',code/'EvaluativeMetering.kt')
    for k in ('A','B','C'):(RUN/k).mkdir()
    config=read(OLD/'config.json')
    import random
    source_rows=[r for r in read(OLD/'data/manifest.json')['rows'] if r['split']=='train']
    ready=[r for r in source_rows if Path(r['provenance']['image']).is_file()]
    missing=[r for r in source_rows if not Path(r['provenance']['image']).is_file()]
    random.Random(20261106).shuffle(missing)
    selected=(ready+missing)[:400]
    config.update(control_root=str(CONTROL),pool=str(POOL),owned_cache=str(POOL/'metering-candidates-20261106'),
        old_run=str(OLD),manifest_sha256=digest(OLD/'data/manifest.json'),epochs=200,patience=20,seed=20261106,
        source_batch=32,batch_size=32,learning_rate=.0003,minimum_learning_rate=.00001,
        train_count=400,selected_train=[r['sha256'] for r in selected],
        pool_limit_bytes=12*1024**3,cache_policy='only exclusively owned restored train copies; all registered refs complete; never delete shared original/fixed image/checkpoint',
        input_mapping='192x128 original sparse points; A stats takes [1::2,1::2] =>96x64; no per-image exposure normalization',
        supervision='same source/ROI reference + linear-domain random exposure delta; target=-delta',
        models=dict(A='stats+TinyDS',B='WideTinyDS-spatial2x3',C='MobileNetV3Small0.5-regression-no-pretrained'))
    selected_manifest=dict(schema='metering-abc-frozen-400-v1',parent_manifest_sha256=config['manifest_sha256'],
        counts=dict(train=400,val=16),rows=selected+[r for r in read(OLD/'data/manifest.json')['rows'] if r['split']=='val'])
    write(RUN/'source-manifest.json',selected_manifest)
    config['selected_manifest_sha256']=digest(RUN/'source-manifest.json')
    write(RUN/'config.json',config)
    write(RUN/'code-index.json',{p.name:digest(p) for p in code.iterdir() if p.is_file()})
    shutil.copyfile(WORKSPACE/'LICENSE',RUN/'LICENSE')
    shutil.copyfile(WORKSPACE/'docs/research/METERING_ABC_TRAINING_PROTOCOL_2026_11_06_ZH.md',RUN/'TRAINING_PROTOCOL_ZH.md')
    import sqlite3
    from ledger import Ledger
    from raw_adaptation_data import validate_rows
    manifest=read(OLD/'data/manifest.json');validate_rows(manifest['rows'])
    source=sqlite3.connect(POOL/'continuous-registry.sqlite3',timeout=30)
    target=sqlite3.connect(RUN/'registry-before-abc.sqlite3')
    source.backup(target);target.close();source.close()
    ledger=Ledger(POOL/'continuous-registry.sqlite3')
    try:
        for k in ('A','B','C'):
            ledger.freeze_manifest('metering-candidates-'+k,RUN.name,RUN/'source-manifest.json',dict(input=[192,128],fixed_sources=True,train_count=400))
    finally:ledger.close()
    write(CONTROL/'training-ops/metering-candidates.json',dict(state='prepared',folder=str(RUN),at=time.time()))
    return dict(state='prepared',folder=str(RUN))


def command(action,settings):
    sys.path.insert(0,str(Path(__file__).parent))
    sys.path.insert(0,str(WORKSPACE/'tools/shared-training'))
    from common import digest
    if action=='prepare-metering-candidates':return prepare(settings)
    if action=='metering-candidates-status':return dict(panel=panel())
    if action=='stop-metering-candidates':
        write(RUN/'PAUSE.json',dict(at=time.time(),reason='explicit user stop'))
        return dict(state='stop-requested-save-at-source-batch-boundary',folder=str(RUN))
    if not (RUN/'config.json').exists():prepare(settings)
    args=[settings['python'],'-B','-u',str(RUN/'code/candidate_training.py')]
    if action=='smoke-metering-candidates':
        result=subprocess.run(args+['smoke','--folder',str(RUN),'--smoke-device','cuda'],cwd=RUN,capture_output=True,text=True,encoding='utf-8',timeout=600)
        if result.returncode:raise RuntimeError(result.stderr[-5000:])
        return read(RUN/'smoke.json')
    from ops import alive
    launch=read(RUN/'launch.json')
    if alive(launch.get('pid')):return dict(state='already-running',**launch)
    if not read(RUN/'smoke.json').get('passed'):raise ValueError('Run smoke/reload first')
    for name in ('controller-process.json','coordinator-state.json','active-job.json','gpu-owner.json','collector-process.json'):
        record=read(CONTROL/name)
        if alive(record.get('pid')):raise RuntimeError('Existing shared process still live: '+name)
    # Explicit candidate-only start acknowledges historic global pause bytes; do not resume other jobs.
    if (RUN/'PAUSE.json').exists():raise ValueError('Candidate-specific PAUSE exists; keep it and request explicit resume workflow')
    for name,expected in read(RUN/'code-index.json').items():
        if digest(RUN/'code'/name)!=expected:raise ValueError('Frozen code changed: '+name)
    env=dict(os.environ,PYTHONUTF8='1',PYTHONIOENCODING='utf-8',PYTHONDONTWRITEBYTECODE='1')
    with (RUN/'stdout.log').open('a',encoding='utf-8') as out,(RUN/'stderr.log').open('a',encoding='utf-8') as err:
        p=subprocess.Popen(args+['run','--folder',str(RUN)],cwd=RUN,stdin=subprocess.DEVNULL,stdout=out,stderr=err,
            env=env,creationflags=subprocess.CREATE_NO_WINDOW|subprocess.CREATE_NEW_PROCESS_GROUP,close_fds=True)
    launch=dict(pid=p.pid,command=args+['run','--folder',str(RUN)],folder=str(RUN),at=time.time())
    write(RUN/'launch.json',launch);write(CONTROL/'training-ops/metering-candidates.json',dict(state='launched',**launch))
    return dict(state='launched',**launch)
