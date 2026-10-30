# SPDX-License-Identifier: Apache-2.0
"""Bind a training stage to the Global-only Android input implementation."""
import json
from pathlib import Path
import re

from metering_data import sha256
from metering_domains import DOMAIN_VERSION
from metering_features import FEATURE_VERSION

SOURCES = (
    "app/src/main/assets/global_metering_input_contract.json",
    "app/src/main/java/com/lightmeter/rawmeter/GlobalMeteringInputMapping.kt",
    "app/src/main/java/com/lightmeter/rawmeter/ProcessedLumaMath.kt",
    "app/src/main/java/com/lightmeter/rawmeter/ProcessedLumaMetadata.kt",
    "app/src/main/java/com/lightmeter/rawmeter/EvaluativeFrameAnalyzer.kt",
    "app/src/main/java/com/lightmeter/rawmeter/RawMeterBridge.kt",
    "app/src/main/java/com/lightmeter/rawmeter/MeteringAnalysis.kt",
    "app/src/main/cpp/evaluative_raw_sampler.h",
    "app/src/main/cpp/raw_meter.cpp",
    "app/src/test/resources/global-metering-mapping-v1.tsv",
)


def capture(workspace):
    workspace = Path(workspace)
    contract = json.loads((workspace/SOURCES[0]).read_text(encoding="utf-8"))
    expected = dict(domain_version=DOMAIN_VERSION,feature_version=FEATURE_VERSION,
                    applies_to_saved_metering_mode="AVERAGE",sample_width=96,sample_height=64,
                    sample_count=6144,grid_columns=12,grid_rows=8,samples_per_cell_edge=8,feature_count=776)
    for name,value in expected.items():
        if contract.get(name) != value: raise ValueError("Application/training input mismatch: "+name)
    kotlin = (workspace/SOURCES[1]).read_text(encoding="utf-8")
    for name,value in dict(DOMAIN_VERSION=DOMAIN_VERSION,FEATURE_VERSION=FEATURE_VERSION,
                           MAPPING_VERSION=contract["mapping_version"]).items():
        if not re.search(r'const val '+name+r'\s*=\s*"'+re.escape(value)+r'"',kotlin):
            raise ValueError("Kotlin mapping version differs: "+name)
    return contract, {name:sha256(workspace/name) for name in SOURCES}
