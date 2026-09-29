/**
 * <strong>Production / JVM diagnostics lab — training experiments only.</strong>
 * <p>
 * Nothing here is part of the application: it lives under {@code src/test}, is never packaged, changes no application
 * bean, pool or executor, and every intentionally "broken" pattern (held locks, retained memory, exhausted pools,
 * saturated executors) exists only inside these tests. The experiments are tagged {@code production-lab} and excluded
 * from the normal {@code mvn test}; run them with:
 * <pre>
 * mvn test -Pproduction-lab                                      # all experiments
 * mvn test -Pproduction-lab -Dtest=HikariPoolExhaustionLabTest   # one experiment
 * mvn test -Pproduction-lab -Dtest=LockContentionLabTest -Dlab.hold=60s   # keep the state for jcmd
 * </pre>
 * Observations are printed as {@code [PROD-LAB] …} lines. The assertions check the <em>shape</em> of the result
 * (threads blocked, pending connections, rejections, retained bytes), with broad timing bounds; the exact numbers are
 * machine-specific. See docs/production-diagnostics.md for what each experiment teaches and the matching jcmd/JFR
 * commands.
 */
package pl.dch.marketplace.productionlab;
