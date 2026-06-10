package biz.gss;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Slf4j
public class AccountVirtualVsPlatformDemo {

    // simulation parameters
    private static final int TASKS = 100;           // number of "clients"
    private static final int DEPOSITS_PER_TASK = 1_000;
    private static final int DEPOSIT_AMOUNT = 1;      // unit per deposit

    // platform thread pool size
    private static final int PLATFORM_POOL_SIZE = 16; // typical small pool

    public static void main(String[] args) throws Exception {
        log.info("Java version: {}", System.getProperty("java.version"));
        log.info("TASKS              = {}", TASKS);
        log.info("DEPOSITS_PER_TASK  = {}", DEPOSITS_PER_TASK);
        log.info("DEPOSIT_AMOUNT     = {}", DEPOSIT_AMOUNT);
        log.info("PLATFORM_POOL_SIZE = {}", PLATFORM_POOL_SIZE);
        log.info("Expected total     = {}", expectedTotal());
        log.info("====================================================");

        runPlatformThreadsWithSharedAccount();
        log.info("----------------------------------------------------");
        runVirtualThreadsWithLocalAccounts();
    }

    private static int expectedTotal() {
        return TASKS * DEPOSITS_PER_TASK * DEPOSIT_AMOUNT;
    }

    // ============================================================
    // 1) PLATFORM THREADS: shared mutable Account, needs sync
    // ============================================================
    private static void runPlatformThreadsWithSharedAccount() throws Exception {
        log.info("=== Scenario 1: PLATFORM threads with SHARED synchronized Account ===");

        // one shared account across all tasks → must synchronize
        SharedAccount shared = new SharedAccount();

        ExecutorService pool = Executors.newFixedThreadPool(PLATFORM_POOL_SIZE);
        List<Future<?>> futures = new ArrayList<>();

        Instant start = Instant.now();

        for (int i = 0; i < TASKS; i++) {
            futures.add(pool.submit(() -> {
                for (int j = 0; j < DEPOSITS_PER_TASK; j++) {
                    shared.deposit(DEPOSIT_AMOUNT);
                    // simulate some small blocking work
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get();
        }

        pool.shutdown();

        Instant end = Instant.now();
        Duration duration = Duration.between(start, end);
        int finalBalance = shared.getBalance();

        log.info("[platform] Final balance   = {}", finalBalance);
        log.info("[platform] Expected balance = {}", expectedTotal());
        log.info("[platform] Correct result?  = {}", finalBalance == expectedTotal());
        log.info("[platform] Time             = {} s", duration.toMillis() / 1000);
    }

    // Shared mutable account that needs synchronization for platform threads
    private static class SharedAccount {
        private int balance;

        // critical section: multiple threads hit this concurrently
        public synchronized void deposit(int amount) {
            balance += amount;
        }

        public synchronized int getBalance() {
            return balance;
        }
    }

    // ============================================================
    // 2) VIRTUAL THREADS: NO shared account, NO synchronization
    // ============================================================
    private static void runVirtualThreadsWithLocalAccounts() throws Exception {
        log.info("=== Scenario 2: VIRTUAL threads with LOCAL (non-shared) Accounts ===");

        Instant start = Instant.now();

        // With virtual threads we can afford ONE THREAD PER TASK.
        // Each task uses its OWN account; no sharing, no synchronization.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < TASKS; i++) {
                futures.add(executor.submit(() -> {
                    LocalAccount account = new LocalAccount();
                    for (int j = 0; j < DEPOSITS_PER_TASK; j++) {
                        account.deposit(DEPOSIT_AMOUNT);
                        try {
                            // simulate some small blocking work
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    // return this task's result instead of touching shared state
                    return account.getBalance();
                }));
            }

            // aggregate results in a single thread: no synchronization needed, structured programing
            int total = 0;
            for (Future<Integer> f : futures) {
                total += f.get();
            }

            Instant end = Instant.now();
            Duration duration = Duration.between(start, end);

            log.info("[virtual] Final balance   = {}", total);
            log.info("[virtual] Expected balance = {}", expectedTotal());
            log.info("[virtual] Correct result?  = {}", total == expectedTotal());
            log.info("[virtual] Time             = {} s", duration.toMillis() / 1000);
        }
    }

    // Local, non-shared account: used only inside one virtual thread
    private static class LocalAccount {
        private int balance;

        // called only from a single virtual thread → no sync needed
        public void deposit(int amount) {
            balance += amount;
        }

        public int getBalance() {
            return balance;
        }
    }
}
