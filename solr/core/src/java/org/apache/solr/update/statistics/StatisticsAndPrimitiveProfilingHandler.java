package org.apache.solr.update.statistics;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.solr.common.ExpandingLongArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

public class StatisticsAndPrimitiveProfilingHandler {
  
  private static final long DEFAULT_LOG_TIME_INTERVAL_MS = 5 * 60 * 1000; // log stats (at most) every 5 min
  private static final Logger log = LoggerFactory.getLogger(StatisticsAndPrimitiveProfilingHandler.class);
  
  private Thread loggingThread; 

  private final boolean resetAfterEachLog;
  private final List<StatisticsAndPrimitiveProfiling> statsAndPrimProfs;
  
  public interface StatisticsAndPrimitiveProfiling {
    void log(boolean reset);
  }
  
  public StatisticsAndPrimitiveProfilingHandler(boolean resetAfterEachLog) {
    this.resetAfterEachLog = resetAfterEachLog;
    statsAndPrimProfs = new ArrayList<StatisticsAndPrimitiveProfiling>();
  }
  
  Object logSynchObj = new Object();
  protected void log() {
    synchronized (logSynchObj) {
      for (StatisticsAndPrimitiveProfiling sapp : statsAndPrimProfs) {
        sapp.log(resetAfterEachLog);
      }
    }
  }
  
  public void addToBeLogged(StatisticsAndPrimitiveProfiling statsAndPrimProf) {
    synchronized (logSynchObj) {
      if (!statsAndPrimProfs.contains(statsAndPrimProf)) {
        statsAndPrimProfs.add(statsAndPrimProf);
      } else {
        log.warn("Trying to add " + StatisticsAndPrimitiveProfiling.class.getName() + " to be logged, but it has already been added");
      }
    }
  }
  
  public void startAutomatiLogging() {
    startAutomatiLogging(DEFAULT_LOG_TIME_INTERVAL_MS, false);
  }
  
  public synchronized void startAutomatiLogging(final long timeIntervalMS, final boolean logFirst) {
    if (loggingThread == null) {
      log.info("Starting automatic logging every " + timeIntervalMS + " ms" + ((logFirst)?"":". First logging will occur in " + timeIntervalMS + " ms"));
      loggingThread = new Thread(new Runnable() {
        
        private boolean stopped = false;

        @Override
        public void run() {
          if (logFirst) log();
          while (!stopped) {
            try {
              Thread.sleep(timeIntervalMS);
            } catch (InterruptedException e) {
              stopped = true;
            }
            log();
          }
        }
        
      });
      loggingThread.start();
    } else {
      log.warn("Trying to start automatic logging, but it has already been started");
    }
  }

  public synchronized void stopAutomatiLogging() {
    if (loggingThread != null) {
      log.info("Stopping automatic logging");
      loggingThread.interrupt();
      try {
        loggingThread.join(10000);
        log.info("Automatic logging stopped");
      } catch (InterruptedException e) {
        log.warn("Automatic logging did not stop properly within 10 secs");
      }
    } else {
      log.warn("Trying to stop automatic logging, but it has not been started");
    }
  }
  
  // Helper classes for holding and updating statistics (in StatisticsAndPrimitiveProfiling implementors)
  
  public static class CountStatsEntry {
    
    protected final String id;
    protected final String description;
    protected volatile int count;
    
    public CountStatsEntry(String id, String description) {
      this.id = id + ")";
      this.description = description;
      reset();
    }
    
    public void reset() {
      count = 0;
    }
    
    public void register() {
      count++;
    }
    
    public void add(CountStatsEntry statsToAdd) {
      count += statsToAdd.count; 
    }
    
    @Override
    public String toString() {
      final StringBuilder builder = new StringBuilder();
      builder.append(id).append(" ").append(description);
      builder.append(": count=");
      builder.append(count);
      return builder.toString();
    }

  }
  
  public static class CountAndPrimProfStatsEntry extends CountStatsEntry {

    public static final long DEFAULT_LEVEL_NANOSECS = 1000000; // Default 1 millisecond
    private static final long SCALE = 10;
    
    protected final CountAndPrimProfStatsEntry parent;
    protected final List<CountAndPrimProfStatsEntry> children;
    protected volatile long timeTotalNanosec;
    protected volatile long timeMaxNanosec;
    
    private volatile long levelNanosec;
    private volatile long[] levels = new long[6]; // Levels from 1 - 10000 with SCALA=10
    
    private final boolean collectAllTimes;
    private final ExpandingLongArray allCollectedTimes;
    
    public CountAndPrimProfStatsEntry(String id, String description, CountAndPrimProfStatsEntry parent) {
      this(id, description, parent, DEFAULT_LEVEL_NANOSECS, false);
    }
    
    public CountAndPrimProfStatsEntry(String id, String description, CountAndPrimProfStatsEntry parent, long levelNanosec, boolean collectAllTimes) {
      super(id, description);
      this.parent = parent;
      if (parent != null) {
        parent.registerChild(this);
      }
      this.children = new ArrayList<CountAndPrimProfStatsEntry>();
      this.levelNanosec = levelNanosec;
      this.collectAllTimes = collectAllTimes;
      allCollectedTimes = (collectAllTimes)?new ExpandingLongArray():null;
    }
    
    private void registerChild(CountAndPrimProfStatsEntry child) {
      children.add(child);
    }

    @Override
    public void reset() {
      if (count != 0 && timeTotalNanosec != 0) {
        final long newAverage =  timeTotalNanosec / count;
        if (0 < newAverage) {
          levelNanosec = newAverage;
        }
      }
      super.reset();
      timeTotalNanosec = 0;
      timeMaxNanosec = 0;
      if (levels != null) {
        for (int i = 0; i < levels.length; i++) {
          levels[i] = 0;
        }
      }
      if (collectAllTimes) allCollectedTimes.reset();
    }
    
    public void register() {
      throw new UnsupportedOperationException();
    }
    
    public void register(final long startTimeNanosec) {
      final long endTimeNanosec = System.nanoTime();
      register(startTimeNanosec, endTimeNanosec);
    }

    public void register(final long startTimeNanosec, final long endTimeNanosec) {
      super.register();
      
      final long timeSpentNanosec = endTimeNanosec - startTimeNanosec;
      
      timeTotalNanosec += timeSpentNanosec;
      timeMaxNanosec = Math.max(timeMaxNanosec, timeSpentNanosec);
      addToLevel(timeSpentNanosec);
    }
    
    private void addToLevel(long timeSpentNanosec) {
      long level = levelNanosec;
      int index = 0;
      while (level < timeSpentNanosec && index < levels.length - 1) {
        index++;
        level = level * SCALE;
      }
      levels[index]++;
      if (collectAllTimes) allCollectedTimes.add(timeSpentNanosec);
    }
    
    @Override
    public void add(CountStatsEntry statsToAdd) {
      super.add(statsToAdd);
      
      CountAndPrimProfStatsEntry statsToAddCasted = (CountAndPrimProfStatsEntry)statsToAdd;
      timeTotalNanosec += statsToAddCasted.timeTotalNanosec;
      timeMaxNanosec = Math.max(timeMaxNanosec, statsToAddCasted.timeMaxNanosec);
      if (levelNanosec == statsToAddCasted.levelNanosec) {
        for (int i = 0; i < levels.length; i++) {
          levels[i] += statsToAddCasted.levels[i];
        }
      } else if (statsToAddCasted.collectAllTimes) {
        for (int i = 0; i < statsToAddCasted.allCollectedTimes.size(); i++) {
          addToLevel(statsToAddCasted.allCollectedTimes.get(i));
        }
      } else {
        log.warn("Trying to add two " + getClass().getSimpleName() + "s together with different levelNanosec - level info will not be added." + 
            " To support this set collectAllTimes to true on the stats to be added");
      }
    }
    
    DecimalFormat df = new DecimalFormat("#.00", DecimalFormatSymbols.getInstance(Locale.US));
    @Override
    public String toString() {
      final StringBuilder builder = new StringBuilder(super.toString());
      builder.append(", total time (inclusive)=");
      builder.append(timeTotalNanosec);
      if (children.size() > 0) {
        builder.append("ns, total time (exclusive)=");
        long timeTotalExcNanosec = timeTotalNanosec;
        for (CountAndPrimProfStatsEntry child : children) {
          timeTotalExcNanosec -= child.timeTotalNanosec;
        }
        builder.append(timeTotalExcNanosec);
      }
      builder.append("ns, average time=");
      builder.append((count != 0)?(new Long(timeTotalNanosec / count).toString()+"ns"):"N/A");
      builder.append(", max time=");
      builder.append(timeMaxNanosec);
      builder.append("ns");
      if (parent != null) {
        builder.append(", pct of ").append(parent.id).append("=");
        builder.append((parent.timeTotalNanosec != 0)?(df.format(((double) timeTotalNanosec / parent.timeTotalNanosec) * 100)):"N/A");
      }
      if (0 < count) {
        builder.append(", distribution:");
        boolean separator = false;
        // Below lowest level
        separator = addDistribution(builder, "≤" + levelNanosec + "ns", levels[0], separator);
        long level = levelNanosec;
        for (int i = 1; i < levels.length - 1; i++) {
          final long nextLevel = level * SCALE;
          // Between levels
          separator = addDistribution(builder, "]" + level + "-" + nextLevel + "ns]", levels[i], separator);
          level = nextLevel;
        }
        // Above highest level
        addDistribution(builder, ">" + level + "ns", levels[levels.length - 1], separator);
      }
      return builder.toString();
    }

    private final boolean addDistribution(final StringBuilder builder, final String name, final long value, final boolean separator) {
      if (0 < value) {
        if (separator) {
          builder.append(",");
        }
        builder.append(" ");
        builder.append(name);
        builder.append("=");
        builder.append(value);
        return true;
      }
      return separator;
    }
  }
  
  public static class CountAndPrimProfAndValueStatsEntry extends CountAndPrimProfStatsEntry {
    
    protected String valueStr;
    protected boolean printTotalValue;
    protected boolean printAverageValue;
    protected boolean printMaxValue;
    protected volatile long valueTotal;
    protected volatile long valueMax;
    
    public CountAndPrimProfAndValueStatsEntry(String id, String description, CountAndPrimProfStatsEntry parent, String valueStr, boolean printTotalValue, boolean printAverageValue, boolean printMaxValue) {
      super(id, description, parent);
      init(valueStr, printTotalValue, printAverageValue, printMaxValue);
    }
    
    public CountAndPrimProfAndValueStatsEntry(String id, String description, CountAndPrimProfStatsEntry parent, String valueStr, boolean printTotalValue, boolean printAverageValue, boolean printMaxValue, long levelNanosec, boolean collectAllTimes) {
      super(id, description, parent, levelNanosec, collectAllTimes);
      init(valueStr, printTotalValue, printAverageValue, printMaxValue);
    }
    
    private void init(String valueStr, boolean printTotalValue, boolean printAverageValue, boolean printMaxValue) {
      this.valueStr = valueStr;
      this.printTotalValue = printTotalValue;
      this.printAverageValue = printAverageValue;
      this.printMaxValue = printMaxValue;
    }

    @Override
    public void reset() {
      super.reset();
      
      valueTotal = 0;
      valueMax = 0;
    }
    
    @Override
    public void register(final long startTimeNanosec) {
      throw new UnsupportedOperationException();
    }
    
    public void register(final long startTimeNanosec, final int value) {
      final long endTimeNanosec = System.nanoTime();
      register(startTimeNanosec, endTimeNanosec, value);
    }
    
    public void register(final long startTimeNanosec, final long endTimeNanosec, final int value) {
      super.register(startTimeNanosec, endTimeNanosec);

      valueTotal += value;
      valueMax = Math.max(valueMax, value);
    }
    
    @Override
    public void add(CountStatsEntry statsToAdd) {
      super.add(statsToAdd);
      
      CountAndPrimProfAndValueStatsEntry statsToAddCasted = (CountAndPrimProfAndValueStatsEntry)statsToAdd;
      valueTotal += statsToAddCasted.valueTotal;
      valueMax = Math.max(valueMax, statsToAddCasted.valueMax);
    }

    @Override
    public String toString() {
      final StringBuilder builder = new StringBuilder(super.toString());
      if (printTotalValue) {
        builder.append(", total ").append(valueStr).append("=");
        builder.append(valueTotal);
      }
      if (printAverageValue) {
        builder.append(", average ").append(valueStr).append("=");
        if (count != 0) {
          builder.append(Math.round((double)valueTotal / count));
        } else {
          builder.append("N/A");
        }
      }
      if (printMaxValue) {
        builder.append(", max ").append(valueStr).append("=");
        builder.append(valueMax);
      }
      return builder.toString();
    }

  }
  
  public static final class IntegerId {
    
    int id;
    
    public String getNext() {
      return "" + id++;
    }
    
  }
  
  public static final String LOG_INDENT = "    ";
  public static final String NEW_LINE = "\n";
  public static final String NEW_LINE_LOG_INDENT = NEW_LINE + LOG_INDENT;
  public static final String NEW_LINE_2x_LOG_INDENT = NEW_LINE_LOG_INDENT + LOG_INDENT;
  public static final String NEW_LINE_3x_LOG_INDENT = NEW_LINE_2x_LOG_INDENT + LOG_INDENT;
  public static final String NEW_LINE_4x_LOG_INDENT = NEW_LINE_3x_LOG_INDENT + LOG_INDENT;
    
}
