# SPDX-License-Identifier: Apache-2.0
"""Extend the existing shared ops entry point; preserve its monitor and other jobs."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

ROOT=Path(__file__).resolve().parents[2]
TARGET=Path('C:/Users/15449/.codex/worktrees/5b24/lightmeter a/outputs/shared-training-20261025/training-ops/ops.py')
BRIDGE=ROOT/'tools/metering-training/candidate_ops.py'
ACTIONS=('prepare-metering-candidates','smoke-metering-candidates','start-metering-candidates',
         'metering-candidates-status','stop-metering-candidates')


def compact_monitor(text):
    if '# metering-ABC compact monitor v2' in text:return text
    replacements={
        'def render(data,metrics):':'def render(data,metrics,history=False):',
        "        if _abc_panel:lines=[_abc_panel,'='*90]+lines":
            "        # metering-ABC compact monitor v2\n        if _abc_panel:lines=[_abc_panel,'='*90]+lines if history else [_abc_panel]",
        "    parser.add_argument('--resume-stage',action='store_true')":
            "    parser.add_argument('--resume-stage',action='store_true')\n    parser.add_argument('--history',action='store_true',help='Include historical jobs in monitor')",
        '        print(render(data,metrics));return':
            "        print(render(data,metrics,history=args.action=='status' or args.history));return",
        "        kernel=ctypes.windll.kernel32;kernel.SetConsoleTitleW('Shared Training - ALL')":
            "        kernel=ctypes.windll.kernel32;kernel.SetConsoleTitleW('Metering A B C - LIVE')",
        'render(snapshot(root,metrics),metrics),flush=True)':
            'render(snapshot(root,metrics),metrics,history=args.history),flush=True)'}
    for old,new in replacements.items():
        if text.count(old)!=1:raise ValueError('Unknown ops monitor layout: '+old)
        text=text.replace(old,new)
    return text


def save_extension(before,text,apply,backup_name):
    compile(text,str(TARGET),'exec')
    if before==text:return dict(state='already-installed',target=str(TARGET))
    backup=ROOT/'outputs/metering-training'/backup_name
    if apply:
        backup.parent.mkdir(parents=True,exist_ok=True)
        if backup.exists():raise ValueError('Backup already exists; refusing overwrite')
        shutil.copyfile(TARGET,backup)
        temp=TARGET.with_name('ops.abc-install.tmp');temp.write_text(text,encoding='utf-8');temp.replace(TARGET)
    return dict(state='installed' if apply else 'dry-run-ok',target=str(TARGET),bridge=str(BRIDGE),backup=str(backup),sha256=hashlib.sha256(text.encode()).hexdigest())


def install(apply=False):
    text=TARGET.read_text(encoding='utf-8-sig')
    before=text
    if '# metering-candidates-ABC extension' in text:
        return save_extension(before,compact_monitor(text),apply,'ops-before-abc-compact-20261106.py')
    anchor="'dust-shape-status'])"
    if text.count(anchor)!=1:raise ValueError('Unknown ops action parser; refusing replacement')
    text=text.replace(anchor,"'dust-shape-status',"+','.join(repr(a) for a in ACTIONS)+"])" )
    loader=("        import importlib.util as _abc_import\n"
            f"        _abc_spec=_abc_import.spec_from_file_location('metering_candidate_ops', {str(BRIDGE)!r})\n"
            "        _abc=_abc_import.module_from_spec(_abc_spec);_abc_spec.loader.exec_module(_abc)\n")
    anchor="    if args.action in ('prepare-dust-shape'"
    pos=text.index(anchor)
    text=text[:pos]+"    # metering-candidates-ABC extension\n    if args.action in "+repr(ACTIONS)+":\n"+loader+"        print(json.dumps(_abc.command(args.action,settings),ensure_ascii=False,indent=2));return\n"+text[pos:]
    anchor="    return '\\n'.join(lines)"
    if text.count(anchor)!=1:raise ValueError('Unknown monitor render function')
    panel="    try:\n"+loader+"        _abc_panel=_abc.panel()\n        if _abc_panel:lines=[_abc_panel,'='*90]+lines\n    except Exception as _abc_error:\n        lines.insert(0,'ABC面板读取失败：'+str(_abc_error))\n"
    text=text.replace(anchor,panel+anchor)
    anchor="        comparison=data.get('comparison',{})\n        result['metering_average_error_ev']"
    # The current CLI assigns the average directly before this local comparison variable.
    health_anchor="        result['metering_average_error_ev']=data['meter'].get('validation_mae_ev')"
    if text.count(health_anchor)!=1:raise ValueError('Unknown ops health entry point')
    health=("        _abc_pointer=read(root/'training-ops/metering-candidates.json')\n"
            "        _abc_live=read(Path(_abc_pointer.get('folder','.'))/'training-status.json') if _abc_pointer else {}\n"
            "        result['metering_candidates']=_abc_live\n"
            "        if _abc_live.get('state')=='failed':\n"
            "            result['errors'].append('ABC测光训练失败：'+str(_abc_live.get('error')));result['healthy']=False\n"
            "        if _abc_live.get('state') in ('training','validation') and not alive(_abc_live.get('pid')):\n"
            "            result['errors'].append('ABC活动状态对应的进程不存在');result['healthy']=False\n")
    text=text.replace(health_anchor,health+health_anchor)
    return save_extension(before,compact_monitor(text),apply,'ops-before-abc-20261106.py')


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--apply',action='store_true');args=p.parse_args()
    print(json.dumps(install(args.apply),ensure_ascii=False,indent=2))
