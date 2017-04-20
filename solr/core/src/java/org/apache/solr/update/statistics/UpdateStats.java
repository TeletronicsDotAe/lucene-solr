package org.apache.solr.update.statistics;

import org.apache.solr.update.statistics.RealtimeGetComponentStats.GetInputDocumentStatsEntries;
import org.apache.solr.update.statistics.StatisticsAndPrimitiveProfilingHandler.CountAndPrimProfStatsEntry;
import org.apache.solr.update.statistics.StatisticsAndPrimitiveProfilingHandler.IntegerId;
import org.apache.solr.update.statistics.StatisticsAndPrimitiveProfilingHandler.StatisticsAndPrimitiveProfiling;
import org.apache.solr.update.statistics.UpdateLogStats.LookupVersionStatsEntries;
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

public final class UpdateStats implements StatisticsAndPrimitiveProfiling {

  private static final Logger log = LoggerFactory.getLogger(UpdateStats.class);
  
  private IntegerId id = new IntegerId();
  
  private final CountAndPrimProfStatsEntry versionAdd = new CountAndPrimProfStatsEntry(id.getNext(), "Insert/update document", null);
  private final CountAndPrimProfStatsEntry synch = new CountAndPrimProfStatsEntry(id.getNext(), "Synchronizing", versionAdd);
  private final CountAndPrimProfStatsEntry getUpdatedDocument = new CountAndPrimProfStatsEntry(id.getNext(), "Get updated document", versionAdd);
  private final GetInputDocumentStatsEntries getInputDocumentStatsEntries = new GetInputDocumentStatsEntries(id, versionAdd);
  private final LookupVersionStatsEntries vinfoUpdateLogLookupVersionStatsEntries = new LookupVersionStatsEntries(id, versionAdd);
  private final CountAndPrimProfStatsEntry doLocalAdd = new CountAndPrimProfStatsEntry(id.getNext(), "Executing update handlers", versionAdd);
  private final CountAndPrimProfStatsEntry directUpdateHandler2AddDoc = new CountAndPrimProfStatsEntry(id.getNext(), "Update handler add document", doLocalAdd);
  private final LookupVersionStatsEntries directUpdateHandler2AddDocUpdateLogLookupVersionStatsEntries = new LookupVersionStatsEntries(id, directUpdateHandler2AddDoc);
  private final CountAndPrimProfStatsEntry directUpdateHandler2AddDocAddAndDelete = new CountAndPrimProfStatsEntry(id.getNext(), "Delete and insert/update document", directUpdateHandler2AddDoc);
  private final CountAndPrimProfStatsEntry directUpdateHandler2AddDocWriterUpdateDocument = new CountAndPrimProfStatsEntry(id.getNext(), "Update document in index", directUpdateHandler2AddDoc);
  private final CountAndPrimProfStatsEntry directUpdateHandler2AddDocWriterDeleteDocument = new CountAndPrimProfStatsEntry(id.getNext(), "Delete document from index", directUpdateHandler2AddDoc);
  private final CountAndPrimProfStatsEntry directUpdateHandler2AddDocWriterAddDocument = new CountAndPrimProfStatsEntry(id.getNext(), "Add document in index", directUpdateHandler2AddDoc);
  private final CountAndPrimProfStatsEntry directUpdateHandler2AddDocUlogAdd = new CountAndPrimProfStatsEntry(id.getNext(), "Add document to update-log", directUpdateHandler2AddDoc);
   
  public UpdateStats() {
    reset();
  }
  
  public final void registerVersionAdd(final long startTimeNanosec) {
    versionAdd.register(startTimeNanosec);
  }

  public final void registerSynch(final long startTimeNanosec) {
    synch.register(startTimeNanosec);
  }
  
  public final void registerGetUpdatedDocument(final long startTimeNanosec) {
    getUpdatedDocument.register(startTimeNanosec);
  }

  public final void registerDoLocalAdd(final long startTimeNanosec) {
    doLocalAdd.register(startTimeNanosec);
  }
  
  public final void registerDirectUpdateHandler2AddDoc(final long startTimeNanosec) {
    directUpdateHandler2AddDoc.register(startTimeNanosec);
  }
  
  public final void registerDirectUpdateHandler2AddDocAddAndDelete(final long startTimeNanosec) {
    directUpdateHandler2AddDocAddAndDelete.register(startTimeNanosec);
  }
  
  public final void registerDirectUpdateHandler2AddDocWriterUpdateDocument(final long startTimeNanosec) {
    directUpdateHandler2AddDocWriterUpdateDocument.register(startTimeNanosec);
  }
  
  public final void registerDirectUpdateHandler2AddDocWriterDeleteDocument(final long startTimeNanosec) {
    directUpdateHandler2AddDocWriterDeleteDocument.register(startTimeNanosec);
  }
  
  public final void registerDirectUpdateHandler2AddDocWriterAddDocument(final long startTimeNanosec) {
    directUpdateHandler2AddDocWriterAddDocument.register(startTimeNanosec);
  }
  
  public final void registerDirectUpdateHandler2AddDocUlogAdd(final long startTimeNanosec) {
    directUpdateHandler2AddDocUlogAdd.register(startTimeNanosec);
  }
  
  public final GetInputDocumentStatsEntries getGetInputDocumentStatsEntries() {
    return getInputDocumentStatsEntries;
  }
  
  public final LookupVersionStatsEntries getVinfoUpdateLogLookupVersionStatsEntries() {
    return vinfoUpdateLogLookupVersionStatsEntries;
  }
  
  public final LookupVersionStatsEntries getDirectUpdateHandler2AddDocUpdateLogLookupVersionStatsEntries() {
    return directUpdateHandler2AddDocUpdateLogLookupVersionStatsEntries;
  }

  public final void reset() {
    versionAdd.reset();
    synch.reset();
    getUpdatedDocument.reset();
    getInputDocumentStatsEntries.reset();
    vinfoUpdateLogLookupVersionStatsEntries.reset();
    doLocalAdd.reset();
    directUpdateHandler2AddDoc.reset();
    directUpdateHandler2AddDocUpdateLogLookupVersionStatsEntries.reset();
    directUpdateHandler2AddDocAddAndDelete.reset();
    directUpdateHandler2AddDocWriterUpdateDocument.reset();
    directUpdateHandler2AddDocWriterDeleteDocument.reset();
    directUpdateHandler2AddDocWriterAddDocument.reset();
    directUpdateHandler2AddDocUlogAdd.reset();
  }
  
  @Override
  public final String toString() {
    StringBuilder sb = new StringBuilder();
    sb
      .append(UpdateStats.class.getSimpleName()).append(":")
      .append(StatisticsAndPrimitiveProfilingHandler.NEW_LINE_LOG_INDENT).append(versionAdd)
      .append(StatisticsAndPrimitiveProfilingHandler.NEW_LINE_2x_LOG_INDENT).append(synch)
      .append(StatisticsAndPrimitiveProfilingHandler.NEW_LINE_2x_LOG_INDENT).append(getUpdatedDocument);
    getInputDocumentStatsEntries.print(sb, StatisticsAndPrimitiveProfilingHandler.NEW_LINE_3x_LOG_INDENT);
    sb
      .append(StatisticsAndPrimitiveProfilingHandler.NEW_LINE_2x_LOG_INDENT).append("VInfo version lookup");
    vinfoUpdateLogLookupVersionStatsEntries.print(sb, StatisticsAndPrimitiveProfilingHandler.NEW_LINE_3x_LOG_INDENT);
    sb
      .append(StatisticsAndPrimitiveProfilingHandler.NEW_LINE_2x_LOG_INDENT).append(doLocalAdd)
      .append(StatisticsAndPrimitiveProfilingHandler.NEW_LINE_3x_LOG_INDENT).append(directUpdateHandler2AddDoc);
    directUpdateHandler2AddDocUpdateLogLookupVersionStatsEntries.print(sb, StatisticsAndPrimitiveProfilingHandler.NEW_LINE_4x_LOG_INDENT);
    sb
      .append(StatisticsAndPrimitiveProfilingHandler.NEW_LINE_4x_LOG_INDENT).append(directUpdateHandler2AddDocAddAndDelete)
      .append(StatisticsAndPrimitiveProfilingHandler.NEW_LINE_4x_LOG_INDENT).append(directUpdateHandler2AddDocWriterUpdateDocument)
      .append(StatisticsAndPrimitiveProfilingHandler.NEW_LINE_4x_LOG_INDENT).append(directUpdateHandler2AddDocWriterDeleteDocument)
      .append(StatisticsAndPrimitiveProfilingHandler.NEW_LINE_4x_LOG_INDENT).append(directUpdateHandler2AddDocWriterAddDocument)
      .append(StatisticsAndPrimitiveProfilingHandler.NEW_LINE_4x_LOG_INDENT).append(directUpdateHandler2AddDocUlogAdd);

    return sb.toString();
  }

  @Override
  public synchronized void log(boolean reset) {
    log.info(toString());
    if (reset) reset();
  }

}
