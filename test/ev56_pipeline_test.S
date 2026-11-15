/* ============================================================================
 * Alpha 21164A (EV56) Pipeline Visualizer - Extended Edge Cases & Kernels
 * File: ev56_pipeline_test.S
 * ============================================================================ */

#define ENABLE_MVI_PATH 1
#define UNROLL_FACTOR   4

        .text
        .set    noat
        .set    noreorder

        .globl  ev56_extended_suite
        .ent    ev56_extended_suite

        .align  5                       # 32-byte L1 I-Cache line alignment
ev56_extended_suite:
        ldgp    gp, 0(pv)               # E0/E1, 2c latency (ldah + lda)
        lda     sp, -128(sp)
        stq     ra, 0(sp)
        stq     s0, 8(sp)
        stq     s1, 16(sp)
        stt     fs0, 24(sp)
        stt     fs1, 32(sp)

/* ============================================================================
 * CASE 1: Software-Pipelined 4x Unrolled DAXPY Loop (Quad-Issue Showcase)
 * Computes Y[i] = a * X[i] + Y[i] using software prefetching and FP/Int overlap.
 * Expected:
 *   - High quad-issue density across E0 (ld/st/addr), E1 (loop/add), FA (addt), FM (mult)
 *   - Green vertical brackets grouping dual/triple/quad-issued instructions
 *   - Software prefetch via `ldt fzero, 128(a0)` uses 4-entry Prefetch MAF (non-blocking)
 * ============================================================================ */
        .align  4
daxpy_loop:
        ldt     fzero, 128(a0)          # E0 (Green) - Prefetch next cache line X
        ldt     fzero, 128(a1)          # E0 (Green) - Prefetch next cache line Y

        ldt     ft0, 0(a0)              # E0 (Green) - Load X[0]
        ldt     ft1, 8(a0)              # E0 (Green) - Load X[1]
        ldt     ft2, 16(a0)             # E0 (Green) - Load X[2]
        ldt     ft3, 24(a0)             # E0 (Green) - Load X[3]

        mult    fa0, ft0, ft4           # FM (Green) - a * X[0]
        ldt     ft8, 0(a1)              # E0 (Green) - Load Y[0] (dual-issues with mult!)
        mult    fa0, ft1, ft5           # FM (Green) - a * X[1]
        ldt     ft9, 8(a1)              # E0 (Green) - Load Y[1] (dual-issues with mult!)
        mult    fa0, ft2, ft6           # FM (Green) - a * X[2]
        ldt     ft10, 16(a1)            # E0 (Green) - Load Y[2]
        mult    fa0, ft3, ft7           # FM (Green) - a * X[3]
        ldt     ft11, 24(a1)            # E0 (Green) - Load Y[3]

        addt    ft4, ft8, ft12          # FA (Green) - a*X[0] + Y[0]
        addq    a0, 32, a0              # E1 (Green) - Advance X ptr (dual-issues with addt!)
        addt    ft5, ft9, ft13          # FA (Green) - a*X[1] + Y[1]
        subq    a2, 4, a2               # E1 (Green) - Decrement count (dual-issues with addt!)
        addt    ft6, ft10, ft14         # FA (Green) - a*X[2] + Y[2]
        addt    ft7, ft11, ft15         # FA (Green) - a*X[3] + Y[3] (alias ft15=$f31, test with ft0:)
        addt    ft7, ft11, ft0          # FA (Green) - Store result into ft0 ($f10)

        stt     ft12, 0(a1)             # E0 (Green) - Store Y[0] (WBUF 1/6)
        stt     ft13, 8(a1)             # E0 (Green) - Store Y[1] (merges into WBUF 1/6)
        stt     ft14, 16(a1)            # E0 (Green) - Store Y[2] (merges into WBUF 1/6)
        stt     ft0,  24(a1)            # E0 (Green) - Store Y[3] (merges into WBUF 1/6)
        addq    a1, 32, a1              # E1 (Green) - Advance Y ptr (dual-issues with stt!)
        bgt     a2, daxpy_loop          # E1 (Green) + Orange "B:5"

/* ============================================================================
 * CASE 2: Byte/Word Extension (BWX) & Motion Video Instructions (MVI)
 * All BWX shifts/extracts/inserts and MVI multimedia ops execute ONLY in E0.
 * Expected:
 *   - No dual-issue pairing between consecutive BWX/MVI instructions
 *   - sextl (addl alias) CAN dual-issue on E1, whereas sextb/sextw require E0
 * ============================================================================ */
        .align  4
test_bwx_and_mvi:
        ldbu    t0, 0(a0)               # E0 (Green) - BWX byte load (2c latency)
        ldwu    t1, 2(a0)               # E0 (Green) - BWX word load (2c latency)
        sextb   t0, t2                  # E0 (Red, -1c stall waiting for ldbu t0)
        sextw   t1, t3                  # E0 (Green) - BWX sign-extend word (E0 only)
        sextl   t2, t4                  # E1 (Green) - Sign-extend longword (addl alias, E0/E1!)
        extbl   t4, 1, t5               # E0 (Green) - BWX extract byte (dual-issues with sextl!)
        inswl   t5, 2, t6               # E0 (Green) - BWX insert word
        mskql   t6, 3, t7               # E0 (Green) - BWX mask quadword
        zapnot  t7, 0x0f, t8            # E0 (Green) - Zero bytes (E0 only)

#if ENABLE_MVI_PATH
        minub8  t0, t1, t2              # E0 (Green) - MVI unsigned byte min (E0 only)
        maxsw4  t2, t3, t4              # E0 (Green) - MVI signed word max (E0 only)
        perr    t4, t5, t6              # E0 (Green) - MVI pixel error (E0 only)
        pklb    t6, t7                  # E0 (Green) - MVI pack longword to byte (E0 only)
        unpkbw  t7, t8                  # E0 (Green) - MVI unpack byte to word (E0 only)
#endif

/* ============================================================================
 * CASE 3: Conditional Move (cmov / fcmov) Implicit Destination Dependency
 * On Alpha, cmov/fcmov reads BOTH source operands AND the destination register
 * (because the old destination value is preserved if the condition is false).
 * Expected:
 *   - cmovne stalls on its own destination register (t2) written by mulq!
 *   - fcmovlt stalls on its own destination register (fa2) written by mult!
 * ============================================================================ */
        .align  4
test_cmov_dest_hazard:
        mulq    t0, t1, t2              # E0 (Green) - 12-cycle latency writing t2
        addq    a0, 1, a0               # E0/E1 (Green) - Independent
        subq    a1, 1, a1               # E0/E1 (Green) - Independent
        cmovne  a0, a1, t2              # E1 (Red) - Stalls waiting for t2 (dest is also input!)

        mult    fa0, fa1, fa2           # FM (Green) - 4-cycle latency writing fa2
        fcmovlt fs0, fs1, fa2           # FA (Red) - Stalls waiting for fa2 (dest is also input!)

/* ============================================================================
 * CASE 4: Store-to-Load WBUF Conflict & MTU Priority Arbitration
 * Load MAF misses hold highest MTU priority over WBUF drains.
 * Expected:
 *   - Store writes to 0(a3), allocating a WBUF entry
 *   - Subsequent load to 0(a3) hits the active WBUF block -> Red stall until drain
 *   - Simultaneous Load MAF misses delay WBUF draining under MTU arbitration
 * ============================================================================ */
        .align  4
test_wbuf_load_hazard_and_mtu:
        stq     t0, 0(a3)               # E0 (Green) - Allocates WBUF entry for 0(a3)
        ldq     t1, 0(a3)               # E0 (Red)   - Load-hit-store WBUF conflict stall!

        /* Saturate MTU with Load MAF misses while issuing stores to distinct blocks */
        ldq     t2, 0(s0)       # miss  # E0 (Green) - Load MAF 1/6 (highest MTU priority)
        ldq     t3, 32(s0)      # miss  # E0 (Green) - Load MAF 2/6
        ldq     t4, 64(s0)      # miss  # E0 (Green) - Load MAF 3/6
        stq     t0, 0(s1)               # E0 (Green) - WBUF 1/6 (drain slowed by MTU misses)
        stq     t0, 32(s1)              # E0 (Green) - WBUF 2/6
        stq     t0, 64(s1)              # E0 (Green) - WBUF 3/6
        stq     t0, 96(s1)              # E0 (Green) - WBUF 4/6
        stq     t0, 128(s1)             # E0 (Green) - WBUF 5/6
        stq     t0, 160(s1)             # E0 (Green) - WBUF 6/6 (FULL)
        stq     t0, 192(s1)             # E0 (Red)   - WBUF Full + MTU contention stall!
        wmb                             # E0 (Red)   - Write Memory Barrier flush stall

/* ============================================================================
 * CASE 5: Int <-> FP Register Transfers (FIX Extension) & IEEE Rounding Traps
 * itoft / ftoit cross between integer and FP register files on E0 (4c latency).
 * ============================================================================ */
        .align  4
test_fix_and_ieee_fp:
        itoft   t0, ft0                 # E0 (Green) - Int->FP transfer (4c latency)
        addt/sui ft0, ft1, ft2          # FA (Red, -3c stall waiting for itoft ft0)
        cvttq/svc ft2, ft3              # FA (Red, -3c stall waiting for addt ft2)
        ftoit   ft3, v0                 # E0 (Red, -3c stall waiting for cvttq ft3)
        addq    v0, 42, v0              # E0/E1 (Red, -3c stall waiting for ftoit v0)

/* ============================================================================
 * CASE 6: Deep Mixed Pipeline Chain (Non-Pipelined Divides + Branches + Epilogue)
 * ============================================================================ */
        .align  4
test_mixed_stalls_and_epilogue:
        divs/su fs0, fs1, fs2           # FM (Green) - Single-precision FP divide (17c latency)
        addt    fs3, fs4, fs5           # FA (Green) - Runs in parallel on FA!
        subq    t0, t1, t2              # E0 (Green) - Runs in parallel on E0!
        xor     t3, t4, t5              # E1 (Green) - Runs in parallel on E1!
        divs/su fs5, fs6, fs7           # FM (Red)   - Stalls on both fs5 RAW & non-pipelined FM divider
        addt    fs2, fs7, fv0           # FA (Red)   - Stalls waiting for second divs

        /* Epilogue restore */
        ldq     ra, 0(sp)
        ldq     s0, 8(sp)
        ldq     s1, 16(sp)
        ldt     fs0, 24(sp)
        ldt     fs1, 32(sp)
        lda     sp, 128(sp)
        ret     zero, (ra), 1           # E0 (Green) + Orange "B:6"
        .end    ev56_extended_suite
