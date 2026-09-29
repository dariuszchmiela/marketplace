/**
 * <strong>Concurrency lab — training experiments only.</strong>
 * <p>
 * Nothing in this package is part of the application: it lives under {@code src/test}, is never
 * packaged, exposes no endpoints and changes no application executor. It exists to demonstrate, with
 * deterministic fake downstream services and broad timing bounds:
 * <ul>
 *   <li>sequential vs parallel independent blocking calls ({@code CompletableFuture} on an explicit
 *       executor, virtual threads) — {@code ParallelCallsLabTest};</li>
 *   <li>a fixed platform thread pool queueing work in batches vs virtual-thread-per-task —
 *       {@code ThreadPoolLabTest};</li>
 *   <li>what virtual threads do <em>not</em> fix: downstream capacity, locks, CPU-bound work
 *       ({@code VirtualThreadLimitsLabTest}) and the database connection pool ({@code DatabasePoolLimitLabTest}).</li>
 * </ul>
 * Observed timings are printed as {@code [LAB] …} lines in the test output.
 */
package pl.dch.marketplace.lab;
