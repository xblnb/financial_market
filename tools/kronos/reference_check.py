"""NumPy reference for the pure-Java Kronos port.

This script is the *independent* oracle used to verify
``com.example.financialmarket.engine.ml``: it re-implements the PyTorch module
semantics (Kronos tokenizer + Kronos-mini decoder) with numpy and dumps the
intermediate values that the Java probe also dumps.  Both sides load exactly the
same fp16 safetensors that ship inside the mod jar, so the comparison isolates
implementation bugs instead of quantisation drift.

Usage:
    python tools/kronos/reference_check.py <resources_dir> <out_dir>
"""

from __future__ import annotations

import json
import os
import struct
import sys

import numpy as np

ST = {"F32": np.float32, "F16": np.float16, "BF16": np.dtype("<u2"), "I64": np.int64}


def load(path):
    with open(path, "rb") as fh:
        raw = fh.read()
    n = struct.unpack("<Q", raw[:8])[0]
    header = json.loads(raw[8 : 8 + n])
    base = 8 + n
    out = {}
    for name, meta in header.items():
        if name == "__metadata__":
            continue
        s, e = meta["data_offsets"]
        dt = ST[meta["dtype"]]
        if meta["dtype"] == "F16":
            arr = np.frombuffer(raw, dtype="<f2", count=(e - s) // 2, offset=base + s).astype(np.float32)
        elif meta["dtype"] == "F32":
            arr = np.frombuffer(raw, dtype="<f4", count=(e - s) // 4, offset=base + s).astype(np.float32)
        elif meta["dtype"] == "I64":
            arr = np.frombuffer(raw, dtype="<i8", count=(e - s) // 8, offset=base + s)
        else:
            raise ValueError(meta["dtype"])
        out[name] = arr.reshape(meta["shape"])
    return out


def linear(x, w, b=None):
    y = x @ w.T
    if b is not None:
        y = y + b
    return y


def rmsnorm(x, w, eps=1e-5):
    scale = 1.0 / np.sqrt(np.mean(x * x, axis=-1, keepdims=True) + eps)
    return x * scale * w


def rotate(x, t_len, heads, hd, inv_freq):
    """Rotary embedding for a (T, H*hd) tensor, matching the torch implementation."""
    x = x.reshape(t_len, heads, hd)
    freqs = np.outer(np.arange(t_len, dtype=np.float32), inv_freq.astype(np.float32))
    emb = np.concatenate([freqs, freqs], axis=-1)  # (T, hd)
    cos = np.cos(emb)[:, None, :]
    sin = np.sin(emb)[:, None, :]
    x1, x2 = x[..., : hd // 2], x[..., hd // 2 :]
    out = np.concatenate([x1 * cos[..., : hd // 2] - x2 * sin[..., : hd // 2],
                          x2 * cos[..., hd // 2 :] + x1 * sin[..., hd // 2 :]], axis=-1)
    return out.reshape(t_len, heads * hd)


def attention(q, k, v, t_len, heads, hd, causal=True):
    q = q.reshape(t_len, heads, hd).transpose(1, 0, 2)  # (H,T,hd)
    k = k.reshape(t_len, heads, hd).transpose(1, 0, 2)
    v = v.reshape(t_len, heads, hd).transpose(1, 0, 2)
    scores = np.einsum("htd,hsd->hts", q, k) / np.sqrt(hd)
    if causal:
        mask = np.triu(np.ones((t_len, t_len), dtype=bool), k=1)
        scores = np.where(mask[None], -np.inf, scores)
    m = scores.max(axis=-1, keepdims=True)
    e = np.exp(scores - m)
    probs = e / e.sum(axis=-1, keepdims=True)
    out = np.einsum("hts,hsd->htd", probs, v).transpose(1, 0, 2).reshape(t_len, heads * hd)
    return out


class Block:
    def __init__(self, w, prefix, d, heads, ff):
        self.d, self.heads, self.hd, self.ff = d, heads, d // heads, ff
        g = lambda n: w[f"{prefix}.{n}"]
        self.norm1 = g("norm1.weight")
        self.wq, self.bq = g("self_attn.q_proj.weight"), g("self_attn.q_proj.bias")
        self.wk, self.bk = g("self_attn.k_proj.weight"), g("self_attn.k_proj.bias")
        self.wv, self.bv = g("self_attn.v_proj.weight"), g("self_attn.v_proj.bias")
        self.wo, self.bo = g("self_attn.out_proj.weight"), g("self_attn.out_proj.bias")
        self.inv = g("self_attn.rotary.inv_freq")
        self.norm2 = g("norm2.weight")
        self.w1, self.w2, self.w3 = g("ffn.w1.weight"), g("ffn.w2.weight"), g("ffn.w3.weight")

    def forward(self, x):
        t = x.shape[0]
        h = rmsnorm(x, self.norm1)
        q = linear(h, self.wq, self.bq)
        k = linear(h, self.wk, self.bk)
        v = linear(h, self.wv, self.bv)
        q = rotate(q, t, self.heads, self.hd, self.inv)
        k = rotate(k, t, self.heads, self.hd, self.inv)
        a = attention(q, k, v, t, self.heads, self.hd, causal=True)
        x = x + linear(a, self.wo, self.bo)
        h = rmsnorm(x, self.norm2)
        gate = linear(h, self.w1)
        up = linear(h, self.w3)
        gate = gate / (1.0 + np.exp(-gate))
        x = x + linear(gate * up, self.w2)
        return x


def run_tokenizer(tok, x, s1_bits=10, s2_bits=10):
    d = 256
    rows = x.shape[0]
    z = linear(x, tok["embed.weight"], tok["embed.bias"])
    for i in range(3):
        z = Block(tok, f"encoder.{i}", d, 4, 512).forward(z)
    q = linear(z, tok["quant_embed.weight"], tok["quant_embed.bias"])
    q = q / np.maximum(np.linalg.norm(q, axis=-1, keepdims=True), 1e-12)
    s1 = np.zeros(rows, dtype=np.int64)
    s2 = np.zeros(rows, dtype=np.int64)
    for r in range(rows):
        for k in range(s1_bits):
            if q[r, k] >= 0:
                s1[r] |= 1 << k
        for k in range(s2_bits):
            if q[r, s1_bits + k] >= 0:
                s2[r] |= 1 << k
    return s1, s2


def decode_tokenizer(tok, s1, s2, s1_bits=10, s2_bits=10):
    rows = len(s1)
    cb = s1_bits + s2_bits
    bits = np.zeros((rows, cb), dtype=np.float32)
    q_scale = 1.0 / np.sqrt(cb)
    for r in range(rows):
        for k in range(s1_bits):
            bits[r, k] = (1.0 if (s1[r] >> k) & 1 else -1.0) * q_scale
        for k in range(s2_bits):
            bits[r, s1_bits + k] = (1.0 if (s2[r] >> k) & 1 else -1.0) * q_scale
    z = linear(bits, tok["post_quant_embed.weight"], tok["post_quant_embed.bias"])
    for i in range(3):
        z = Block(tok, f"decoder.{i}", 256, 4, 512).forward(z)
    return linear(z, tok["head.weight"], tok["head.bias"])


def embed_model(m, s1, s2, stamp):
    d = 256
    rows = len(s1)
    e1 = m["embedding.emb_s1.weight"][s1] * np.sqrt(d)
    e2 = m["embedding.emb_s2.weight"][s2] * np.sqrt(d)
    x = linear(np.concatenate([e1, e2], axis=-1), m["embedding.fusion_proj.weight"],
               m["embedding.fusion_proj.bias"])
    tables = ["minute_embed", "hour_embed", "weekday_embed", "day_embed", "month_embed"]
    time = np.zeros_like(x)
    for f, name in enumerate(tables):
        time += m[f"time_emb.{name}.weight"][stamp[:, f]]
    return x + time


def run_model_s1(m, s1, s2, stamp):
    x = embed_model(m, s1, s2, stamp)
    for i in range(4):
        x = Block(m, f"transformer.{i}", 256, 4, 512).forward(x)
    x = rmsnorm(x, m["norm.weight"])
    logits = linear(x, m["head.proj_s1.weight"], m["head.proj_s1.bias"])
    return logits, x


def run_model_s2(m, context, s1_token):
    d = 256
    heads, hd = 4, 64
    rows = context.shape[0]
    sib = m["embedding.emb_s1.weight"][s1_token] * np.sqrt(d)
    q = linear(sib[None, :], m["dep_layer.cross_attn.q_proj.weight"], m["dep_layer.cross_attn.q_proj.bias"])
    k = linear(context, m["dep_layer.cross_attn.k_proj.weight"], m["dep_layer.cross_attn.k_proj.bias"])
    v = linear(context, m["dep_layer.cross_attn.v_proj.weight"], m["dep_layer.cross_attn.v_proj.bias"])
    inv = m["dep_layer.cross_attn.rotary.inv_freq"]
    q = rotate(q, 1, heads, hd, inv)
    k = rotate(k, rows, heads, hd, inv)
    # non-causal cross attention with q_len = 1
    qq = q.reshape(1, heads, hd).transpose(1, 0, 2)
    kk = k.reshape(rows, heads, hd).transpose(1, 0, 2)
    vv = v.reshape(rows, heads, hd).transpose(1, 0, 2)
    scores = np.einsum("htd,hsd->hts", qq, kk) / np.sqrt(hd)
    e = np.exp(scores - scores.max(axis=-1, keepdims=True))
    probs = e / e.sum(axis=-1, keepdims=True)
    att = np.einsum("hts,hsd->htd", probs, vv).transpose(1, 0, 2).reshape(1, heads * hd)
    att = linear(att, m["dep_layer.cross_attn.out_proj.weight"], m["dep_layer.cross_attn.out_proj.bias"])
    x2 = rmsnorm(context[-1:] + att, m["dep_layer.norm.weight"])
    return linear(x2, m["head.proj_s2.weight"], m["head.proj_s2.bias"])[0]


def fixture(n=16):
    x = np.zeros((n, 6), dtype=np.float32)
    for i in range(n):
        base = 100.0 + 8.0 * np.sin(0.7 * i)
        x[i, 0] = np.float32(base)
        x[i, 1] = np.float32(base + 1.5 + 0.3 * (i % 3))
        x[i, 2] = np.float32(base - 1.2 - 0.2 * (i % 4))
        x[i, 3] = np.float32(base + 0.4 * np.cos(0.9 * i))
        x[i, 4] = np.float32(1000 + 37 * i)
        x[i, 5] = np.float32((1000 + 37 * i) * base)
    stamp = np.zeros((n, 5), dtype=np.int64)
    for i in range(n):
        stamp[i] = [i * 5 % 60, 9 + i // 12, i % 7, 1 + i % 28, 1 + i % 12]
    return x, stamp


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    res, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    tok = load(os.path.join(res, "kronos-tokenizer.safetensors"))
    mdl = load(os.path.join(res, "kronos-mini.safetensors"))

    x, stamp = fixture()
    x = (x - x.mean(axis=0)) / (x.std(axis=0) + 1e-5)
    x = np.clip(x, -5, 5)

    s1, s2 = run_tokenizer(tok, x)
    decoded = decode_tokenizer(tok, s1, s2)
    logits, context = run_model_s1(mdl, s1, s2, stamp)
    s1_last = logits[-1]
    s1_token = int(np.argmax(s1_last))
    s2_logits = run_model_s2(mdl, context, s1_token)

    np.savetxt(os.path.join(out, "ref_tok_s1.txt"), s1, fmt="%d")
    np.savetxt(os.path.join(out, "ref_tok_s2.txt"), s2, fmt="%d")
    np.savetxt(os.path.join(out, "ref_tok_decoded.txt"), decoded, fmt="%.8g")
    np.savetxt(os.path.join(out, "ref_s1_logits.txt"), s1_last, fmt="%.8g")
    np.savetxt(os.path.join(out, "ref_s2_logits.txt"), s2_logits, fmt="%.8g")
    with open(os.path.join(out, "ref_summary.json"), "w", encoding="utf-8") as fh:
        json.dump({"s1_argmax": s1_token, "s1_max": float(s1_last.max()), "n": int(len(s1))}, fh)

    print("tokens s1:", s1[:8].tolist())
    print("tokens s2:", s2[:8].tolist())
    print("decoded[0]:", np.round(decoded[0], 5).tolist())
    print("s1 argmax:", s1_token, "logit:", float(s1_last[s1_token]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
