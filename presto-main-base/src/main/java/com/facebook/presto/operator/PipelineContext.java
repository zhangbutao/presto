/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator;

import com.facebook.airlift.stats.CounterStat;
import com.facebook.airlift.stats.Distribution;
import com.facebook.presto.Session;
import com.facebook.presto.execution.FragmentResultCacheContext;
import com.facebook.presto.execution.Lifespan;
import com.facebook.presto.execution.TaskId;
import com.facebook.presto.memory.QueryContextVisitor;
import com.facebook.presto.memory.context.LocalMemoryContext;
import com.facebook.presto.memory.context.MemoryTrackingContext;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.google.common.util.concurrent.ListenableFuture;

import javax.annotation.concurrent.ThreadSafe;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.System.currentTimeMillis;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.stream.Collectors.toList;

@ThreadSafe
public class PipelineContext
{
    private final TaskContext taskContext;
    private final Executor notificationExecutor;
    private final ScheduledExecutorService yieldExecutor;
    private final int pipelineId;

    private final boolean inputPipeline;
    private final boolean outputPipeline;
    private final boolean partitioned;

    private final List<DriverContext> drivers = new CopyOnWriteArrayList<>();

    private final AtomicInteger totalSplits = new AtomicInteger();
    private final AtomicLong totalSplitsWeight = new AtomicLong();
    private final AtomicInteger completedDrivers = new AtomicInteger();
    private final AtomicLong completedSplitsWeight = new AtomicLong();

    private final AtomicLong executionStartTime = new AtomicLong();
    private final AtomicLong lastExecutionStartTime = new AtomicLong();
    private final AtomicLong lastExecutionEndTime = new AtomicLong();

    private final Distribution queuedTime = new Distribution();
    private final Distribution elapsedTime = new Distribution();

    private final AtomicLong totalScheduledTime = new AtomicLong();
    private final AtomicLong totalCpuTime = new AtomicLong();
    private final AtomicLong totalBlockedTime = new AtomicLong();

    private final AtomicLong totalAllocation = new AtomicLong();

    private final CounterStat rawInputDataSize = new CounterStat();
    private final CounterStat rawInputPositions = new CounterStat();

    private final CounterStat processedInputDataSize = new CounterStat();
    private final CounterStat processedInputPositions = new CounterStat();

    private final CounterStat outputDataSize = new CounterStat();
    private final CounterStat outputPositions = new CounterStat();

    private final AtomicLong physicalWrittenDataSize = new AtomicLong();

    private final ConcurrentMap<Integer, OperatorStats> operatorSummaries = new ConcurrentHashMap<>();

    private final MemoryTrackingContext pipelineMemoryContext;

    public PipelineContext(int pipelineId, TaskContext taskContext, Executor notificationExecutor, ScheduledExecutorService yieldExecutor, MemoryTrackingContext pipelineMemoryContext, boolean inputPipeline, boolean outputPipeline, boolean partitioned)
    {
        this.pipelineId = pipelineId;
        this.inputPipeline = inputPipeline;
        this.outputPipeline = outputPipeline;
        this.partitioned = partitioned;
        this.taskContext = requireNonNull(taskContext, "taskContext is null");
        this.notificationExecutor = requireNonNull(notificationExecutor, "notificationExecutor is null");
        this.yieldExecutor = requireNonNull(yieldExecutor, "yieldExecutor is null");
        this.pipelineMemoryContext = requireNonNull(pipelineMemoryContext, "pipelineMemoryContext is null");
        // Initialize the local memory contexts with the ExchangeOperator tag as ExchangeOperator will do the local memory allocations
        pipelineMemoryContext.initializeLocalMemoryContexts(ExchangeOperator.class.getSimpleName());
    }

    public TaskContext getTaskContext()
    {
        return taskContext;
    }

    public TaskId getTaskId()
    {
        return taskContext.getTaskId();
    }

    public int getPipelineId()
    {
        return pipelineId;
    }

    public boolean isInputPipeline()
    {
        return inputPipeline;
    }

    public boolean isOutputPipeline()
    {
        return outputPipeline;
    }

    public DriverContext addDriverContext()
    {
        return addDriverContext(0, Lifespan.taskWide(), Optional.empty());
    }

    public DriverContext addDriverContext(long splitWeight, Lifespan lifespan, Optional<FragmentResultCacheContext> fragmentResultCacheContext)
    {
        checkArgument(partitioned || splitWeight == 0, "Only partitioned splits should have weights");
        DriverContext driverContext = new DriverContext(
                this,
                notificationExecutor,
                yieldExecutor,
                pipelineMemoryContext.newMemoryTrackingContext(),
                lifespan,
                fragmentResultCacheContext,
                splitWeight);
        drivers.add(driverContext);
        return driverContext;
    }

    public Session getSession()
    {
        return taskContext.getSession();
    }

    public void splitsAdded(int count, long weightSum)
    {
        checkArgument(count >= 0);
        checkArgument(weightSum >= 0);
        totalSplits.addAndGet(count);
        if (partitioned && weightSum != 0) {
            totalSplitsWeight.addAndGet(weightSum);
        }
    }

    public void driverFinished(DriverContext driverContext)
    {
        requireNonNull(driverContext, "driverContext is null");

        if (!drivers.remove(driverContext)) {
            throw new IllegalArgumentException("Unknown driver " + driverContext);
        }

        // always update last execution end time
        lastExecutionEndTime.set(currentTimeMillis());

        DriverStats driverStats = driverContext.getDriverStats();

        completedDrivers.getAndIncrement();
        if (partitioned) {
            completedSplitsWeight.addAndGet(driverContext.getSplitWeight());
        }

        queuedTime.add(driverStats.getQueuedTime().roundTo(NANOSECONDS));
        elapsedTime.add(driverStats.getElapsedTime().roundTo(NANOSECONDS));

        totalScheduledTime.getAndAdd(driverStats.getTotalScheduledTime().roundTo(NANOSECONDS));
        totalCpuTime.getAndAdd(driverStats.getTotalCpuTime().roundTo(NANOSECONDS));

        totalBlockedTime.getAndAdd(driverStats.getTotalBlockedTime().roundTo(NANOSECONDS));

        totalAllocation.getAndAdd(driverStats.getTotalAllocationInBytes());

        // merge the operator stats into the operator summary
        List<OperatorStats> operators = driverStats.getOperatorStats();
        for (OperatorStats operator : operators) {
            operatorSummaries.compute(operator.getOperatorId(), (operatorId, summaryStats) -> summaryStats == null ? operator : summaryStats.add(operator));
        }

        rawInputDataSize.update(driverStats.getRawInputDataSizeInBytes());
        rawInputPositions.update(driverStats.getRawInputPositions());

        processedInputDataSize.update(driverStats.getProcessedInputDataSizeInBytes());
        processedInputPositions.update(driverStats.getProcessedInputPositions());

        outputDataSize.update(driverStats.getOutputDataSizeInBytes());
        outputPositions.update(driverStats.getOutputPositions());

        physicalWrittenDataSize.getAndAdd(driverStats.getPhysicalWrittenDataSizeInBytes());
    }

    public void start()
    {
        long now = currentTimeMillis();
        executionStartTime.compareAndSet(0, now);
        // always update last execution start time
        lastExecutionStartTime.set(now);

        taskContext.start();
    }

    public void failed(Throwable cause)
    {
        taskContext.failed(cause);
    }

    public boolean isDone()
    {
        return taskContext.isDone();
    }

    public synchronized ListenableFuture<?> reserveSpill(long bytes)
    {
        return taskContext.reserveSpill(bytes);
    }

    public synchronized void freeSpill(long bytes)
    {
        checkArgument(bytes >= 0, "bytes is negative");
        taskContext.freeSpill(bytes);
    }

    public LocalMemoryContext localSystemMemoryContext()
    {
        return pipelineMemoryContext.localSystemMemoryContext();
    }

    public void moreMemoryAvailable()
    {
        drivers.forEach(DriverContext::moreMemoryAvailable);
    }

    public boolean isPerOperatorCpuTimerEnabled()
    {
        return taskContext.isPerOperatorCpuTimerEnabled();
    }

    public boolean isCpuTimerEnabled()
    {
        return taskContext.isCpuTimerEnabled();
    }

    public boolean isPerOperatorAllocationTrackingEnabled()
    {
        return taskContext.isPerOperatorAllocationTrackingEnabled();
    }

    public boolean isAllocationTrackingEnabled()
    {
        return taskContext.isAllocationTrackingEnabled();
    }

    public CounterStat getInputDataSize()
    {
        CounterStat stat = new CounterStat();
        stat.merge(rawInputDataSize);
        for (DriverContext driver : drivers) {
            stat.merge(driver.getInputDataSize());
        }
        return stat;
    }

    public CounterStat getInputPositions()
    {
        CounterStat stat = new CounterStat();
        stat.merge(rawInputPositions);
        for (DriverContext driver : drivers) {
            stat.merge(driver.getInputPositions());
        }
        return stat;
    }

    public CounterStat getOutputDataSize()
    {
        CounterStat stat = new CounterStat();
        stat.merge(outputDataSize);
        for (DriverContext driver : drivers) {
            stat.merge(driver.getOutputDataSize());
        }
        return stat;
    }

    public CounterStat getOutputPositions()
    {
        CounterStat stat = new CounterStat();
        stat.merge(outputPositions);
        for (DriverContext driver : drivers) {
            stat.merge(driver.getOutputPositions());
        }
        return stat;
    }

    public long getPhysicalWrittenDataSize()
    {
        return drivers.stream()
                .mapToLong(DriverContext::getPhysicalWrittenDataSize)
                .sum();
    }

    public PipelineStatus getPipelineStatus()
    {
        return getPipelineStatus(drivers.iterator(), totalSplits.get(), completedDrivers.get(), getActivePartitionedSplitsWeight(), partitioned);
    }

    private long getActivePartitionedSplitsWeight()
    {
        if (partitioned) {
            return totalSplitsWeight.get() - completedSplitsWeight.get();
        }
        return 0;
    }

    public PipelineStats getPipelineStats()
    {
        // check for end state to avoid callback ordering problems
        if (taskContext.getState().isDone()) {
            long now = currentTimeMillis();
            executionStartTime.compareAndSet(0, now);
            lastExecutionStartTime.compareAndSet(0, now);
            lastExecutionEndTime.compareAndSet(0, now);
        }

        int completedDrivers = this.completedDrivers.get();
        List<DriverContext> driverContexts = ImmutableList.copyOf(this.drivers);
        int totalSplits = this.totalSplits.get();
        PipelineStatusBuilder pipelineStatusBuilder = new PipelineStatusBuilder(totalSplits, completedDrivers, getActivePartitionedSplitsWeight(), partitioned);
        int totalDrivers = completedDrivers + driverContexts.size();

        Distribution queuedTime = new Distribution(this.queuedTime);
        Distribution elapsedTime = new Distribution(this.elapsedTime);

        long totalScheduledTime = this.totalScheduledTime.get();
        long totalCpuTime = this.totalCpuTime.get();
        long totalBlockedTime = this.totalBlockedTime.get();

        long totalAllocation = this.totalAllocation.get();

        long rawInputDataSize = this.rawInputDataSize.getTotalCount();
        long rawInputPositions = this.rawInputPositions.getTotalCount();

        long processedInputDataSize = this.processedInputDataSize.getTotalCount();
        long processedInputPositions = this.processedInputPositions.getTotalCount();

        long outputDataSize = this.outputDataSize.getTotalCount();
        long outputPositions = this.outputPositions.getTotalCount();

        long physicalWrittenDataSize = this.physicalWrittenDataSize.get();

        ImmutableSet.Builder<BlockedReason> blockedReasons = ImmutableSet.builder();
        boolean hasUnfinishedDrivers = false;
        boolean unfinishedDriversFullyBlocked = true;

        TreeMap<Integer, OperatorStats> operatorSummaries = new TreeMap<>(this.operatorSummaries);
        ListMultimap<Integer, OperatorStats> runningOperators = ArrayListMultimap.create();
        ImmutableList.Builder<DriverStats> drivers = ImmutableList.builderWithExpectedSize(driverContexts.size());
        for (DriverContext driverContext : driverContexts) {
            DriverStats driverStats = driverContext.getDriverStats();
            drivers.add(driverStats);
            pipelineStatusBuilder.accumulate(driverStats, driverContext.getSplitWeight());
            if (driverStats.getStartTimeInMillis() != 0 && driverStats.getEndTimeInMillis() == 0) {
                // driver has started running, but not yet completed
                hasUnfinishedDrivers = true;
                unfinishedDriversFullyBlocked &= driverStats.isFullyBlocked();
                blockedReasons.addAll(driverStats.getBlockedReasons());
            }

            queuedTime.add(driverStats.getQueuedTime().roundTo(NANOSECONDS));
            elapsedTime.add(driverStats.getElapsedTime().roundTo(NANOSECONDS));

            totalScheduledTime += driverStats.getTotalScheduledTime().roundTo(NANOSECONDS);
            totalCpuTime += driverStats.getTotalCpuTime().roundTo(NANOSECONDS);
            totalBlockedTime += driverStats.getTotalBlockedTime().roundTo(NANOSECONDS);

            totalAllocation += driverStats.getTotalAllocationInBytes();

            for (OperatorStats operatorStats : driverStats.getOperatorStats()) {
                runningOperators.put(operatorStats.getOperatorId(), operatorStats);
            }

            rawInputDataSize += driverStats.getRawInputDataSizeInBytes();
            rawInputPositions += driverStats.getRawInputPositions();

            processedInputDataSize += driverStats.getProcessedInputDataSizeInBytes();
            processedInputPositions += driverStats.getProcessedInputPositions();

            outputDataSize += driverStats.getOutputDataSizeInBytes();
            outputPositions += driverStats.getOutputPositions();

            physicalWrittenDataSize += driverStats.getPhysicalWrittenDataSizeInBytes();
        }

        // merge the running operator stats into the operator summary
        for (Integer operatorId : runningOperators.keySet()) {
            List<OperatorStats> runningStats = runningOperators.get(operatorId);
            if (runningStats.isEmpty()) {
                continue;
            }
            OperatorStats current = operatorSummaries.get(operatorId);
            OperatorStats combined;
            if (current != null) {
                combined = current.add(runningStats);
            }
            else {
                combined = runningStats.get(0);
                if (runningStats.size() > 1) {
                    combined = combined.add(runningStats.subList(1, runningStats.size()));
                }
            }
            operatorSummaries.put(operatorId, combined);
        }

        PipelineStatus pipelineStatus = pipelineStatusBuilder.build();
        boolean fullyBlocked = hasUnfinishedDrivers && unfinishedDriversFullyBlocked;

        return new PipelineStats(
                pipelineId,

                executionStartTime.get(),
                lastExecutionStartTime.get(),
                lastExecutionEndTime.get(),

                inputPipeline,
                outputPipeline,

                totalDrivers,
                pipelineStatus.getQueuedDrivers(),
                pipelineStatus.getQueuedPartitionedDrivers(),
                pipelineStatus.getQueuedPartitionedSplitsWeight(),
                pipelineStatus.getRunningDrivers(),
                pipelineStatus.getRunningPartitionedDrivers(),
                pipelineStatus.getRunningPartitionedSplitsWeight(),
                pipelineStatus.getBlockedDrivers(),
                completedDrivers,

                pipelineMemoryContext.getUserMemory(),
                pipelineMemoryContext.getRevocableMemory(),
                pipelineMemoryContext.getSystemMemory(),

                queuedTime.snapshot(),
                elapsedTime.snapshot(),

                totalScheduledTime,
                totalCpuTime,
                totalBlockedTime,
                fullyBlocked,
                blockedReasons.build(),

                totalAllocation,

                rawInputDataSize,
                rawInputPositions,

                processedInputDataSize,
                processedInputPositions,

                outputDataSize,
                outputPositions,

                physicalWrittenDataSize,

                ImmutableList.copyOf(operatorSummaries.values()),
                drivers.build());
    }

    public <C, R> R accept(QueryContextVisitor<C, R> visitor, C context)
    {
        return visitor.visitPipelineContext(this, context);
    }

    public <C, R> List<R> acceptChildren(QueryContextVisitor<C, R> visitor, C context)
    {
        return drivers.stream()
                .map(driver -> driver.accept(visitor, context))
                .collect(toList());
    }

    @VisibleForTesting
    public MemoryTrackingContext getPipelineMemoryContext()
    {
        return pipelineMemoryContext;
    }

    private static PipelineStatus getPipelineStatus(Iterator<DriverContext> driverContextsIterator, int totalSplits, int completedDrivers, long activePartitionedSplitsWeight, boolean partitioned)
    {
        PipelineStatusBuilder builder = new PipelineStatusBuilder(totalSplits, completedDrivers, activePartitionedSplitsWeight, partitioned);
        while (driverContextsIterator.hasNext()) {
            builder.accumulate(driverContextsIterator.next());
        }
        return builder.build();
    }

    /**
     * Allows building a {@link PipelineStatus} either from a series of {@link DriverContext} instances or
     * {@link DriverStats} instances. In {@link PipelineContext#getPipelineStats()} where {@link DriverStats}
     * instances are already created as a state snapshot of {@link DriverContext}, using those instead of
     * re-checking the fields on {@link DriverContext} is cheaper since it avoids extra volatile reads and
     * reduces the opportunities to read inconsistent values
     */
    private static final class PipelineStatusBuilder
    {
        private final int totalSplits;
        private final int completedDrivers;
        private final long activePartitionedSplitsWeight;
        private final boolean partitioned;
        private int runningDrivers;
        private int blockedDrivers;
        private long runningSplitsWeight;
        private long blockedSplitsWeight;
        // When a split for a partitioned pipeline is delivered to a worker,
        // conceptually, the worker would have an additional driver.
        // The queuedDrivers field in PipelineStatus is supposed to represent this.
        // However, due to implementation details of SqlTaskExecution, it may defer instantiation of drivers.
        //
        // physically queued drivers: actual number of instantiated drivers whose execution hasn't started
        // conceptually queued drivers: includes assigned splits that haven't been turned into a driver
        private int physicallyQueuedDrivers;

        private PipelineStatusBuilder(int totalSplits, int completedDrivers, long activePartitionedSplitsWeight, boolean partitioned)
        {
            this.totalSplits = totalSplits;
            this.partitioned = partitioned;
            this.completedDrivers = completedDrivers;
            this.activePartitionedSplitsWeight = activePartitionedSplitsWeight;
        }

        public void accumulate(DriverContext driverContext)
        {
            if (!driverContext.isExecutionStarted()) {
                physicallyQueuedDrivers++;
            }
            else if (driverContext.isFullyBlocked()) {
                blockedDrivers++;
                blockedSplitsWeight += driverContext.getSplitWeight();
            }
            else {
                runningDrivers++;
                runningSplitsWeight += driverContext.getSplitWeight();
            }
        }

        public void accumulate(DriverStats driverStats, long splitWeight)
        {
            if (driverStats.getStartTimeInMillis() == 0) {
                // driver has not started running
                physicallyQueuedDrivers++;
            }
            else if (driverStats.isFullyBlocked()) {
                blockedDrivers++;
                blockedSplitsWeight += splitWeight;
            }
            else {
                runningDrivers++;
                runningSplitsWeight += splitWeight;
            }
        }

        public PipelineStatus build()
        {
            int queuedDrivers;
            int queuedPartitionedSplits;
            int runningPartitionedSplits;
            long queuedPartitionedSplitsWeight;
            long runningPartitionedSplitsWeight;
            if (partitioned) {
                queuedDrivers = totalSplits - runningDrivers - blockedDrivers - completedDrivers;
                if (queuedDrivers < 0) {
                    // It is possible to observe negative here because inputs to the above expression was not taken in a snapshot.
                    queuedDrivers = 0;
                }
                queuedPartitionedSplitsWeight = activePartitionedSplitsWeight - runningSplitsWeight - blockedSplitsWeight;
                if (queuedDrivers == 0 || queuedPartitionedSplitsWeight < 0) {
                    // negative or inconsistent count vs weight inputs might occur
                    queuedPartitionedSplitsWeight = 0;
                }
                queuedPartitionedSplits = queuedDrivers;
                runningPartitionedSplits = runningDrivers;
                runningPartitionedSplitsWeight = runningSplitsWeight;
            }
            else {
                queuedDrivers = physicallyQueuedDrivers;
                queuedPartitionedSplits = 0;
                queuedPartitionedSplitsWeight = 0;
                runningPartitionedSplits = 0;
                runningPartitionedSplitsWeight = 0;
            }
            return new PipelineStatus(queuedDrivers, runningDrivers, blockedDrivers, queuedPartitionedSplits, queuedPartitionedSplitsWeight, runningPartitionedSplits, runningPartitionedSplitsWeight);
        }
    }
}
