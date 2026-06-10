package biz.gss;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import com.sun.management.OperatingSystemMXBean;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ThreadMemoryDemo {

    private static final int THREAD_COUNT = 100_000;
    private static final int HOLD_MILLIS = 5_000; // how long to keep threads alive after release

    public static void main(String[] args) throws Exception {
        log.info("Java version: " + System.getProperty("java.version"));
        log.info("THREAD_COUNT = " + THREAD_COUNT);
        log.info("================================================");

        logAll("START (before anything)");
        runThreadsTest("PLATFORM");

        logAll("AFTER GC / BEFORE VIRTUAL");
        runThreadsTest("VIRTUAL");

        logAll("END");
    }

    // ---------------------------------------------------------------
    // Platform threads
    // ---------------------------------------------------------------
    private static void runThreadsTest(String type) throws Exception {
        Instant start = Instant.now();
        log.info("\n=== " + type + " THREADS TEST ===");
        logAll(type + " BEFORE creating threads");

        CountDownLatch latch = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            Thread t = (type=="PLATFORM") ? new Thread(getRunnable(latch), "platform-" + i)
                                          :Thread.ofVirtual().unstarted(getRunnable(latch));
            t.start();
            threads.add(t);
        }

        logAll(type + " AFTER creating " + THREAD_COUNT + " threads (before release)");

        // Let threads run / sleep
        latch.countDown();
        log.info(type + " latch released, threads are now sleeping for " + HOLD_MILLIS + " ms");
        Thread.sleep(2_000);
        logAll(type + " DURING threads running");

        // Wait for all to finish
        for (Thread t : threads) {
            t.join();
        }
        log.info(type + " all threads joined.");

        System.gc();
        Thread.sleep(2_000);
        logAll(type + "AFTER threads finished + GC");
        Instant end = Instant.now();
        Duration duration = Duration.between(start, end);
        log.info("threads TOTAL TIME:  {} s", duration.toMillis() / 1000);
        log.info("================================================");
        Thread.sleep(2_000); // small pause between tests
        System.gc();
    }

    private static Runnable getRunnable(CountDownLatch latch) {
        return () -> {
            try {
                // Wait until we release them
                latch.await();
                // Keep thread alive a bit after release so OS keeps stacks allocated
                Thread.sleep(HOLD_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }


    // ---------------------------------------------------------------
    // Logging helpers
    // ---------------------------------------------------------------
    private static void logAll(String label) {
        log.info("\n--- " + label + " ---");
        logHeap();
        logOSMemory();
        logThreadCount();
    }

    private static void logHeap() {
        MemoryMXBean mx = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = mx.getHeapMemoryUsage();

        long used = heap.getUsed() / (1024 * 1024);
        long committed = heap.getCommitted() / (1024 * 1024);
        long max = heap.getMax() / (1024 * 1024);

        System.out.printf("Heap: used=%d MB, committed=%d MB, max=%d MB%n", used, committed, max);
    }

    private static void logOSMemory() {
        OperatingSystemMXBean os =
                (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        long committedVirtual = os.getCommittedVirtualMemorySize() / (1024 * 1024);
        long totalPhys = os.getTotalPhysicalMemorySize() / (1024 * 1024);
        long freePhys = os.getFreePhysicalMemorySize() / (1024 * 1024);

        System.out.printf("OS: committedVirtual=%d MB, totalPhysical=%d MB, freePhysical=%d MB%n",
                committedVirtual, totalPhys, freePhys);
    }

    private static void logThreadCount() {
        int live = ManagementFactory.getThreadMXBean().getThreadCount();
        System.out.printf("Live threads (JVM): %d%n", live);
    }
}
