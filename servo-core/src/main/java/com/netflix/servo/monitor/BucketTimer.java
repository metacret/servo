/**
 * Copyright 2013 Netflix, Inc.
 *
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
package com.netflix.servo.monitor;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.netflix.servo.tag.Tag;
import com.netflix.servo.tag.Tags;
import com.netflix.servo.util.Clock;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;


/**
 * A simple timer implementation providing the total time, count, min, and max for the times that
 * have been recorded.
 */
public class BucketTimer extends AbstractMonitor<Long> implements Timer, CompositeMonitor<Long> {

    private static final String STATISTIC = "statistic";
    private static final String BUCKET = "servo.bucket";
    private static final String UNIT = "unit";

    private static final Tag STAT_TOTAL = Tags.newTag(STATISTIC, "totalTime");
    private static final Tag STAT_COUNT = Tags.newTag(STATISTIC, "count");
    private static final Tag STAT_MIN = Tags.newTag(STATISTIC, "min");
    private static final Tag STAT_MAX = Tags.newTag(STATISTIC, "max");

    private final TimeUnit timeUnit;

    private final Counter totalTime;
    private final Counter[] bucketCount;
    private final Counter overflowCount;

    private final MinGauge min;
    private final MaxGauge max;

    private final List<Monitor<?>> monitors;
    private final BucketConfig bucketConfig;

    /**
     * Creates a new instance of the timer with a unit of milliseconds.
     */
    public BucketTimer(MonitorConfig config, BucketConfig bucketConfig) {
        this(config, bucketConfig, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a new instance of the timer.
     */
    public BucketTimer(MonitorConfig config, BucketConfig bucketConfig, TimeUnit unit) {
        this(config, bucketConfig, unit, Clock.WALL);
    }

    BucketTimer(MonitorConfig config, BucketConfig bucketConfig, TimeUnit unit, Clock clock) {
        super(config);
        this.bucketConfig = Preconditions.checkNotNull(bucketConfig, "bucketConfig");

        final Tag unitTag = Tags.newTag(UNIT, unit.name());
        final MonitorConfig unitConfig = config.withAdditionalTag(unitTag);
        this.timeUnit = unit;

        this.totalTime = new BasicCounter(unitConfig.withAdditionalTag(STAT_TOTAL));
        this.overflowCount = new BasicCounter(unitConfig
            .withAdditionalTag(STAT_COUNT)
            .withAdditionalTag(Tags.newTag(BUCKET, "bucket=overflow")));
        this.min = new MinGauge(unitConfig.withAdditionalTag(STAT_MIN), clock);
        this.max = new MaxGauge(unitConfig.withAdditionalTag(STAT_MAX), clock);

        final long[] buckets = bucketConfig.getBuckets();
        final int numBuckets = buckets.length;
        final int numDigits  = Long.toString(buckets[numBuckets - 1]).length();
        final String label   = bucketConfig.getTimeUnitAbbreviation();

        this.bucketCount = new Counter[numBuckets];

        for (int i = 0; i < numBuckets; i++) {
            bucketCount[i] = new BasicCounter(unitConfig
                .withAdditionalTag(STAT_COUNT)
                .withAdditionalTag(Tags.newTag(BUCKET, String.format("bucket=%0" + numDigits + "d%s", buckets[i], label)))
            );
        }

        this.monitors = new ImmutableList.Builder<Monitor<?>>()
            .add(totalTime)
            .add(min)
            .add(max)
            .addAll(Arrays.asList(bucketCount))
            .add(overflowCount)
            .build();
    }

    /** {@inheritDoc} */
    @Override
    public List<Monitor<?>> getMonitors() {
        return monitors;
    }

    /** {@inheritDoc} */
    @Override
    public Stopwatch start() {
        Stopwatch s = new TimedStopwatch(this);
        s.start();
        return s;
    }

    /** {@inheritDoc} */
    @Override
    public TimeUnit getTimeUnit() {
        return timeUnit;
    }

    /** {@inheritDoc} */
    @Override
    public void record(long duration) {
        totalTime.increment(duration);
        min.update(duration);
        max.update(duration);

        final long[] buckets = bucketConfig.getBuckets();
        for (int i = 0; i < buckets.length; i++) {
            if (duration <= buckets[i]) {
                bucketCount[i].increment();
                return;
            }
        }
        overflowCount.increment();
    }

    /** {@inheritDoc} */
    @Override
    public void record(long duration, TimeUnit unit) {
        record(this.timeUnit.convert(duration, unit));
    }

    /** {@inheritDoc} */
    @Override
    public Long getValue(int pollerIndex) {
        final long cnt = getCount(pollerIndex);
        return (cnt == 0) ? 0L : totalTime.getValue().longValue() / cnt;
    }

    /** Get the total time for all updates. */
    public Long getTotalTime() {
        return totalTime.getValue().longValue();
    }

    /** Get the total number of updates. */
    public Long getCount(int pollerIndex) {
        long updates = 0;
        for (Counter c : bucketCount) {
            updates += c.getValue(pollerIndex).longValue();
        }
        updates += overflowCount.getValue(pollerIndex).longValue();

        return updates;
    }

    /** Get the min value since the last reset. */
    public Long getMin(int pollerIndex) {
        return min.getValue(pollerIndex);
    }

    /** Get the max value since the last reset. */
    public Long getMax(int pollerIndex) {
        return max.getValue(pollerIndex);
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof BucketTimer)) {
            return false;
        }
        BucketTimer m = (BucketTimer) obj;
        return config.equals(m.getConfig())
            && bucketConfig.equals(m.bucketConfig)
            && timeUnit.equals(m.timeUnit)
            && totalTime.equals(m.totalTime)
            && min.equals(m.min)
            && max.equals(m.max)
            && overflowCount.equals(m.overflowCount)
            && Arrays.equals(bucketCount, m.bucketCount);
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return Objects.hashCode(
            config,
            bucketConfig,
            timeUnit,
            totalTime,
            min,
            max,
            overflowCount,
            Arrays.hashCode(bucketCount)
        );
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("config", config)
                .add("bucketConfig", bucketConfig)
                .add("timeUnit", timeUnit)
                .add("totalTime", totalTime)
                .add("min", min)
                .add("max", max)
                .add("bucketCount", bucketCount)
                .add("overflowCount", overflowCount)
                .toString();
    }
}
