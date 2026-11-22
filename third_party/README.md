# Third-party assets for full/lean release

This directory holds the third-party jars, model weights, and license texts required by the
**full** release. The assets are tracked in this repository so a clean clone can reproduce the
full jar; see `THIRD_PARTY_NOTICES.md` for licenses.


## Release modes

- **Full (default)**: `./gradlew clean jar` bundles Kronos weights + JQuantLib and produces
  `financialmarket-1.0.0-full.jar`.
- **Lean**: `./gradlew jar -PbundleModels=false -PbundleJQuantLib=false` produces
  `financialmarket-1.0.0.jar`; players/operators supply models externally.

## Kronos

- Path: `third_party/models/kronos/` (or `.models/kronos/`)
- Files: `kronos-mini.safetensors`, `kronos-tokenizer.safetensors`
- License: MIT (NeoQuasar/Kronos-mini, NeoQuasar/Kronos-Tokenizer-base)
- Full release: bundled as classpath resources under
  `assets/financialmarket/models/kronos/`.
- External override:
  ```text
  -Dfinancialmarket.models.dir=/absolute/path/to/kronos
  ```
  or `config/financialmarket/models/kronos/`.
- If missing, `TablPriceModel` falls back to the handwritten GBM/GARCH path.

## MCEF

- Path: `libs/mcef-neoforge-2.1.6-1.21.1.jar`
- License: LGPL-2.1 (`third_party/licenses/MCEF-LGPL-2.1.txt`)
- Required external runtime dependency; not unpacked into the mod jar.
- Players/operators install the MCEF mod separately in `mods/`.

## JQuantLib

- Path: `third_party/jquantlib/jquantlib-core-0.3.0.jar`
- License: BSD-2-Clause (see upstream POM)
- Full release: classes are unpacked into the mod jar root; `JQuantLibBridge` loads them reflectively.
- Lean release: not bundled; `JQuantLibBridge` uses the internal DCF curve.
- No `jarJar` nesting is required because the full jar contains the classes directly.

## finmath-lib

- Path: `third_party/finmath/finmath-lib-6.1.10.jar`
- Dependency: `third_party/finmath/commons-math3-3.6.1.jar`
- License: Apache-2.0 (both)
- Use: CZCE option Black-76 pricing, Greeks and IV (`CzceOptionMarket`)
- Full release: both jars are unpacked into the mod jar root.
- Lean release: not bundled; option API will fail if finmath classes are absent.

## fsynth

- No fsynth code or package is bundled. `FsynthFundamentals` is an independent Java implementation
  **inspired by** fsynth's Heston + jump-diffusion structure.
