package biz.gss;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import com.sun.management.OperatingSystemMXBean;

public class ThreadMemoryDemo {

    private static final int THREAD_COUNT = 150_000;
    private static final int HOLD_MILLIS = 5_000; // how long to keep threads alive after release

    public static void main(String[] args) throws Exception {
        System.out.println("Java version: " + System.getProperty("java.version"));
        System.out.println("THREAD_COUNT = " + THREAD_COUNT);
        System.out.println("================================================");

        logAll("START (before anything)");

        runPlatformThreadsTest();

        System.out.println("================================================");
        Thread.sleep(2_000); // small pause between tests
        System.gc();
        Thread.sleep(2_000);
        logAll("AFTER GC / BEFORE VIRTUAL");

        runVirtualThreadsTest();

        System.out.println("================================================");
        Thread.sleep(2_000);
        System.gc();
        Thread.sleep(2_000);
        logAll("END");
    }

    // ---------------------------------------------------------------
    // Platform threads
    // ---------------------------------------------------------------
    private static void runPlatformThreadsTest() throws Exception {
        System.out.println("\n=== PLATFORM THREADS TEST ===");
        logAll("Platform BEFORE creating threads");

        CountDownLatch latch = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            Thread t = new Thread(() -> {
                try {
                    // Wait until we release them
                    latch.await();
                    // Keep thread alive a bit after release so OS keeps stacks allocated
                    Thread.sleep(HOLD_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "platform-" + i);
            t.start();
            threads.add(t);
        }

        logAll("Platform AFTER creating " + THREAD_COUNT + " threads (before release)");

        // Let threads run / sleep
        latch.countDown();
        System.out.println("Platform: latch released, threads are now sleeping for " + HOLD_MILLIS + " ms");
        Thread.sleep(2_000);
        logAll("Platform DURING threads running");

        // Wait for all to finish
        for (Thread t : threads) {
            t.join();
        }
        System.out.println("Platform: all threads joined.");

        System.gc();
        Thread.sleep(2_000);
        logAll("Platform AFTER threads finished + GC");
    }

    // ---------------------------------------------------------------
    // Virtual threads
    // ---------------------------------------------------------------
    private static void runVirtualThreadsTest() throws Exception {
        System.out.println("\n=== VIRTUAL THREADS TEST ===");
        logAll("Virtual BEFORE creating threads");

        CountDownLatch latch = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            Thread t = Thread.ofVirtual().unstarted(() -> {
                try {
                    latch.await();
                    Thread.sleep(HOLD_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            t.start();
            threads.add(t);
        }

        logAll("Virtual AFTER creating " + THREAD_COUNT + " virtual threads (before release)");

        latch.countDown();
        System.out.println("Virtual: latch released, virtual threads are now sleeping for " + HOLD_MILLIS + " ms");
        Thread.sleep(2_000);
        logAll("Virtual DURING threads running");

        for (Thread t : threads) {
            t.join();
        }
        System.out.println("Virtual: all threads joined.");

        System.gc();
        Thread.sleep(2_000);
        logAll("Virtual AFTER threads finished + GC");
    }

    // ---------------------------------------------------------------
    // Logging helpers
    // ---------------------------------------------------------------
    private static void logAll(String label) {
        System.out.println("\n--- " + label + " ---");
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

        System.out.printf("Heap: used=%d MB, committed=%d MB, max=%d MB%n",
                used, committed, max);
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
