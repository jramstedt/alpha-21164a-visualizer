/* ============================================================================
 * Alpha 21164A (EV56) Pipeline Visualizer - Comprehensive Test Suite
 * File: ev56_pipeline_test.S
 * ============================================================================ */

#define USE_FAST_PATH 1

        .text
        .set    noat
        .set    noreorder

        .globl  ev56_test_suite
        .ent    ev56_test_suite

/* ----------------------------------------------------------------------------
 * TEST 1: Quad-Issue Packing (E0 + E1 + FA + FM in a Single Cycle)
 * Expected:
 *   - All 4 instructions share Cycle 0 with a green vertical grouping bracket
 *   - ldq -> E0, addq -> E1, addt -> FA, mult -> FM (0c stalls)
 * ---------------------------------------------------------------------------- */
        .align  5                       # Align to 32-byte I-Cache line boundary
ev56_test_suite:
        ldgp    $gp, 0($27)             # E0/E1, 2-cycle latency (ldah + lda)
        lda     $sp, -64($sp)

        .align  4                       # Align to 16-byte octaword fetch boundary
test_quad_issue:
        ldq     t0, 0(a0)               # E0 (Green) - Cycle N
        addq    a1, 1, t1               # E1 (Green) - Cycle N (paired)
        addt    fa0, fa1, fa2           # FA (Green) - Cycle N (paired)
        mult    fa3, fa4, fa5           # FM (Green) - Cycle N (paired)

/* ----------------------------------------------------------------------------
 * TEST 2: Generalized E0/E1 Dual-Issue & 1-Cycle Integer Bypass (0c Stall)
 * Expected:
 *   - srl (E0) + addq (E1) pair in Cycle N (0c stall)
 *   - sll (E0) + or (E1) pair in Cycle N+1 (0c stall)
 * ---------------------------------------------------------------------------- */
test_dual_issue_shift_alu:
        srl     t10, 63, at             # E0 (Green) - produces $28 (at)
        addq    t12, t12, t12           # E1 (Green) - dual-issues with srl
        sll     t10, 1, t10             # E0 (Green) - next cycle
        or      t12, at, t12            # E1 (Green) - dual-issues with sll (0c stall)

/* ----------------------------------------------------------------------------
 * TEST 3: E0 Structural Conflict (Two Shifts Cannot Dual-Issue)
 * Expected:
 *   - Both shifts require E0; second shift advances to next cycle (0c stall)
 * ---------------------------------------------------------------------------- */
test_structural_e0:
        sll     t0, 4, t1               # E0 (Green)
        srl     t2, 4, t3               # E0 (Green) - next cycle (no green bracket)

/* ----------------------------------------------------------------------------
 * TEST 4: RAW Load-Use & Integer Multiply Stalls
 * Expected:
 *   - ldq L1 hit = 2c latency -> dependent addq shows Red (-1c) stall
 *   - mulq = 12c latency -> dependent subq shows Red (-11c) stall
 * ---------------------------------------------------------------------------- */
test_raw_stalls:
        ldq     t4, 8(a0)               # E0 (Green) - 2-cycle L1 hit
        addq    t4, 10, t5              # E0 (Red, -1c stall waiting for t4)

        mulq    t5, t6, t7              # E0 (Green) - 12-cycle latency
        subq    t7, 1, t8               # E0 (Red, -11c stall waiting for t7)

/* ----------------------------------------------------------------------------
 * TEST 5: Non-Pipelined FP Divide (FM Occupancy Stall)
 * Expected:
 *   - First divt issues on FM (30c latency, 27c unit occupancy)
 *   - Independent second divt stalls on FM (Red, -26c) until divider frees
 * ---------------------------------------------------------------------------- */
test_fp_divide:
        divt    fs0, fs1, fs2           # FM (Green)
        divt    fs3, fs4, fs5           # FM (Red, -26c non-pipelined divider stall)

/* ----------------------------------------------------------------------------
 * TEST 6: 6-Entry Load MAF Saturation & 32-Byte Line Merging + EV56 Prefetch
 * Expected:
 *   - fetch / fetch_m execute as 1-cycle NOPs (no MAF entry allocated)
 *   - lds ft15 ($f31) allocates 1 of 4 non-blocking Prefetch MAF entries
 *   - First 6 distinct cache-line misses fill the 6 Load MAF entries (Green)
 *   - Load to 0(a1) + 8(a1) merges into same 32-byte block (no extra MAF entry)
 *   - 7th distinct cache-line miss stalls (Red) until oldest MAF entry retires
 * ---------------------------------------------------------------------------- */
test_maf_saturation:
        fetch   0(a1)                   # E0/E1 (Green) - Hardware NOP on 21164A
        lds     ft15, 256(a1)           # E0 (Green) - Prefetch into $f31 (4-entry MAF)

        ldq     t0, 0(a1)       # miss  # E0 (Green) - Load MAF 1/6 (block 0)
        ldq     t1, 8(a1)       # miss  # E0 (Green) - Merges into Load MAF 1/6!
        ldq     t2, 32(a1)      # miss  # E0 (Green) - Load MAF 2/6 (block 1)
        ldq     t3, 64(a1)      # miss  # E0 (Green) - Load MAF 3/6 (block 2)
        ldq     t4, 96(a1)      # miss  # E0 (Green) - Load MAF 4/6 (block 3)
        ldq     t5, 128(a1)     # miss  # E0 (Green) - Load MAF 5/6 (block 4)
        ldq     t6, 160(a1)     # miss  # E0 (Green) - Load MAF 6/6 (block 5 - FULL)
        ldq     t7, 192(a1)     # miss  # E0 (Red)   - Stalls waiting for MAF 1/6!

/* ----------------------------------------------------------------------------
 * TEST 7: 6-Entry Write Buffer (WBUF) Merging, Saturation & wmb Barrier
 * Expected:
 *   - 4 stores to 0..24(a2) merge into a single 32-byte WBUF entry (Green)
 *   - 6 distinct 32-byte blocks fill WBUF; 7th block stalls (Red)
 *   - wmb flushes pending WBUF entries (Red stall until drained)
 * ---------------------------------------------------------------------------- */
test_write_buffer:
        stq     t0, 0(a2)               # E0 (Green) - WBUF 1/6 (block 0)
        stq     t1, 8(a2)               # E0 (Green) - Merges into WBUF 1/6
        stq     t2, 16(a2)              # E0 (Green) - Merges into WBUF 1/6
        stq     t3, 24(a2)              # E0 (Green) - Merges into WBUF 1/6

        stq     t0, 32(a2)              # E0 (Green) - WBUF 2/6
        stq     t0, 64(a2)              # E0 (Green) - WBUF 3/6
        stq     t0, 96(a2)              # E0 (Green) - WBUF 4/6
        stq     t0, 128(a2)             # E0 (Green) - WBUF 5/6
        stq     t0, 160(a2)             # E0 (Green) - WBUF 6/6 (FULL)
        stq     t0, 192(a2)             # E0 (Red)   - WBUF Full stall!
        wmb                             # E0 (Red)   - Drains remaining WBUF entries

/* ----------------------------------------------------------------------------
 * TEST 8: I-Cache Fetch Alignment & Branch Misprediction Penalties
 * Expected:
 *   - Unaligned label shows Orange IF bar
 *   - # icache_miss shows Red IF bar with IF-8c stall
 *   - Conditional branches show B:5; ret shows B:6
 * ---------------------------------------------------------------------------- */
test_icache_and_branches:
        nop                             # +0x00 (16B aligned)
unaligned_target:                       # +0x04 -> Orange IF bar (not 16B aligned!)
        bne     t0, test_quad_issue     # E0/E1 (Green) + Orange "B:5"

        .align  5                       # +0x00 on new 32B I-Cache line
icache_miss_target:     # icache_miss   # Red IF bar ("IF-8c")
        fbeq    fa0, test_quad_issue    # FA (Green) + Orange "B:5"

/* ----------------------------------------------------------------------------
 * TEST 9: Preprocessor (#ifdef) & GNU GAS (.if) Conditional Filtering
 * Expected:
 *   - Active #if USE_FAST_PATH branch renders tracks normally
 *   - Inactive #else and .if 0 branches show NO tracks and add NO dependencies
 * ---------------------------------------------------------------------------- */
#if USE_FAST_PATH
        addq    v0, 1, v0               # E0/E1 (Green) - Active branch
#else
        divt    fs0, fs1, fs0           # Ignored! (No gutter tracks, no 30c stall)
        mulq    v0, v0, v0              # Ignored!
#endif

.if 0
        ldq     v0, 0(zero)     # miss  # Ignored!
.else
        mov     v0, a0                  # E0/E1 (Green) - Active .else branch
.endif

        lda     sp, 64(sp)
        ret     zero, (ra), 1           # E0 (Green) + Orange "B:6"
        .end    ev56_test_suite
