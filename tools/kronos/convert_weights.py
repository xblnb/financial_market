"""Convert Kronos safetensors weights into the distributable in-jar format.

Source checkpoints (MIT licence):
  * NeoQuasar/Kronos-mini             (4.1M params, 5-dim K-line transformer)
  * NeoQuasar/Kronos-Tokenizer-base   (BSQ tokenizer, 20-bit codebook)

The mod ships these weights inside the jar as *fp16* safetensors so that the
runtime needs no Python, no native library and no network access.  One
dimensional tensors (norms, biases, rope frequencies) stay fp32 because they
are tiny and numerically sensitive.

Usage:
    python tools/kronos/convert_weights.py <src_dir> <dst_dir>

<src_dir> must contain mini.safetensors and tokenizer.safetensors (the raw
HuggingFace checkpoints).  The output is written as
    <dst_dir>/kronos-mini.safetensors
    <dst_dir>/kronos-tokenizer.safetensors
"""

from __future__ import annotations

import json
import os
import struct
import sys

import numpy as np

ST_DTYPES = {
    "F32": np.dtype("<f4"),
    "F16": np.dtype("<f2"),
    "BF16": np.dtype("<u2"),
    "I64": np.dtype("<i8"),
    "I32": np.dtype("<i4"),
}
OUT_DTYPES = {"F32": np.dtype("<f4"), "F16": np.dtype("<f2"), "I64": np.dtype("<i8")}


def read_safetensors(path: str):
    with open(path, "rb") as fh:
        raw = fh.read()
    n = struct.unpack("<Q", raw[:8])[0]
    header = json.loads(raw[8 : 8 + n])
    base = 8 + n
    tensors = {}
    for name, meta in header.items():
        if name == "__metadata__":
            continue
        start, end = meta["data_offsets"]
        dtype = ST_DTYPES[meta["dtype"]]
        arr = np.frombuffer(raw, dtype=dtype, count=(end - start) // dtype.itemsize, offset=base + start)
        tensors[name] = (meta["dtype"], tuple(meta["shape"]), arr.copy())
    return tensors


def write_safetensors(path: str, tensors) -> None:
    header = {}
    blobs = []
    offset = 0
    for name in sorted(tensors):
        src_dtype, shape, arr = tensors[name]
        ndim = len(shape)
        if src_dtype == "F32" and ndim >= 2:
            out_dtype, arr = "F16", arr.astype(OUT_DTYPES["F16"])
        elif src_dtype in ("F32", "I64"):
            out_dtype = src_dtype
            arr = arr.astype(OUT_DTYPES[src_dtype])
        else:  # F16/BF16 input: keep the values, promote 1-D helpers to fp32
            out_dtype = "F32" if ndim < 2 else "F16"
            arr = arr.astype(OUT_DTYPES[out_dtype])
        data = arr.tobytes(order="C")
        header[name] = {
            "dtype": out_dtype,
            "shape": list(shape),
            "data_offsets": [offset, offset + len(data)],
        }
        blobs.append(data)
        offset += len(data)
    header["__metadata__"] = {"format": "pt", "converted": "financialmarket-kronos-fp16"}
    header_bytes = json.dumps(header, separators=(",", ":")).encode("utf-8")
    with open(path, "wb") as fh:
        fh.write(struct.pack("<Q", len(header_bytes)))
        fh.write(header_bytes)
        for blob in blobs:
            fh.write(blob)


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    src, dst = sys.argv[1], sys.argv[2]
    os.makedirs(dst, exist_ok=True)
    jobs = [
        ("mini.safetensors", "kronos-mini.safetensors"),
        ("tokenizer.safetensors", "kronos-tokenizer.safetensors"),
    ]
    for src_name, dst_name in jobs:
        tensors = read_safetensors(os.path.join(src, src_name))
        out = os.path.join(dst, dst_name)
        write_safetensors(out, tensors)
        print(f"{src_name} -> {out}  tensors={len(tensors)}  bytes={os.path.getsize(out)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
