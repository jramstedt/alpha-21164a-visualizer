# Alpha 21164A (EV56) Pipeline Visualizer

An IntelliJ Platform plugin (designed for **CLion** and **IntelliJ IDEA**) that tracks and visualizes **DEC Alpha 21164A (EV56)** quad-issue pipeline slot usage, instruction cache fetches, memory buffer stalls, and branch misprediction penalties directly in the editor gutter for **GNU GAS** assembly (`.s`, `.S`, `.asm`, `.inc`).

---

## Disclaimer

> **Might not work at all:** AI generated for personal educational purposes
> **Accuracy & Simulation Notice:** This plugin uses a static, heuristic microarchitectural model of the DEC Alpha 21164A (EV56) processor. While it models EV56 execution slotting rules, instruction latencies, Miss Address Files (MAF), Write Buffers (WBUF), and I-Cache fetch boundaries, **the cycle counts, stall predictions, and cache behaviors are approximations and may not be accurate or cycle-exact** compared to real EV56 silicon or full hardware RTL simulators. Dynamic runtime conditions—such as actual L1/L2/B-cache hit rates, TLB misses, DRAM page conflicts, replay traps, and dynamic branch predictor history—cannot be fully determined from static source files. Do not rely on these estimates as a substitute for hardware performance counters or cycle-accurate architectural simulation.

---

## Features

* **Quad-Issue Slot Tracking (`E0`, `E1`, `FA`, `FM`):** Models EV56 pipeline slotting rules across both integer pipelines (`E0`, `E1`) and both floating-point pipelines (`FA`, `FM`).
* **Complete EV56 Instruction Set Support:** Decodes integer ALU, Byte/Word Extensions (BWX), Motion Video Instructions (MVI), Int↔FP transfers (FIX), IEEE/VAX floating-point operations (including `/s`, `/u`, `/c`, `/m`, `/d` qualifiers), non-pipelined divide/multiply unit occupancy, and GNU GAS pseudo-instructions (`mov`, `clr`, `nop`, `fnop`, `unop`, `fmov`, `fneg`, `fabs`).
* **I-Cache Fetch & Alignment Visualization (`IF`):** Tracks synthetic PC addresses, `.align` directives, 16-byte (4-instruction) IDU octaword fetch boundaries, 32-byte L1 I-cache line crossings, and unaligned branch targets.
* **Memory Subsystem Modeling (MAF & WBUF):**
  * **6-Entry Load MAF:** Models 32-byte cache line merging and pipeline stalls when 6 outstanding L1 data load misses are saturated.
  * **4-Entry IDU / Prefetch MAF:** Models non-blocking software prefetches (`fetch`, `fetch_m`, or loads targeting `$31`/`$f31`) and instruction fetch misses.
  * **6-Entry Write Buffer (WBUF):** Models 32-byte store merging, `wmb`/`mb` flush barriers, store-to-load conflicts, and MTU priority arbitration (where data load misses take priority over WBUF drains).
* **Directive & Comment Aware:** Ignores `#`, `//`, and `/* ... */` comments and evaluates both GNU GAS (`.if`, `.ifdef`, `.ifndef`, `.elseif`, `.else`, `.endif`, `.set`, `.equ`) and C preprocessor (`#if`, `#ifdef`, `#ifndef`, `#elif`, `#else`, `#endif`, `#define`, `#undef`) conditional blocks so inactive code paths do not skew PC alignment or register dependencies.

---

## Gutter Color Codes & Visual Legend

Tracks are drawn exclusively on lines containing executable instructions, inside a dedicated gutter annotation column that does not overlap line numbers, breakpoints, or code-folding arrows.

### 1. Execution Slot Tracks (`E0`, `E1`, `FA`, `FM`)

| Pipeline Track | Execution Unit |
| :--- | :--- |
| **`E0`** | Integer 0 — All integer ALU ops, shifts, BWX, MVI, integer multiply, loads/stores, branches, and indirect jumps |
| **`E1`** | Integer 1 — Basic integer ALU ops (`add*`, `sub*`, logicals, `cmov*`, `lda*`) and conditional/unconditional branches |
| **`FA`** | Floating-Point Add — FP add, subtract, compare, convert, `fcmov*`, sign/move ops, and FP branches |
| **`FM`** | Floating-Point Multiply — Pipelined FP multiply (`muls`, `mult`) and non-pipelined FP divide (`divs`, `divt`) |

* **Green Vertical Line:** The instruction issues cleanly into that pipeline slot without stalling.
* **Red Vertical Line + Dashed Connector (`-Nc`):** The instruction is assigned to that slot, but stalls for **`N` cycles** before issuing due to a RAW data hazard, structural slotting conflict, non-pipelined divider/multiplier occupancy, MAF saturation, or WBUF drain/full stall.
* **Gray Vertical Line:** The pipeline slot is idle/unused during that instruction's issue cycle.

### 2. Instruction Cache Fetch Track (`IF`)

* **Blue Vertical Line:** Normal 16-byte (4-instruction) aligned octaword fetch from the 8 KB L1 I-Cache.
* **Orange Vertical Line:** Unaligned branch target (a label that is not 16-byte aligned, reducing initial IDU fetch width).
* **Red Vertical Line (`IF-Nc`):** L1 I-Cache miss occupying one of the 4 IDU/Prefetch MAF entries while filling from the L2 S-cache.
* **Solid Blue Horizontal Divider:** Marks a **32-byte (8-instruction)** L1 I-Cache line boundary.
* **Dashed Gray Horizontal Tick:** Marks a **16-byte (4-instruction)** IDU octaword fetch boundary.

### 3. Branch Misprediction Penalties

* **Orange Label (`B:5` / `B:6`):** Displayed to the right of the `FM` track on branch and jump instructions:
  * **`B:5`:** Conditional integer or floating-point branch (`beq`, `bne`, `fbeq`, etc.) — 5-cycle misprediction penalty.
  * **`B:6`:** Indirect jump or subroutine return (`jmp`, `jsr`, `ret`, `jsr_coroutine`) — 6-cycle misprediction penalty.

---

## Inline Simulation Hints

You can simulate cache misses on specific lines using inline comments:

```gas
ldq     $1, 0($16)      # miss          # Forces an L1 D-cache miss (allocates a Load MAF entry)
loop:                   # icache_miss   # Forces an L1 I-cache miss on the 32-byte fetch line
```

---

## Building & Installation

### Build from Source (Linux / macOS / CI)

Requires **JDK 21** and **Gradle** (or the Gradle wrapper):

```bash
gradle clean buildPlugin
```

The installable plugin archive is generated at:
```text
build/distributions/alpha-21164a-visualizer-1.1.2.zip
```

### Install in CLion or IntelliJ IDEA

1. Open **CLion** or **IntelliJ IDEA**.
2. Open **Settings / Preferences** (`Ctrl+Alt+S` or `Cmd+,`) → **Plugins**.
3. Click the **Gear Icon (⚙️)** → **Install Plugin from Disk...**.
4. Select `alpha-21164a-visualizer-1.1.2.zip` and restart the IDE.
