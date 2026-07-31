# NOP-style dual-issue refactor

This directory now implements the timing-oriented refactor while retaining the
in-order, precise-commit dual-issue architecture.

## Backend timing boundaries

- `ID -> RRD`, `RRD -> EX`, `EX -> AG/M1`, and `M1 -> M2` use local,
  non-flowing queues.  Enqueue readiness depends only on each queue's
  registered count and never borrows a same-cycle dequeue slot.
- Queue and WB payload registers are unreset.  Reset/flush affects only
  valid/count/pointer state.
- Payload types shrink after EX, after M1, and before WB so prediction/decode,
  MDU/CACOP, and LSU-only fields do not cross unnecessary stages.
- Forwarding uses parallel comparisons, youngest-producer priority encoding,
  and one `Mux1H`.  DCache and multiplier outputs enter registered result
  queues before they can forward.
- A blocked lane 1 downgrades a pair to lane 0; lane 1 can never pass a blocked
  lane 0.
- Two-bit epochs tag packets and multiplier/DCache results.  Redirects advance
  the epoch and stale queued results are discarded on mismatch.

## MDU

- The production multiplier is the `multiplier` black box from
  `multiplier.xci`: unsigned 32x32, DSP, speed optimized, two stages, II=1,
  with no CE/reset/flush pins.
- Signed high multiply uses magnitudes and a registered sign tag.  Low multiply
  and unsigned high multiply use the raw product.
- Multiply launches no longer stall EX.  Results are paired with packets in a
  local four-entry result queue.
- Division remains the existing single-request `div_gen_0` wrapper.

Production Vivado projects use only the two native XCI IPs.  Do not add a
local `multiplier_sim.sv` or `div_gen_0_sim.sv` alongside their vendor models.

## Frontend

- BTB and PHT payloads use synchronous RAMs; capacity is unchanged.
- F0 launches PC/predictor/TLB work, F1 consumes registered translation and RAM
  results, and F2 aligns the returned 128-bit sector before FetchQ.
- An eight-entry RAS and GHR/RAS snapshots support local misprediction repair.
- Predictor training remains a registered interface.  There is no
  backend-to-fetch training bypass.

## LSU and caches

- A local four-entry Store Buffer accepts translated stores, marks them
  committed at WB, preserves committed entries across flushes, and drains them
  to DCache independently.
- Load forwarding performs a four-entry LSU-local CAM and selects each byte
  from the youngest matching store.  Partial forwarding merges with the
  registered DCache word.
- ICache and DCache are 8 KiB, two-way, 64-byte-line caches with 64 sets.
  Each line is four independent 128-bit sectors.
- Refill is 16 32-bit AXI beats (`rd_type == 6`).  Dirty DCache eviction sends
  four independent 128-bit sector write transactions; no 512-bit bus crosses
  a module boundary.

## Build and IP import

Generate production RTL with:

```powershell
sbt -batch "runMain mycpu.Elaborate --target-dir generated/final-nop-refactor"
```

Elaboration generates RTL only; it intentionally does not copy any XCI.
Generate `multiplier` and `div_gen_0` independently with the current Vivado
using `scripts/create_multiplier_ip.tcl` and `scripts/create_divider_ip.tcl`,
then add the two native XCI files to the Vivado project.  This avoids importing
NOP-Core's Vivado 2019.2 multiplier libraries into a project that also uses a
current Divider Generator.  See `VIVADO_ARITHMETIC_IP_GUIDE.md` for the exact
parameters and commands.

## Verification completed locally

- SBT compile and full `core_top` elaboration.
- WSL Verilator production-RTL lint using the two simulation IP models.
- Directed synthesizable smoke covering:
  - local FIFO no-pop-borrow behavior and flush;
  - lane-1 blocked/lane-0 progress and no overtaking;
  - Store Buffer youngest-byte forwarding, commit, drain, and flush;
  - consecutive multiply launches for low, signed-high, and unsigned-high;
  - registered synchronous predictor training;
  - 64-byte I/D Cache refill, sector response, and subsequent hit.

Post-route WNS/TNS and benchmark cycle targets still require importing the
generated snapshot plus both vendor IPs into the Chiplab Vivado project.  No
frequency claim should be made from RTL lint alone.
