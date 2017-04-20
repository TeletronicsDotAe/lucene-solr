package org.apache.solr.update.statistics;

import org.apache.solr.update.statistics.StatisticsAndPrimitiveProfilingHandler.CountAndPrimProfStatsEntry;
import org.apache.solr.update.statistics.StatisticsAndPrimitiveProfilingHandler.IntegerId;


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

public class UpdateLogStats {
  
  public static final class LookupVersionStatsEntries {
    
    public final CountAndPrimProfStatsEntry total;
    public final CountAndPrimProfStatsEntry cache;
    public final CountAndPrimProfStatsEntry updateLog;
    public final CountAndPrimProfStatsEntry indexDocFound;
    public final CountAndPrimProfStatsEntry indexDocNotFound;

    public LookupVersionStatsEntries(IntegerId id, CountAndPrimProfStatsEntry parent) {
      this.total = new CountAndPrimProfStatsEntry(id.getNext(), "Lookup version", parent);
      this.cache = new CountAndPrimProfStatsEntry(id.getNext(), "Lookup version cache", total);
      this.updateLog = new CountAndPrimProfStatsEntry(id.getNext(), "Lookup version update-log", total);
      this.indexDocFound = new CountAndPrimProfStatsEntry(id.getNext(), "Lookup version index (doc found)", total);
      this.indexDocNotFound = new CountAndPrimProfStatsEntry(id.getNext(), "Lookup version index (no doc found)", total);
    }
    
    public final void registerTotal(final long startTimeNanosec) {
      total.register(startTimeNanosec);
    }

    public final void registerCache(final long startTimeNanosec) {
      cache.register(startTimeNanosec);
    }

    public final void registerUpdateLog(final long startTimeNanosec) {
      updateLog.register(startTimeNanosec);
    }

    public final void registerIndexDocFound(final long startTimeNanosec) {
      indexDocFound.register(startTimeNanosec);
    }

    public final void registerIndexDocNotFound(final long startTimeNanosec) {
      indexDocNotFound.register(startTimeNanosec);
    }

    public void reset() {
      total.reset();
      cache.reset();
      updateLog.reset();
      indexDocFound.reset();
      indexDocNotFound.reset();
    }
    
    public void print(StringBuilder sb, String linePrefix) {
      sb
        .append(linePrefix).append(total)
        .append(linePrefix).append(StatisticsAndPrimitiveProfilingHandler.LOG_INDENT).append(cache)
        .append(linePrefix).append(StatisticsAndPrimitiveProfilingHandler.LOG_INDENT).append(updateLog)
        .append(linePrefix).append(StatisticsAndPrimitiveProfilingHandler.LOG_INDENT).append(indexDocFound)
        .append(linePrefix).append(StatisticsAndPrimitiveProfilingHandler.LOG_INDENT).append(indexDocNotFound);
    }
     
  }

  public static final class LookupStatsEntries {
    
    public final CountAndPrimProfStatsEntry total;
    public final CountAndPrimProfStatsEntry cache;
    public final CountAndPrimProfStatsEntry updateLog;

    public LookupStatsEntries(IntegerId id, CountAndPrimProfStatsEntry parent) {
      this.total = new CountAndPrimProfStatsEntry(id.getNext(), "Lookup doc", parent);
      this.cache = new CountAndPrimProfStatsEntry(id.getNext(), "Lookup doc cache", total);
      this.updateLog = new CountAndPrimProfStatsEntry(id.getNext(), "Lookup doc update-log", total);
    }
    
    public LookupStatsEntries(IntegerId id, CountAndPrimProfStatsEntry parent, long levelNanosec, boolean collectAllTimes) {
      this.total = new CountAndPrimProfStatsEntry(id.getNext(), "Lookup doc", parent, levelNanosec, collectAllTimes);
      this.cache = new CountAndPrimProfStatsEntry(id.getNext(), "Lookup doc cache", total, levelNanosec, collectAllTimes);
      this.updateLog = new CountAndPrimProfStatsEntry(id.getNext(), "Lookup doc update-log", total, levelNanosec, collectAllTimes);
    }
    
    public final void registerTotal(final long startTimeNanosec) {
      total.register(startTimeNanosec);
    }

    public final void registerCache(final long startTimeNanosec) {
      cache.register(startTimeNanosec);
    }

    public final void registerUpdateLog(final long startTimeNanosec) {
      updateLog.register(startTimeNanosec);
    }
    
    public final void add(LookupStatsEntries statsToAdd) {
      total.add(statsToAdd.total);
      cache.add(statsToAdd.cache);
      updateLog.add(statsToAdd.updateLog);
    }
    
    public void reset() {
      total.reset();
      cache.reset();
      updateLog.reset();
    }
    
    public void print(StringBuilder sb, String linePrefix) {
      sb
        .append(linePrefix).append(total)
        .append(linePrefix).append(StatisticsAndPrimitiveProfilingHandler.LOG_INDENT).append(cache)
        .append(linePrefix).append(StatisticsAndPrimitiveProfilingHandler.LOG_INDENT).append(updateLog);
    }
    
  }
  
}
