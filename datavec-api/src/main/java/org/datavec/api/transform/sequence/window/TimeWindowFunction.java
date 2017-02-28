/*-
 *  * Copyright 2016 Skymind, Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 */

package org.datavec.api.transform.sequence.window;

import org.datavec.api.writable.LongWritable;
import org.datavec.api.transform.ColumnType;
import org.datavec.api.transform.metadata.ColumnMetaData;
import org.datavec.api.transform.metadata.TimeMetaData;
import org.datavec.api.transform.schema.Schema;
import org.datavec.api.transform.schema.SequenceSchema;
import org.datavec.api.writable.Writable;
import org.joda.time.DateTimeZone;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A windowing function based on time, with non-overlapping windows. Time for each entry in the sequence is provided by a Time column<br>
 * Functionality here: Calculate windows of data based on a fixed window size (1 minute, 1 hour, etc), with an optional offset.<br>
 * Specifically, window start times T are calculated such that (T + timeZoneOffset + offset) % windowSize == 0
 * timeZoneOffset comes from the Time column metadata; offset allows for the window to be shifted one way or another,
 * for example to allow for windowing like (10:15 to 11:15) instead of (10:00 to 11:00), using an offset of 15 minutes<br>
 * <p/>
 * Note that the windows generated by this window function need not contain any data - i.e., it can generate empty an empty
 * window if no data occurs in the specified time period.
 *
 * @author Alex Black
 */
public class TimeWindowFunction implements WindowFunction {

    private final String timeColumn;
    private final long windowSize;
    private final TimeUnit windowSizeUnit;
    private final long offsetAmount;
    private final TimeUnit offsetUnit;
    private final boolean addWindowStartTimeColumn;
    private final boolean addWindowEndTimeColumn;
    private final boolean excludeEmptyWindows;
    private Schema inputSchema;

    private final long offsetAmountMilliseconds;
    private final long windowSizeMilliseconds;

    private DateTimeZone timeZone;

    /**
     * Constructor with zero offset
     *
     * @param timeColumn     Name of the column that contains the time values (must be a time column)
     * @param windowSize     Numerical quantity for the size of the time window (used in conjunction with windowSizeUnit)
     * @param windowSizeUnit Unit of the time window
     */
    public TimeWindowFunction(String timeColumn, long windowSize, TimeUnit windowSizeUnit) {
        this(timeColumn, windowSize, windowSizeUnit, 0, null);
    }

    /**
     * Constructor with zero offset, and supports adding columns containing the start and/or end times of the window
     *
     * @param timeColumn               Name of the column that contains the time values (must be a time column)
     * @param windowSize               Numerical quantity for the size of the time window (used in conjunction with windowSizeUnit)
     * @param windowSizeUnit           Unit of the time window
     * @param addWindowStartTimeColumn If true: add a time column (name: "windowStartTime") that contains the start time
     *                                 of the window
     * @param addWindowStartTimeColumn If true: add a time column (name: "windowEndTime") that contains the end time
     *                                 of the window
     */
    public TimeWindowFunction(String timeColumn, long windowSize, TimeUnit windowSizeUnit, boolean addWindowStartTimeColumn,
                              boolean addWindowEndTimeColumn) {
        this(timeColumn, windowSize, windowSizeUnit, 0, null, addWindowStartTimeColumn, addWindowEndTimeColumn, false);
    }

    /**
     * Constructor with optional offset
     *
     * @param timeColumn     Name of the column that contains the time values (must be a time column)
     * @param windowSize     Numerical quantity for the size of the time window (used in conjunction with windowSizeUnit)
     * @param windowSizeUnit Unit of the time window
     * @param offset         Optional offset amount, to shift start/end of the time window forward or back
     * @param offsetUnit     Optional offset unit for the offset amount.
     */
    public TimeWindowFunction(String timeColumn, long windowSize, TimeUnit windowSizeUnit, long offset, TimeUnit offsetUnit) {
        this(timeColumn, windowSize, windowSizeUnit, offset, offsetUnit, false, false, false);
    }

    /**
     * Constructor with optional offset
     *
     * @param timeColumn               Name of the column that contains the time values (must be a time column)
     * @param windowSize               Numerical quantity for the size of the time window (used in conjunction with windowSizeUnit)
     * @param windowSizeUnit           Unit of the time window
     * @param offset                   Optional offset amount, to shift start/end of the time window forward or back
     * @param offsetUnit               Optional offset unit for the offset amount.
     * @param addWindowStartTimeColumn If true: add a column (at the end) with the window start time
     * @param addWindowEndTimeColumn   If true: add a column (at the end) with the window end time
     * @param excludeEmptyWindows      If true: exclude any windows that don't have any values in them
     */
    public TimeWindowFunction(String timeColumn, long windowSize, TimeUnit windowSizeUnit, long offset, TimeUnit offsetUnit,
                              boolean addWindowStartTimeColumn, boolean addWindowEndTimeColumn, boolean excludeEmptyWindows) {
        this.timeColumn = timeColumn;
        this.windowSize = windowSize;
        this.windowSizeUnit = windowSizeUnit;
        this.offsetAmount = offset;
        this.offsetUnit = offsetUnit;
        this.addWindowStartTimeColumn = addWindowStartTimeColumn;
        this.addWindowEndTimeColumn = addWindowEndTimeColumn;
        this.excludeEmptyWindows = excludeEmptyWindows;

        if (offsetAmount == 0 || offsetUnit == null) this.offsetAmountMilliseconds = 0;
        else {
            this.offsetAmountMilliseconds = TimeUnit.MILLISECONDS.convert(offset, offsetUnit);
        }

        this.windowSizeMilliseconds = TimeUnit.MILLISECONDS.convert(windowSize, windowSizeUnit);
    }

    private TimeWindowFunction(Builder builder) {
        this(builder.timeColumn, builder.windowSize, builder.windowSizeUnit, builder.offsetAmount, builder.offsetUnit,
                builder.addWindowStartTimeColumn, builder.addWindowEndTimeColumn, builder.excludeEmptyWindows);
    }

    @Override
    public void setInputSchema(Schema schema) {
        if (!(schema instanceof SequenceSchema))
            throw new IllegalArgumentException("Invalid schema: TimeWindowFunction can "
                    + "only operate on SequenceSchema");
        if (!schema.hasColumn(timeColumn))
            throw new IllegalStateException("Input schema does not have a column with name \""
                    + timeColumn + "\"");

        if (schema.getMetaData(timeColumn).getColumnType() != ColumnType.Time) throw new IllegalStateException(
                "Invalid column: column \"" + timeColumn + "\" is not of type " + ColumnType.Time + "; is " +
                        schema.getMetaData(timeColumn).getColumnType());

        this.inputSchema = schema;

        timeZone = ((TimeMetaData) schema.getMetaData(timeColumn)).getTimeZone();
    }

    @Override
    public Schema getInputSchema() {
        return inputSchema;
    }

    @Override
    public Schema transform(Schema inputSchema) {
        if (!addWindowStartTimeColumn && !addWindowEndTimeColumn) return inputSchema;
        List<ColumnMetaData> newMeta = new ArrayList<>();

        newMeta.addAll(inputSchema.getColumnMetaData());

        if (addWindowStartTimeColumn) {
            newMeta.add(new TimeMetaData("windowStartTime"));
        }

        if (addWindowEndTimeColumn) {
            newMeta.add(new TimeMetaData("windowEndTime"));
        }

        return inputSchema.newSchema(newMeta);
    }

    @Override
    public String toString() {
        return "TimeWindowFunction(column=\"" + timeColumn + "\",windowSize=" + windowSize + windowSizeUnit + ",offset="
                + offsetAmount + (offsetAmount != 0 && offsetUnit != null ? offsetUnit : "") +
                (addWindowStartTimeColumn ? ",addWindowStartTimeColumn=true" : "") + (addWindowEndTimeColumn ? ",addWindowEndTimeColumn=true" : "")
                + (excludeEmptyWindows ? ",excludeEmptyWindows=true" : "") + ")";
    }


    @Override
    public List<List<List<Writable>>> applyToSequence(List<List<Writable>> sequence) {

        int timeColumnIdx = inputSchema.getIndexOfColumn(this.timeColumn);

        List<List<List<Writable>>> out = new ArrayList<>();

        //We are assuming here that the sequence is already ordered (as is usually the case)
        long currentWindowStartTime = Long.MIN_VALUE;
        List<List<Writable>> currentWindow = null;
        for (List<Writable> timeStep : sequence) {
            long currentTime = timeStep.get(timeColumnIdx).toLong();
            long windowStartTimeOfThisTimeStep = getWindowStartTimeForTime(currentTime);

            //First time step...
            if (currentWindowStartTime == Long.MIN_VALUE) {
                currentWindowStartTime = windowStartTimeOfThisTimeStep;
                currentWindow = new ArrayList<>();
            }

            //Two possibilities here: (a) we add it to the last time step, or (b) we need to make a new window...
            if (currentWindowStartTime < windowStartTimeOfThisTimeStep) {
                //New window. But: complication. We might have a bunch of empty windows...
                while (currentWindowStartTime < windowStartTimeOfThisTimeStep) {
                    if (currentWindow != null) {
                        if (!(excludeEmptyWindows && currentWindow.size() == 0)) out.add(currentWindow);
                    }
                    currentWindow = new ArrayList<>();
                    currentWindowStartTime += windowSizeMilliseconds;
                }
            }
            if (addWindowStartTimeColumn || addWindowEndTimeColumn) {
                List<Writable> timeStep2 = new ArrayList<>(timeStep);
                if (addWindowStartTimeColumn) timeStep2.add(new LongWritable(currentWindowStartTime));
                if (addWindowEndTimeColumn)
                    timeStep2.add(new LongWritable(currentWindowStartTime + windowSizeMilliseconds));
                currentWindow.add(timeStep2);
            } else {
                currentWindow.add(timeStep);
            }
        }

        //Add the final window to the output data...
        if (!(excludeEmptyWindows && currentWindow.size() == 0) && currentWindow != null) out.add(currentWindow);

        return out;
    }


    /**
     * Calculates the start time of the window for which the specified time belongs, in unix epoch (millisecond) format<br>
     * For example, if the window size is 1 hour with offset 0, then a time 10:17 would return 10:00, as the 1 hour window
     * is for 10:00:00.000 to 10:59:59.999 inclusive, or 10:00:00.000 (inclusive) to 11:00:00.000 (exclusive)
     *
     * @param time Time at which to determine the window start time (milliseconds epoch format)
     */
    public long getWindowStartTimeForTime(long time) {

        //Calculate aggregate offset: aggregate offset is due to both timezone and manual offset
        long aggregateOffset = (timeZone.getOffset(time) + this.offsetAmountMilliseconds) % this.windowSizeMilliseconds;

        return (time + aggregateOffset) - (time + aggregateOffset) % this.windowSizeMilliseconds;
    }

    /**
     * Calculates the end time of the window for which the specified time belongs, in unix epoch (millisecond) format.
     * <b>Note</b>: this value is not included in the interval. Put another way, it is the start time of the <i>next</i>
     * interval: i.e., is equivalent to {@link #getWindowStartTimeForTime(long)} + interval (in milliseconds).<br>
     * To get the last <i>inclusive</i> time for the interval, subtract 1L (1 millisecond) from the value returned by
     * this method.<br>
     * For example, if the window size is 1 hour with offset 0, then a time 10:17 would return 11:00, as the 1 hour window
     * is for 10:00:00.000 (inclusive) to 11:00:00.000 (exclusive)
     *
     * @param time Time at which to determine the window start time
     * @return
     */
    public long getWindowEndTimeForTime(long time) {
        return getWindowStartTimeForTime(time) + this.windowSizeMilliseconds;
    }

    public static class Builder {
        private String timeColumn;
        private long windowSize = -1;
        private TimeUnit windowSizeUnit;
        private long offsetAmount;
        private TimeUnit offsetUnit;
        private boolean addWindowStartTimeColumn = false;
        private boolean addWindowEndTimeColumn = false;
        private boolean excludeEmptyWindows = false;

        public Builder timeColumn(String timeColumn) {
            this.timeColumn = timeColumn;
            return this;
        }

        public Builder windowSize(long windowSize, TimeUnit windowSizeUnit) {
            this.windowSize = windowSize;
            this.windowSizeUnit = windowSizeUnit;
            return this;
        }

        public Builder offset(long offsetAmount, TimeUnit offsetUnit) {
            this.offsetAmount = offsetAmount;
            this.offsetUnit = offsetUnit;
            return this;
        }

        public Builder addWindowStartTimeColumn(boolean addWindowStartTimeColumn) {
            this.addWindowStartTimeColumn = addWindowStartTimeColumn;
            return this;
        }

        public Builder addWindowEndTimeColumn(boolean addWindowEndTimeColumn) {
            this.addWindowEndTimeColumn = addWindowEndTimeColumn;
            return this;
        }

        public Builder excludeEmptyWindows(boolean excludeEmptyWindows) {
            this.excludeEmptyWindows = excludeEmptyWindows;
            return this;
        }

        public TimeWindowFunction build() {
            if (timeColumn == null) throw new IllegalStateException("Time column is null (not specified)");
            if (windowSize == -1 || windowSizeUnit == null) throw new IllegalStateException("Window size/unit not set");
            return new TimeWindowFunction(this);
        }
    }
}
