package biz.gss;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Slf4j
public class VirtualThreadsDemo {

    private static final int TASK_COUNT = 10_000;
    private static final int PLATFORM_POOL_SIZE = 100;
    private static final int TASK_SLEEP_MILLIS = 100;

    public static void main(String[] args) throws Exception {
        log.info("Java version: {}", System.getProperty("java.version"));
        log.info("Tasks: {}", TASK_COUNT);
        log.info("Platform pool size: {}", PLATFORM_POOL_SIZE);
        log.info("Simulated blocking per task: {} ms", TASK_SLEEP_MILLIS);
        log.info("------------------------------------------------------");

        log.info("=== Running with PLATFORM threads (fixed thread pool) ===");
        try (ExecutorService executor = Executors.newFixedThreadPool(PLATFORM_POOL_SIZE)) {
            runThreads(executor);
        }

        log.info("=== Running with VIRTUAL threads (one per task) ===");
        log.info("");
        try (ExecutorService executorVirtual = Executors.newVirtualThreadPerTaskExecutor()) {
            runThreads(executorVirtual);
        }

    }

    private static void runThreads(ExecutorService executor) throws Exception {

        Instant start = Instant.now();

        List<Future<String>> futures = new ArrayList<>(TASK_COUNT);
        for (int i = 0; i < TASK_COUNT; i++) {
            final int taskId = i;
            futures.add(executor.submit(() -> simulateBlockingCall("platform", taskId)));
        }

        for (Future<String> f : futures) {
            f.get();
        }

        Instant end = Instant.now();
        executor.shutdown();

        Duration duration = Duration.between(start, end);
        log.info("threads total time: {} ms ({} s)", duration.toMillis(), duration.toMillis() / 1000.0);
        log.info("active thread count: {}", Thread.activeCount());
    }



    private static String simulateBlockingCall(String type, int taskId) {
        try {
            if (taskId % 2000 == 0) {
                log.debug("[{}] Starting task {} on thread {}", type, taskId, Thread.currentThread());
            }
            Thread.sleep(TASK_SLEEP_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "done-" + taskId;
    }
}