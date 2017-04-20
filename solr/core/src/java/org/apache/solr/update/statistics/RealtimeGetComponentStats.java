package org.apache.solr.update.statistics;

import org.apache.solr.update.statistics.StatisticsAndPrimitiveProfilingHandler.CountAndPrimProfStatsEntry;
import org.apache.solr.update.statistics.StatisticsAndPrimitiveProfilingHandler.IntegerId;
import org.apache.solr.update.statistics.UpdateLogStats.LookupStatsEntries;


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

public class RealtimeGetComponentStats {
  
  public static final class GetInputDocumentStatsEntries {

    public final CountAndPrimProfStatsEntry total;
    public final LookupStatsEntries lookupStatsEntries;
    public final CountAndPrimProfStatsEntry indexDocFound;
    public final CountAndPrimProfStatsEntry indexDocNotFound;

    public GetInputDocumentStatsEntries(IntegerId id, CountAndPrimProfStatsEntry parent) {
      this.total = new CountAndPrimProfStatsEntry(id.getNext(), "Get document", parent);
      this.lookupStatsEntries = new LookupStatsEntries(id, total);
      this.indexDocFound = new CountAndPrimProfStatsEntry(id.getNext(), "Lookup index (doc found)", total);
      this.indexDocNotFound = new CountAndPrimProfStatsEntry(id.getNext(), "Lookup index (no doc found)", total);
    }

    public final void registerTotal(final long startTimeNanosec) {
      total.register(startTimeNanosec);
    }
    
    public final void registerIndexDocFound(final long startTimeNanosec) {
      indexDocFound.register(startTimeNanosec);
    }

    public final void registerIndexDocNotFound(final long startTimeNanosec) {
      indexDocNotFound.register(startTimeNanosec);
    }
    
    public final LookupStatsEntries getLookupStatsEntries() {
      return lookupStatsEntries;
    }

    public void reset() {
      total.reset();
      lookupStatsEntries.reset();
      indexDocFound.reset();
      indexDocNotFound.reset();
    }
    
    public void print(StringBuilder sb, String linePrefix) {
      sb
        .append(linePrefix).append(total);
      lookupStatsEntries.print(sb, linePrefix + StatisticsAndPrimitiveProfilingHandler.LOG_INDENT);
      sb
        .append(linePrefix).append(StatisticsAndPrimitiveProfilingHandler.LOG_INDENT).append(indexDocFound)
        .append(linePrefix).append(StatisticsAndPrimitiveProfilingHandler.LOG_INDENT).append(indexDocNotFound);
    }

  }
  
}
