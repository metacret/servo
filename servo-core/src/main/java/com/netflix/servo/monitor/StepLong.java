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

import com.netflix.servo.DefaultMonitorRegistry;
import com.netflix.servo.util.Clock;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Utility class for managing a set of AtomicLong instances mapped to a particular step interval.
 * The current implementation keeps an array of with two items where one is the current value
 * being updated and the other is the value from the previous interval and is only available for
 * polling.
 */
class StepLong {

    private static final Counter REPOLLED_INTERVALS = newCounter("servo.monitor.repolledIntervals");
    private static final Counter POLLED_INTERVALS = newCounter("servo.monitor.polledIntervals");
    private static final Counter MISSED_INTERVALS = newCounter("servo.monitor.missedIntervals");

    private static Counter newCounter(String name) {
        Counter c = Monitors.newCounter(name);
        DefaultMonitorRegistry.getInstance().register(c);
        return c;
    }

    private final long init;
    private final Clock clock;

    private final AtomicLong[] data;

    private final AtomicLong[] lastPollTime;

    private final AtomicLong[] lastInitPos;

    StepLong(long init, Clock clock) {
        this.init = init;
        this.clock = clock;
        lastInitPos = new AtomicLong[Pollers.NUM_POLLERS];
        lastPollTime = new AtomicLong[Pollers.NUM_POLLERS];
        for (int i = 0; i < Pollers.NUM_POLLERS; ++i) {
            lastInitPos[i] = new AtomicLong(0L);
            lastPollTime[i] = new AtomicLong(0L);
        }
        data = new AtomicLong[2 * Pollers.NUM_POLLERS];
        for (int i = 0; i < data.length; ++i) {
            data[i] = new AtomicLong(init);
        }
    }

    void addAndGet(long amount) {
        for (int i = 0; i < Pollers.NUM_POLLERS; ++i) {
            int pos = getIndex(i);
            data[pos].addAndGet(amount);
        }
    }

    private int getIndex(int pollerIndex) {
        final long now = clock.now();
        final long step = Pollers.POLLING_INTERVALS[pollerIndex];
        final long stepTime = now / step;
        final int pos = 2 * pollerIndex + (int) (stepTime % 2);
        final long v = data[pos].get();
        final long lastInit = lastInitPos[pollerIndex].get();
        if (lastInit != stepTime && lastInitPos[pollerIndex].compareAndSet(lastInit, stepTime)) {
            data[pos].compareAndSet(v, init);
        }
        return pos;
    }

    AtomicLong getCurrent(int pollerIndex) {
        return data[getIndex(pollerIndex)];
    }

    Datapoint poll(int pollerIndex) {
        final long now = clock.now();
        final long step = Pollers.POLLING_INTERVALS[pollerIndex];
        final long stepTime = now / step;
        final int prevPos = 2 * pollerIndex + (int) ((stepTime + 1) % 2);
        final long value = data[prevPos].getAndSet(init);

        final long last = lastPollTime[pollerIndex].getAndSet(now);
        final long missed = (now - last) / step - 1;

        if (last / step == now / step) {
            REPOLLED_INTERVALS.increment();
            return new Datapoint(now / step * step, value);
        } else if (last > 0L && missed > 0L) {
            MISSED_INTERVALS.increment(missed);
            return Datapoint.UNKNOWN;
        } else {
            POLLED_INTERVALS.increment();
            return new Datapoint(now / step * step, value);
        }
    }

    @Override
    public String toString() {
        return "StepLong{" +
                "init=" + init +
                ", data=" + Arrays.toString(data) +
                ", lastPollTime=" + Arrays.toString(lastPollTime) +
                ", lastInitPos=" + Arrays.toString(lastInitPos) +
                '}';
    }
}

