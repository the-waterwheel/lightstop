# SPDX-License-Identifier: Apache-2.0
"""Streaming, same-sample A/B/C experiment; fixed validation and durable multi-consumer commits."""
import os
os.environ.setdefault('CUBLAS_WORKSPACE_CONFIG',':4096:8')
import argparse
from contextlib import contextmanager
import json
import math
from pathlib import Path
import random
import sys
import threading
import time
import numpy as np
import torch
from torch.nn import functional as F
from candidate_models import Candidate, stats_input
from candidate_cache import Cache
from common import digest, write_json, gpu_slot
from ledger import Ledger
from metering_features import FeatureExtractor, valid_features
from metering_domains import DomainAugmenter
from metering_model import ExposureMLP
from train import draw_augmentation, atomic_checkpoint
from raw_adaptation import capture_rng, restore_rng


def read(path):return json.loads(Path(path).read_text(encoding='utf-8-sig'))


@contextmanager
def process_lock(path):
    import msvcrt
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    with path.open('a+b') as f:
        if path.stat().st_size==0:f.write(b'0');f.flush()
        f.seek(0)
        msvcrt.locking(f.fileno(),msvcrt.LK_NBLCK,1)
        yield


def metric(values,targets,ids,mask=None):
    e=np.asarray(values)-np.asarray(targets); ids=np.asarray(ids)
    mask=np.ones(len(e),bool) if mask is None else np.asarray(mask,bool)
    if not mask.any():return dict(mae_ev=None,p90_ev=None,over_1ev=None,samples=0)
    groups=[e[mask & (ids==k)] for k in sorted(set(ids[mask]))]
    return dict(mae_ev=float(np.mean([np.abs(g).mean() for g in groups])),
        p90_ev=float(np.quantile(np.abs(e[mask]),.9)),
        over_1ev=float(np.mean([(np.abs(g)>1).mean() for g in groups])),
        bias_ev=float(np.mean([g.mean() for g in groups])),samples=int(mask.sum()),originals=len(groups))


@torch.inference_mode()
def evaluate(models,reference,cache,val_rows,old_data,extractor,augmenter,config,device):
    started=time.perf_counter()
    rng=capture_rng(device);torch.manual_seed(config['validation_seed'])
    for m in (*models.values(),reference):m.eval()
    arrays=np.concatenate([cache.load(r) for r in val_rows])
    old=np.concatenate([np.load(old_data/r['cache'],allow_pickle=False) for r in val_rows])
    ids0=[r['id'] for r in val_rows for _ in range(16)]
    results={d:{k:[] for k in (*models,'epoch219','uniform_ev','uniform_linear')} for d in ('synthetic_raw','synthetic_processed')}
    common={d:[] for d in results}; targets=[]; ids=[]
    anchors=read(Path(config['old_run'])/'baseline-anchors.json')['offset_ev']['canonical']
    for delta in config['validation_shifts_ev']:
        for start in range(0,len(arrays),config['batch_size']):
            high=torch.as_tensor(arrays[start:start+config['batch_size']],device=device)
            low=torch.as_tensor(old[start:start+len(high)],device=device)
            shifts=torch.full((len(high),),delta,device=device)
            pair=augmenter.pair(high,shifts); old_pair=augmenter.pair(low,shifts)
            for domain,linear,old_linear in zip(results,pair,old_pair):
                x=extractor.from_linear(stats_input(linear)); old_x=extractor.from_linear(old_linear)
                mask=valid_features(x,config)&valid_features(old_x,config)
                common[domain].extend(mask.cpu().tolist())
                for name,model in models.items():results[domain][name].extend(model(linear)[:,0].cpu().tolist())
                results[domain]['epoch219'].extend(reference(old_x)[:,0].cpu().tolist())
                luma=.2126*linear[:,0]+.7152*linear[:,1]+.0722*linear[:,2]
                ev=(luma.clamp_min(1e-6)/.18).log2()
                results[domain]['uniform_ev'].extend((anchors['uniform_ev']-ev.flatten(1).mean(1)).cpu().tolist())
                results[domain]['uniform_linear'].extend((anchors['uniform_linear']-(luma.flatten(1).mean(1).clamp_min(1e-6)/.18).log2()).cpu().tolist())
            targets.extend((-shifts).cpu().tolist());ids.extend(ids0[start:start+len(high)])
    report={d:{k:dict(all=metric(v,targets,ids),valid=metric(v,targets,ids,common[d])) for k,v in values.items()} for d,values in results.items()}
    restore_rng(rng,device)
    return dict(elapsed_seconds=time.perf_counter()-started,domains=report,selection={k:sum(report[d][k]['all']['mae_ev'] for d in report)/2 for k in results['synthetic_raw']},
        scope='16 frozen validation originals x16 ROI x9 exposure; synthetic ISP; old219 own frozen96 samples, candidates192; averages192 with frozen training-only96 anchors; no test pixels')


def loss_for(model,views,targets,mask,config):
    model.train();pred=[model(v) for v in views]
    point=sum(F.smooth_l1_loss(p[:,0],y,beta=.25) for p,y in zip(pred,targets))/3
    scale=sum(((p[:,0]-y).detach().abs()/p[:,1]+p[:,1].log()).mean() for p,y in zip(pred,targets))/3
    zero=point*0
    relation=F.smooth_l1_loss((pred[0][:,0]-pred[2][:,0])[mask],(targets[0]-targets[2])[mask],beta=.25) if mask.any() else zero
    domain=F.smooth_l1_loss(pred[0][:,0][mask],pred[1][:,0][mask],beta=.25) if mask.any() else zero
    loss=point+.1*scale+.1*relation+.1*domain
    return loss,dict(total=float(loss.detach()),compensation=float(point.detach()),scale=float(scale.detach()),consistency=float(relation.detach()),domain=float(domain.detach()))


def run(folder,smoke=False,smoke_device='cpu'):
    folder=Path(folder).resolve();config=read(folder/'config.json')
    for name,expected in read(folder/'code-index.json').items():
        if digest(folder/'code'/name)!=expected:raise ValueError('Frozen code changed: '+name)
    manifest_path=Path(config['old_run'])/'data/manifest.json'
    if digest(manifest_path)!=config['manifest_sha256']:raise ValueError('Frozen source list changed')
    manifest=read(manifest_path); rows=[r for r in manifest['rows'] if r['split']=='val' or r['sha256'] in config['selected_train']]
    if sum(r['split']=='train' for r in rows)!=config['train_count']:raise ValueError('Selected training membership changed')
    if digest(folder/'source-manifest.json')!=config['selected_manifest_sha256']:raise ValueError('Selected manifest changed')
    if any(r['split'] not in ('train','val') for r in rows):raise ValueError('Test source forbidden')
    if len({r['sha256'] for r in rows})!=len(rows):raise ValueError('Duplicate original')
    root=Path(config['control_root']);old_data=manifest_path.parent
    ledger_factory=lambda:Ledger(Path(config['pool'])/'continuous-registry.sqlite3')
    cache=Cache(config,rows,ledger_factory)
    device=torch.device(smoke_device if smoke else 'cuda')
    torch.set_num_threads(2);torch.manual_seed(config['seed']);random.seed(config['seed']);np.random.seed(config['seed'])
    torch.use_deterministic_algorithms(True)
    if device.type=='cuda':
        torch.cuda.set_per_process_memory_fraction(.2);torch.backends.cuda.matmul.allow_tf32=False
        torch.backends.cudnn.allow_tf32=False
    original=Path(config['old_run'])/'best.pt'
    if digest(original)!='c548b23435c5bb7f383020a731da96abf1835d13098274988db36d066913b0db':raise ValueError('Reference model changed')
    saved=torch.load(original,map_location='cpu',weights_only=True)
    reference=ExposureMLP().to(device);reference.load_state_dict(saved['model'])
    mean,std=reference.feature_mean.detach().cpu(),reference.feature_std.detach().cpu()
    models={k:Candidate(k,mean,std).to(device) for k in ('A','B','C')}
    opts={k:torch.optim.AdamW(m.parameters(),lr=config['learning_rate'],weight_decay=1e-4) for k,m in models.items()}
    extractor=FeatureExtractor().to(device);augmenter=DomainAugmenter(config)
    state=dict(epoch=0,batch=0,active=[],updates={k:0 for k in models},best={k:999. for k in models},
               stale={k:0 for k in models},epoch_improved={k:False for k in models},completed_sources=[],last_validation=None)
    checkpoint=folder/'last.pt'
    if checkpoint.exists() and not smoke:
        ck=torch.load(checkpoint,map_location=device,weights_only=True)
        if ck['config_sha256']!=digest(folder/'config.json'):raise ValueError('Resume config changed')
        for k in models:models[k].load_state_dict(ck['models'][k]);opts[k].load_state_dict(ck['optimizers'][k])
        state=ck['state'];restore_rng(ck['rng'],device)
    pauses=[root/'PAUSE.json',Path(config['pool'])/'pause-all.json',folder/'PAUSE.json']
    initial_pause={str(p):digest(p) if p.exists() else None for p in pauses}
    if (folder/'pause-acknowledged.json').exists():initial_pause=read(folder/'pause-acknowledged.json')
    else:write_json(folder/'pause-acknowledged.json',initial_pause)
    def paused():return any((digest(p) if p.exists() else None)!=initial_pause[str(p)] for p in pauses)
    def status(phase,**extra):
        write_json(folder/'training-status.json',dict(state=phase,pid=os.getpid(),at=time.time(),epoch=state['epoch'],
            batch=state['batch'],batches=math.ceil(len(state['active'])/config['source_batch']),active_sources=len(state['active']),
            planned_sources=config['train_count'],updates=state['updates'],best=state['best'],stale=state['stale'],
            device=str(device),gpu_name=torch.cuda.get_device_name(0) if device.type=='cuda' else None,
            performance=state.get('performance'),last_validation=state['last_validation'],**extra))
    def save():
        atomic_checkpoint(checkpoint,dict(models={k:m.state_dict() for k,m in models.items()},
            optimizers={k:o.state_dict() for k,o in opts.items()},rng=capture_rng(device),state=state,
            config_sha256=digest(folder/'config.json')))
    val_rows=[r for r in rows if r['split']=='val'];train_rows=[r for r in rows if r['split']=='train']
    if smoke:
        x=torch.rand(2,3,128,192,device=device)
        losses={}
        for k,m in models.items():
            opts[k].zero_grad(); loss,parts=loss_for(m,[x,x,x],[torch.zeros(2,device=device)]*3,torch.ones(2,dtype=torch.bool,device=device),config)
            loss.backward();opts[k].step();losses[k]=parts
        probe=folder/'smoke-reload.pt';atomic_checkpoint(probe,dict(models={k:m.state_dict() for k,m in models.items()},optimizers={k:o.state_dict() for k,o in opts.items()}))
        restored=torch.load(probe,map_location=device,weights_only=True)
        for k,m in models.items():
            clone=Candidate(k,mean,std).to(device);clone.load_state_dict(restored['models'][k]);m.eval();clone.eval()
            assert torch.equal(m(x),clone(x))
            clone_opt=torch.optim.AdamW(clone.parameters(),lr=config['learning_rate'],weight_decay=1e-4)
            clone_opt.load_state_dict(restored['optimizers'][k])
            for net,opt in ((m,opts[k]),(clone,clone_opt)):
                opt.zero_grad();loss,_=loss_for(net,[x,x,x],[torch.zeros(2,device=device)]*3,torch.ones(2,dtype=torch.bool,device=device),config)
                loss.backward();opt.step()
            assert all(torch.equal(v,clone.state_dict()[name]) for name,v in m.state_dict().items())
        write_json(folder/'smoke.json',dict(passed=True,device=str(device),finite_losses=losses,exact_model_reload=True,exact_next_optimizer_update=True,training_started=False));return
    producer=None
    with process_lock(folder/'trainer.lock'),process_lock(Path(config['pool'])/'continuous-collector.lock'),gpu_slot(root,'metering-candidates-ABC'):
        try:
            status('preparing-fixed-validation')
            for row in val_rows:
                if not cache.prepare(row,allow_download=False):raise RuntimeError('Fixed validation cache unavailable/capacity blocked')
            producer=threading.Thread(target=cache.producer,daemon=True);producer.start()
            if state['last_validation'] is None:
                status('validation-initial')
                state['last_validation']=evaluate(models,reference,cache,val_rows,old_data,extractor,augmenter,config,device)
                save()
            first_wait=time.monotonic()
            while state['epoch']<config['epochs']:
                if paused():save();status('paused-checkpoint-saved');return
                if not state['active']:
                    active=[r['sha256'] for r in train_rows if cache.replay_path(r).exists()]
                    if len(active)<32 and producer.is_alive() and time.monotonic()-first_wait<300:
                        status('waiting-first-source-batch',ready_sources=len(active));time.sleep(2);continue
                    if len(active)<8:status('waiting-minimum-eight-sources',ready_sources=len(active));time.sleep(2);continue
                    state['active']=active;state['batch']=0
                    state['epoch_improved']={k:False for k in models}
                    write_json(folder/f"data-epoch-{state['epoch']:03d}.json",dict(sources=active,planned=config['train_count'],added_only_at_epoch_boundary=True))
                    save()
                bysha={r['sha256']:r for r in train_rows}
                groups=[state['active'][i:i+config['source_batch']] for i in range(0,len(state['active']),config['source_batch'])]
                epoch_losses={k:[] for k in models}
                for bi in range(state['batch'],len(groups)):
                    subset=[bysha[h] for h in groups[bi]]
                    fresh=[r for r in subset if r['sha256'] not in state['completed_sources']]
                    ledger=ledger_factory()
                    try:
                        for r in fresh:
                            for k in models:ledger.batch('metering-candidates-'+k,folder.name+'-'+r['sha256'][:16],
                                config['selected_manifest_sha256'],[r],None,dict(epoch=state['epoch'],batch=bi),required=16)
                        bank=np.concatenate([cache.load(r) for r in subset])
                        order=torch.randperm(len(bank)).numpy()
                        for start in range(0,len(bank),config['batch_size']):
                            step_started=time.perf_counter();model_seconds={}
                            if paused():
                                # Replay this entire uncommitted source batch from its durable starting state.
                                status('pause-pending-batch-commit')
                            batch=torch.as_tensor(bank[order[start:start+config['batch_size']]],device=device)
                            a,b=(draw_augmentation(len(batch),config,device) for _ in range(2))
                            with torch.no_grad():
                                ra,pa=augmenter.pair(batch,a);_,pb=augmenter.pair(batch,b)
                                mask=valid_features(extractor.from_linear(stats_input(ra)),config)&valid_features(extractor.from_linear(stats_input(pb)),config)
                            losses={}
                            for k,m in models.items():
                                model_started=time.perf_counter()
                                opts[k].zero_grad(set_to_none=True)
                                loss,parts=loss_for(m,[ra,pa,pb],[-a,-a,-b],mask,config)
                                if not torch.isfinite(loss):raise FloatingPointError('Nonfinite '+k)
                                loss.backward(); norm=torch.nn.utils.clip_grad_norm_(m.parameters(),5)
                                if not torch.isfinite(norm):raise FloatingPointError('Nonfinite gradient '+k)
                                opts[k].step();state['updates'][k]+=1;losses[k]=parts;epoch_losses[k].append(parts['compensation'])
                                if device.type=='cuda':torch.cuda.synchronize()
                                model_seconds[k]=time.perf_counter()-model_started
                            elapsed=time.perf_counter()-step_started
                            if min(state['updates'].values())>5:
                                previous=state.get('performance') or {}
                                avg=previous.get('abc_step_seconds',elapsed)*.9+elapsed*.1
                                state['performance']=dict(abc_step_seconds=avg,last_model_seconds=model_seconds,
                                    batch_rois=len(batch),estimated_full_epoch_optimizer_seconds=math.ceil(config['train_count']*16/config['batch_size'])*avg,
                                    validation_seconds=(state['last_validation'] or {}).get('elapsed_seconds'),measured_cuda=True)
                            status('training',current_sources=[r['id'] for r in subset],roi_cursor=min(start+len(batch),len(bank)),roi_total=len(bank),loss=losses)
                        commits={}
                        if fresh:
                            for k,m in models.items():
                                p=folder/k/'commits'/f"e{state['epoch']:03d}-b{bi:03d}.pt";p.parent.mkdir(parents=True,exist_ok=True)
                                atomic_checkpoint(p,dict(model=m.state_dict(),epoch=state['epoch'],updates=state['updates'][k],sources=[r['sha256'] for r in fresh]))
                                commits[k]=(str(p),digest(p))
                            for r in fresh:
                                for k in models:ledger.commit('metering-candidates-'+k,folder.name+'-'+r['sha256'][:16],commits[k][0],dict(epoch=state['epoch'],batch=bi),{r['sha256']:16})
                        state['completed_sources']=sorted(set(state['completed_sources'])|{r['sha256'] for r in fresh})
                        state['batch']=bi+1
                        save() # All three states durable BEFORE any owned original deletion.
                        cache.collect(fresh,commits)
                    finally:ledger.close()
                    if bi==0 or bi==len(groups)-1:
                        status('validation')
                        state['last_validation']=evaluate(models,reference,cache,val_rows,old_data,extractor,augmenter,config,device)
                        for k,m in models.items():
                            score=state['last_validation']['selection'][k]
                            if score<state['best'][k]-1e-6:
                                state['best'][k]=score;state['stale'][k]=0
                                state['epoch_improved'][k]=True
                                atomic_checkpoint(folder/k/'best.pt',dict(model=m.state_dict(),validation=state['last_validation'],epoch=state['epoch'],updates=state['updates'][k]))
                        save()
                    if paused():status('paused-checkpoint-saved');return
                for k in models:
                    state['stale'][k]=0 if state['epoch_improved'][k] else state['stale'][k]+1
                    atomic_checkpoint(folder/k/'last.pt',dict(model=models[k].state_dict(),optimizer=opts[k].state_dict(),epoch=state['epoch']+1,updates=state['updates'][k]))
                with (folder/'metrics.jsonl').open('a',encoding='utf-8') as f:
                    f.write(json.dumps(dict(epoch=state['epoch']+1,active_sources=len(state['active']),updates=state['updates'],
                        train_compensation_loss={k:float(np.mean(v)) if v else None for k,v in epoch_losses.items()},validation=state['last_validation']))+'\n')
                state['epoch']+=1;state['batch']=0;state['active']=[]
                lr=config['minimum_learning_rate']+(config['learning_rate']-config['minimum_learning_rate'])*(1+math.cos(math.pi*state['epoch']/config['epochs']))/2
                for o in opts.values():
                    for g in o.param_groups:g['lr']=lr
                save();status('epoch-complete',learning_rate=lr)
                if all(v>=config['patience'] for v in state['stale'].values()) and len(state['completed_sources'])==config['train_count']:break
            status('complete' if len(state['completed_sources'])==config['train_count'] else 'budget-complete-partial-source-coverage')
        except Exception as e:
            # Do not overwrite a durable resume checkpoint with a half-updated batch.
            status('failed',error=repr(e));raise
        finally:
            cache.stop.set()
            if producer:producer.join(timeout=25)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=('run','smoke'));p.add_argument('--folder',type=Path,required=True)
    p.add_argument('--smoke-device',choices=('cpu','cuda'),default='cpu')
    a=p.parse_args()
    if a.action=='smoke' and a.smoke_device=='cuda':
        with gpu_slot(Path(read(a.folder/'config.json')['control_root']),'metering-ABC-GPU-smoke'):
            run(a.folder,smoke=True,smoke_device='cuda')
    else:run(a.folder,smoke=a.action=='smoke',smoke_device=a.smoke_device)
