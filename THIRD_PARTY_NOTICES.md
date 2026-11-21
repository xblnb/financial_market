# Third-party notices

The default **full** release jar bundles the third-party assets below. The optional lean jar does not.

| Component | Use | License | Distribution |
|---|---|---|---|
| Kronos-mini / Kronos-Tokenizer-base fp16 weights | TABL/Kronos anchors | MIT | Full jar: bundled under `assets/financialmarket/models/kronos/` with NOTICE. Lean: external |
| JQuantLib core 0.3.0 | DCF discounting in `JQuantLibBridge` | BSD-2-Clause | Full jar: classes unpacked at jar root + this notice. Lean: external |
| finmath-lib 6.1.10 | CZCE option Black-76 / Greeks | Apache-2.0 | Full jar: classes unpacked at jar root + Apache license |
| Apache Commons Math 3.6.1 | finmath normal distribution dependency | Apache-2.0 | Full jar: classes unpacked at jar root + Apache license |
| fsynth | conceptual inspiration for `FsynthFundamentals` | no code bundled | independent Java implementation |

Kronos upstream: https://github.com/shiyu-coder/Kronos  
JQuantLib: https://github.com/dv01-inc/jquantlib  
finmath-lib: https://github.com/finmath/finmath-lib  
Apache Commons Math: https://commons.apache.org/proper/commons-math/  
fsynth: https://github.com/welcra/fsynth

See `assets/financialmarket/models/kronos/NOTICE.txt` (all releases) for the Kronos MIT notice.
The full jar also contains this notice at `META-INF/THIRD_PARTY_NOTICES.md` and the
Apache/BSD/MIT texts under `META-INF/licenses/`.
