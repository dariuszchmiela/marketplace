package pl.dch.marketplace.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pl.dch.marketplace.IntegrationTestBase;
import pl.dch.marketplace.common.MarketplaceException;
import pl.dch.marketplace.product.Product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two stock update paths directly at transaction level (no HTTP):
 * entity + {@code @Version} for purchases, atomic {@code UPDATE … + ?} for compensation.
 */
class StockUpdateConcurrencyIntegrationTest extends IntegrationTestBase {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void twoTransactionsDecreasingFromTheSameVersionCannotBothWin() throws Exception {
        Product lamp = createProduct("Decrease Lamp", "10.00", 5);
        long versionBefore = version(lamp);
        CyclicBarrier bothHaveRead = new CyclicBarrier(2);

        Work decrease = () -> transaction().executeWithoutResult(status -> {
            Product loaded = productRepository.findById(lamp.getId()).orElseThrow();
            await(bothHaveRead);   // both read stock 5 / the same version before anyone writes
            loaded.decreaseStock(2);
        });
        List<Throwable> failures = runTwice(decrease);

        // Without @Version both would write 5 - 2 = 3 and one purchase would be lost.
        assertThat(failures).singleElement().isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(stockOf(lamp)).isEqualTo(3);
        assertThat(version(lamp)).isEqualTo(versionBefore + 1);
    }

    @Test
    void compensationDuringAPurchaseIsNotOverwritten() throws Exception {
        Product lamp = createProduct("Compensated Lamp", "10.00", 1);
        CountDownLatch purchaseHasRead = new CountDownLatch(1);
        CountDownLatch compensationCommitted = new CountDownLatch(1);

        Future<?> purchase = inBackground(() -> {
            transaction().executeWithoutResult(status -> {
                Product loaded = productRepository.findById(lamp.getId()).orElseThrow();   // stock 1
                purchaseHasRead.countDown();
                await(compensationCommitted);
                loaded.decreaseStock(1);   // would write 0, based on the stale read
            });
            return null;
        });
        assertThat(purchaseHasRead.await(10, TimeUnit.SECONDS)).isTrue();
        // A failed payment of another order returns 2 units meanwhile.
        transaction().executeWithoutResult(status -> productRepository.increaseStock(lamp.getId(), 2));
        compensationCommitted.countDown();

        // The version bump in increaseStock makes the stale purchase fail instead of writing stock 0.
        assertThatThrownBy(() -> purchase.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(OptimisticLockingFailureException.class);
        assertThat(stockOf(lamp)).isEqualTo(3);
    }

    @Test
    void concurrentCompensationsAreAtomicAndBumpTheVersionEachTime() throws Exception {
        Product lamp = createProduct("Returned Lamp", "10.00", 0);
        long versionBefore = version(lamp);
        int returns = 20;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < returns; i++) {
            futures.add(inBackground(() -> {
                start.await();
                return transaction().execute(status -> productRepository.increaseStock(lamp.getId(), 1));
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(20, TimeUnit.SECONDS);   // none fails: the database serializes the row updates
        }

        assertThat(stockOf(lamp)).isEqualTo(returns);
        assertThat(version(lamp)).isEqualTo(versionBefore + returns);
    }

    @Test
    void stockCanNeverBecomeNegative() {
        Product lamp = createProduct("Guarded Lamp", "10.00", 1);

        assertThatThrownBy(() -> transaction().executeWithoutResult(status ->
                productRepository.findById(lamp.getId()).orElseThrow().decreaseStock(2)))
                .isInstanceOf(MarketplaceException.class);
        // Second line of defence below the domain check: CHECK (available_quantity >= 0).
        assertThatThrownBy(() -> jdbcTemplate.update(
                "update product set available_quantity = available_quantity - 2 where id = ?", lamp.getId()))
                .hasMessageContaining("available_quantity");
        assertThat(stockOf(lamp)).isEqualTo(1);
    }

    private interface Work {
        void run();
    }

    private List<Throwable> runTwice(Work task) throws Exception {
        List<Future<?>> futures = List.of(inBackground(() -> {
            task.run();
            return null;
        }), inBackground(() -> {
            task.run();
            return null;
        }));
        List<Throwable> failures = new ArrayList<>();
        for (Future<?> future : futures) {
            try {
                future.get(20, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException ex) {
                failures.add(ex.getCause());
            }
        }
        return failures;
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private long version(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getVersion();
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timeout");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
