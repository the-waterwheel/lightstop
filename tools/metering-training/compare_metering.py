# SPDX-License-Identifier: Apache-2.0
"""CPU-only, frozen-validation comparison. Never trains or loads the test split."""
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import sqlite3
import sys
import time

import numpy as np
from metering_baselines import CORE_PATH, app_policy, core_sha256, scores

WORK = Path(__file__).resolve().parents[2]
METHODS = ('project_global','uniform_ev','uniform_linear')


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write(path,value):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    temporary=path.with_name(path.name+f'.{os.getpid()}.tmp')
    temporary.write_text(json.dumps(value,ensure_ascii=False,indent=2,allow_nan=False)+'\n',encoding='utf-8')
    os.replace(temporary,path)


def stats(predicted,target,records,mask):
    chosen=np.flatnonzero(mask)
    if not len(chosen):
        return dict(samples=0,originals=0,mae_ev=None,p90_ev=None,max_ev=None)
    error=np.asarray(predicted)[chosen]-np.asarray(target)[chosen]
    groups={}
    for index,value in zip(chosen,error):groups.setdefault(records[index]['id'],[]).append(float(value))
    return dict(samples=len(chosen),originals=len(groups),mae_ev=float(np.mean([np.abs(x).mean() for x in groups.values()])),
                p90_ev=float(np.quantile(np.abs(error),.9)),p99_ev=float(np.quantile(np.abs(error),.99)),
                max_ev=float(np.abs(error).max()),over_1ev_rate=float(np.mean([np.mean(np.abs(x)>1) for x in groups.values()])))


def evaluate(root,if_stale=False):
    root=Path(root).resolve();output=root/'metering-comparison';output.mkdir(exist_ok=True)
    status=read(root/'metering-status.json');checkpoint=Path(status['checkpoint'])
    launcher=read(root/'controller-process.json')
    training=Path(launcher['runtime']).parent.parent/'metering-training'
    cache=WORK/'outputs/metering-training/cache-v1'
    with checkpoint.open('rb') as stream:
        raw=stream.read();checkpoint_info=os.fstat(stream.fileno())
    identity=dict(checkpoint=str(checkpoint),checkpoint_sha256=hashlib.sha256(raw).hexdigest(),checkpoint_mtime_ns=checkpoint_info.st_mtime_ns,
                  app_core_sha256=core_sha256(),evaluator_sha256=digest(__file__),baseline_port_sha256=digest(Path(__file__).with_name('metering_baselines.py')),
                  cache_index_sha256=digest(cache/'index.json'),runtime_sources={p:digest(training/p) for p in ('metering_model.py','metering_features.py','metering_data.py','metering_evaluation.py')})
    report_path=output/'report.json'
    if if_stale and report_path.exists() and read(report_path).get('identity')==identity:
        return dict(state='cached',path=str(report_path),epoch=read(report_path)['epoch'])
    policy=app_policy()
    # Import the exact running feature/model contract, independently of this evaluator.
    sys.path.insert(0,str(training))
    import torch
    from metering_model import ExposureMLP
    from metering_features import FeatureExtractor, FEATURE_VERSION
    from metering_data import load_bank
    from metering_evaluation import fixed_features,predict
    torch.set_num_threads(2);torch.use_deterministic_algorithms(True)
    began=time.perf_counter()
    state=torch.load(io.BytesIO(raw),map_location='cpu',weights_only=True)
    assert state['feature_version']==FEATURE_VERSION
    config=state['config'];index=read(cache/'index.json');extractor=FeatureExtractor()
    lut=extractor.linear_lut.numpy()
    reviewed=read(root/'reviewed-assets.json')['records']
    train_ids={r['id'] for r in reviewed if r['global_split']=='train' and r['quality_state']=='accepted'}
    train_items=[i for i in index['items'] if i['record']['split']=='train' and i['record']['id'] in train_ids]
    # A single median offset per rule, fitted only on frozen training references.
    # Never normalize each validation/reference image independently.
    calibration_key=dict(cache_index_sha256=identity['cache_index_sha256'],app_core_sha256=identity['app_core_sha256'],
                         baseline_port_sha256=identity['baseline_port_sha256'],training_ids=sorted(train_ids))
    cal_path=output/'calibration.json'
    cal=read(cal_path) if cal_path.exists() else {}
    if cal.get('identity')!=calibration_key:
        values={k:[] for k in METHODS};used=0
        for item in train_items:
            assert digest(cache/item['file'])==item['cache_sha256']
            data=np.load(cache/item['file'],allow_pickle=False)
            result=scores(lut[data],policy)
            common=result['base_reliable']
            for name in METHODS:values[name].extend(result[name][common].tolist())
            used+=int(common.sum())
        cal=dict(identity=calibration_key,training_originals=len(train_items),reference_rois=16*len(train_items),
                 reliable_reference_rois=used,offset_ev={k:float(np.median(v)) for k,v in values.items()},
                 method='One scalar median anchor per method from the same frozen train-only zero-shift ROIs; no validation/test/per-image fitting',
                 camera_calibration=False)
        write(cal_path,cal)
    # Verify held-out identities match the actual stage manifest.
    pool=Path(read(root/'publication.json')['canonical_registry'])
    db=sqlite3.connect(pool.as_uri()+'?mode=ro',uri=True)
    manifest=db.execute('SELECT path,sha256 FROM shared_manifests WHERE consumer=? AND name=?',('metering',checkpoint.parent.name)).fetchone();db.close()
    if not manifest:raise ValueError('No immutable metering manifest for this checkpoint')
    assert digest(manifest[0])==manifest[1]==state['shared_manifest_sha256']
    stage_rows=[json.loads(line) for line in Path(manifest[0]).read_text(encoding='utf-8').splitlines() if line.strip()]
    assert {r['id']:r['sha256'] for r in stage_rows if r['split']=='val'}=={i['record']['id']:i['record']['sha256'] for i in index['items'] if i['record']['split']=='val'}
    for item in index['items']:
        if item['record']['split']=='val':assert digest(cache/item['file'])==item['cache_sha256']
    bank,metadata=load_bank(cache,index,'val',torch.device('cpu'))
    with torch.inference_mode():
        features,targets,records=fixed_features(bank,metadata,extractor,config['validation_shifts_ev'],config['batch_size'])
        model=ExposureMLP();model.load_state_dict(state['model'])
        learned=predict(model,features,config['batch_size'])[:,0].numpy()
    baseline_values={k:[] for k in METHODS};reliable=[];highlight=[]
    source=lut[bank.numpy()]
    for shift in config['validation_shifts_ev']:
        for start in range(0,len(source),config['batch_size']):
            result=scores(np.clip(source[start:start+config['batch_size']]*np.float32(2**shift),0,1),policy)
            for name in METHODS:baseline_values[name].extend((cal['offset_ev'][name]-result[name]).tolist())
            reliable.extend(result['base_reliable'].tolist());highlight.extend(result['highlight_reliable'].tolist())
    reliable=np.asarray(reliable,dtype=bool)
    feature=features.numpy()
    common=reliable & (feature[:,771]<=config['valid_max_clipped_fraction']) & (feature[:,772]<=config['valid_max_dark_fraction'])
    all_samples=np.ones(len(records),dtype=bool);target=targets.numpy()
    names=dict(model='当前模型',project_global='项目全局（RGB单帧核心）',uniform_ev='完全平均（EV域）',uniform_linear='完全平均（亮度均值后log2）')
    results={}
    for name,values in dict(model=learned,**baseline_values).items():
        results[name]=dict(label=names[name],common=stats(values,target,records,common),
                           all_diagnostic=stats(values,target,records,all_samples),
                           calibration_offset_ev=cal['offset_ev'].get(name))
    if state['epoch']==status['epoch'] and 'validation_mae_ev' in status:
        assert abs(results['model']['all_diagnostic']['mae_ev']-status['validation_mae_ev'])<1e-5
    assert len({r['common']['samples'] for r in results.values()})==1
    report=dict(schema='metering-comparison-v1',generated_at=datetime.now(timezone.utc).isoformat(),epoch=state['epoch'],identity=identity,
                split='fixed validation only; test untouched',samples=len(records),originals=len({r['id'] for r in records}),
                common_samples=int(common.sum()),common_coverage=float(common.mean()),algorithms=results,calibration=cal,
                app_policy=policy,app_core_path=str(CORE_PATH),highlight_confirmed_fraction=float(np.mean(highlight)),
                scope='Same 6144 ROI sample points, shifts, targets and common reliable subset. Offline policy port with a shared linearized-RGB proxy (including linear-channel .98 clipping), not the complete app RAW/YUV or encoded-preview pipeline. No absolute EV100, device calibration, RAW retry or multi-frame fusion.',
                fully_average_definition='Primary uniform_ev = mean(log2(Y/.18)); auxiliary uniform_linear = log2(mean(Y)/.18).',
                timing_seconds=time.perf_counter()-began,execution_device='CPU, 2 threads; no GPU work or training',
                max_error_is_observed=True)
    write(report_path,report)
    archive=output/f"epoch-{state['epoch']:04d}-{identity['checkpoint_sha256'][:12]}.json"
    write(archive,report)
    return dict(state='computed',path=str(report_path),epoch=state['epoch'],common_samples=report['common_samples'],
                algorithms={k:r['common'] for k,r in results.items()},model_all=results['model']['all_diagnostic'])


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,required=True);parser.add_argument('--if-stale',action='store_true')
    args=parser.parse_args()
    print(json.dumps(evaluate(args.root,args.if_stale),ensure_ascii=False,indent=2))
