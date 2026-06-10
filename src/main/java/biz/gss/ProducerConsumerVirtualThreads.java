package biz.gss;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class ProducerConsumerVirtualThreads {

    // Total number of items to process
    private static final int TOTAL_ITEMS = 500_000;

    // Platform thread configuration (simulate realistic small pool)
    private static final int PLATFORM_PRODUCERS = 400;
    private static final int PLATFORM_CONSUMERS = 400;

    // Virtual thread configuration (lots of lightweight threads)
    private static final int VIRTUAL_PRODUCERS = 40000;
    private static final int VIRTUAL_CONSUMERS = 40000;

    // Queue capacity
    private static final int QUEUE_CAPACITY = 10_000;

    // Simulated per-item work (e.g. blocking I/O)
    private static final int PRODUCE_DELAY_MS = 10;
    private static final int CONSUME_DELAY_MS = 200;

    // Special value to signal consumers to stop
    private static final int POISON_PILL = -1;

    public static void main(String[] args) throws Exception {
        log.info("Java version       = {}", System.getProperty("java.version"));
        log.info("TOTAL_ITEMS        = {}", TOTAL_ITEMS);
        log.info("QUEUE_CAPACITY     = {}", QUEUE_CAPACITY);
        log.info("PRODUCE_DELAY_MS   = {}", PRODUCE_DELAY_MS);
        log.info("CONSUME_DELAY_MS   = {}", CONSUME_DELAY_MS);
        log.info("-------------------------------------------------");
        log.info("PLATFORM_PRODUCERS = {}", PLATFORM_PRODUCERS);
        log.info("PLATFORM_CONSUMERS = {}", PLATFORM_CONSUMERS);
        log.info("VIRTUAL_PRODUCERS  = {}", VIRTUAL_PRODUCERS);
        log.info("VIRTUAL_CONSUMERS  = {}", VIRTUAL_CONSUMERS);
        log.info("=================================================\n");

        try (ExecutorService producerPool = Executors.newFixedThreadPool(PLATFORM_PRODUCERS);
            ExecutorService consumerPool = Executors.newFixedThreadPool(PLATFORM_CONSUMERS)) {
            runThreads("PLATFORM ", producerPool, consumerPool);
        }


        System.gc();

        try (ExecutorService execProducer = Executors.newVirtualThreadPerTaskExecutor();
             ExecutorService execConsumer= Executors.newVirtualThreadPerTaskExecutor()) {
            runThreads("VIRTUAL ", execProducer, execConsumer);
        }
    }


    // -------------------------------------------------
    // Virtual threads scenario
    // -------------------------------------------------
    private static void runThreads(String label, ExecutorService producerPool, ExecutorService consumerPool) throws Exception {
        log.info("=== Scenario: " + label + " THREADS ===");

        BlockingQueue<Integer> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        AtomicInteger producedCounter = new AtomicInteger();
        AtomicInteger consumedCounter = new AtomicInteger();



        Duration duration = runScenario(label, queue, producedCounter, consumedCounter, producerPool, consumerPool, VIRTUAL_PRODUCERS, VIRTUAL_CONSUMERS);

        double seconds = duration.toMillis() / 1000.0;
        double throughput = TOTAL_ITEMS / seconds;

        log.info(label + " threads: total time = {} s", seconds);
        log.info(label +  " threads: throughput ≈ {} items/s", String.format("%.2f", throughput));
        log.info("-------------------------------------------------\n");
    }

    // -------------------------------------------------
    // Shared scenario logic
    // -------------------------------------------------
    private static Duration runScenario(String label, BlockingQueue<Integer> queue, AtomicInteger producedCounter,
                                        AtomicInteger consumedCounter, ExecutorService producerPool, ExecutorService consumerPool, int producersCount, int consumersCount) throws Exception {

        Instant start = Instant.now();
        int baseItemsPerProducer = TOTAL_ITEMS / producersCount;
        int remainder = TOTAL_ITEMS % producersCount;

        List<Future<?>> producerFutures = new ArrayList<>();
        for (int i = 0; i < producersCount; i++) {
            int producerId = i;
            //int itemsForThisProducer = baseItemsPerProducer + (i == producersCount - 1 ? remainder : 0);
            int itemsForThisProducer = baseItemsPerProducer + (i < remainder ? 1 : 0);

            producerFutures.add(producerPool.submit(() ->
                    runProducer(label, producerId, itemsForThisProducer, queue, producedCounter)));
        }

        List<Future<?>> consumerFutures = new ArrayList<>();
        for (int i = 0; i < consumersCount; i++) {
            int consumerId = i;
            consumerFutures.add(consumerPool.submit(() ->
                    runConsumer(label, consumerId, queue, consumedCounter)));
        }

        // Wait for all producers
        for (Future<?> f : producerFutures) {
            f.get();
        }

        log.info("[{}] All producers finished, inserting poison pills", label);

        // One poison pill per consumer
        for (int i = 0; i < consumersCount; i++) {
            queue.put(POISON_PILL);
        }

        // Wait for all consumers
        for (Future<?> f : consumerFutures) {
            f.get();
        }

        Instant end = Instant.now();
        Duration duration = Duration.between(start, end);

        log.info("[{}] Produced items   = {}", label, producedCounter.get());
        log.info("[{}] Consumed items   = {}", label, consumedCounter.get());
        log.info("[{}] Queue size final = {}", label, queue.size());


        return duration;
    }

    // -------------------------------------------------
    // Producer / Consumer implementations
    // -------------------------------------------------
    private static void runProducer(String label, int producerId, int itemsToProduce, BlockingQueue<Integer> queue, AtomicInteger producedCounter ) {
        try {
            for (int i = 0; i < itemsToProduce; i++) {
                // Simulate some blocking work (e.g. I/O)
                if (PRODUCE_DELAY_MS > 0) {
                    Thread.sleep(PRODUCE_DELAY_MS);
                }

                int item = producedCounter.incrementAndGet();
                queue.put(item); // blocks if queue is full

                if (item % 10_000 == 0) {
                    log.info("[{}-producer-{}] Produced item {} (thread: {})",
                            label, producerId, item, Thread.currentThread());
                }
            }
            log.debug("[{}-producer-{}] Finished producing {} items (thread: {})",
                    label, producerId, itemsToProduce, Thread.currentThread());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[{}-producer-{}] Interrupted", label, producerId, e);
        }
    }

    private static void runConsumer(String label, int consumerId, BlockingQueue<Integer> queue, AtomicInteger consumedCounter) {
        try {
            while (true) {
                Integer item = queue.take(); // blocks if queue is empty

                if (item == POISON_PILL) {
                    log.debug("[{}-consumer-{}] Received poison pill, exiting (thread: {})",
                            label, consumerId, Thread.currentThread());
                    break;
                }

                // Simulate some blocking work (e.g. I/O)
                if (CONSUME_DELAY_MS > 0) {
                    Thread.sleep(CONSUME_DELAY_MS);
                }

                int consumed = consumedCounter.incrementAndGet();
                if (consumed % 10_000 == 0) {
                    log.info("[{}-consumer-{}] Consumed item {} (total consumed: {}, thread: {})",
                            label, consumerId, item, consumed, Thread.currentThread());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[{}-consumer-{}] Interrupted", label, consumerId, e);
        }
    }

}
