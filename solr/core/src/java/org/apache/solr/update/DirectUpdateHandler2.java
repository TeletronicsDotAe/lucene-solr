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
package org.apache.solr.update;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.LongAdder;

import com.codahale.metrics.Meter;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.CodecReader;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SlowCodecReaderWrapper;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.function.ValueSource;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.exceptions.WrongUsage;
import org.apache.solr.common.exceptions.update.DocumentAlreadyExists;
import org.apache.solr.common.exceptions.update.DocumentDoesNotExist;
import org.apache.solr.common.exceptions.update.VersionConflict;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.core.SolrConfig.UpdateHandlerInfo;
import org.apache.solr.core.SolrCore;
import org.apache.solr.metrics.SolrMetricManager;
import org.apache.solr.metrics.SolrMetricProducer;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrRequestInfo;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.FunctionRangeQuery;
import org.apache.solr.search.QParser;
import org.apache.solr.search.QueryUtils;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.search.SyntaxError;
import org.apache.solr.search.function.ValueSourceRangeFilter;
import org.apache.solr.update.processor.DistributedUpdateProcessor;
import org.apache.solr.util.RefCounted;
import org.apache.solr.util.TestInjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <code>DirectUpdateHandler2</code> implements an UpdateHandler where documents are added
 * directly to the main Lucene index as opposed to adding to a separate smaller index.
 * <p>
 * TODO: add soft commitWithin support
 */
public class DirectUpdateHandler2 extends UpdateHandler implements SolrCoreState.IndexWriterCloser, SolrMetricProducer {
  protected final SolrCoreState solrCoreState;

  // stats
  LongAdder addCommands = new LongAdder();
  Meter addCommandsCumulative;
  LongAdder deleteByIdCommands= new LongAdder();
  Meter deleteByIdCommandsCumulative;
  LongAdder deleteByQueryCommands = new LongAdder();
  Meter deleteByQueryCommandsCumulative;
  Meter expungeDeleteCommands;
  Meter mergeIndexesCommands;
  Meter commitCommands;
  Meter splitCommands;
  Meter optimizeCommands;
  Meter rollbackCommands;
  LongAdder numDocsPending = new LongAdder();
  LongAdder numErrors = new LongAdder();
  Meter numErrorsCumulative;

  // tracks when auto-commit should occur
  protected final CommitTracker commitTracker;
  protected final CommitTracker softCommitTracker;
  protected final UpdateSemanticsMode configedSemanticsMode;

  protected boolean commitWithinSoftCommit;

  protected boolean indexWriterCloseWaitsForMerges;

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  
  public DirectUpdateHandler2(SolrCore core) throws SolrServerException {
    this(core, null);
  }
  
  public DirectUpdateHandler2(SolrCore core, UpdateHandler updateHandler) throws SolrServerException {
    super(core, (updateHandler != null)?updateHandler.getUpdateLog():null);

    solrCoreState = core.getSolrCoreState();
    
    UpdateHandlerInfo updateHandlerInfo = core.getSolrConfig()
        .getUpdateHandlerInfo();
    int docsUpperBound = updateHandlerInfo.autoCommmitMaxDocs; // getInt("updateHandler/autoCommit/maxDocs", -1);
    int timeUpperBound = updateHandlerInfo.autoCommmitMaxTime; // getInt("updateHandler/autoCommit/maxTime", -1);
    commitTracker = new CommitTracker("Hard", core, docsUpperBound, timeUpperBound, updateHandlerInfo.openSearcher, false);
    
    int softCommitDocsUpperBound = updateHandlerInfo.autoSoftCommmitMaxDocs; // getInt("updateHandler/autoSoftCommit/maxDocs", -1);
    int softCommitTimeUpperBound = updateHandlerInfo.autoSoftCommmitMaxTime; // getInt("updateHandler/autoSoftCommit/maxTime", -1);
    softCommitTracker = new CommitTracker("Soft", core, softCommitDocsUpperBound, softCommitTimeUpperBound, (updateHandler != null)?updateHandlerInfo.openSearcher:true, true);
    
    commitWithinSoftCommit = updateHandlerInfo.commitWithinSoftCommit;
    indexWriterCloseWaitsForMerges = updateHandlerInfo.indexWriterCloseWaitsForMerges;

    UpdateSemanticsMode semanticsModeToUse;
    semanticsModeToUse = UpdateSemanticsMode.fromString(updateHandlerInfo.semanticsMode);
    if (semanticsModeToUse == null) {
      log.info("Semantics-mode not explitictly set. Using default");
      semanticsModeToUse = UpdateSemanticsMode.getDefault();
    }
    configedSemanticsMode = semanticsModeToUse;
    log.info("Using semantics-mode: " + configedSemanticsMode.toString());

    if (updateHandler != null) {
    UpdateLog existingLog = updateHandler.getUpdateLog();
    if (this.ulog != null && this.ulog == existingLog) {
      // If we are reusing the existing update log, inform the log that its update handler has changed.
      // We do this as late as possible.
      this.ulog.init(this, core);
    }
    }

    UpdateSemanticsMode.RuleAndReason rar;
    if ((rar = configedSemanticsMode.requireUniqueKeyFieldInSchema()).ruleEnforced && idField == null) throw new SolrServerException(rar.reason);
    if ((rar = configedSemanticsMode.requireUpdateLog()).ruleEnforced && ulog == null) throw new SolrServerException(rar.reason);
  }

  @Override
  public void initializeMetrics(SolrMetricManager manager, String registry, String scope) {
    commitCommands = manager.meter(registry, "commits", getCategory().toString(), scope);
    manager.registerGauge(registry, () -> commitTracker.getCommitCount(), true, "autoCommits", getCategory().toString(), scope);
    manager.registerGauge(registry, () -> softCommitTracker.getCommitCount(), true, "softAutoCommits", getCategory().toString(), scope);
    optimizeCommands = manager.meter(registry, "optimizes", getCategory().toString(), scope);
    rollbackCommands = manager.meter(registry, "rollbacks", getCategory().toString(), scope);
    splitCommands = manager.meter(registry, "splits", getCategory().toString(), scope);
    mergeIndexesCommands = manager.meter(registry, "merges", getCategory().toString(), scope);
    expungeDeleteCommands = manager.meter(registry, "expungeDeletes", getCategory().toString(), scope);
    manager.registerGauge(registry, () -> numDocsPending.longValue(), true, "docsPending", getCategory().toString(), scope);
    manager.registerGauge(registry, () -> addCommands.longValue(), true, "adds", getCategory().toString(), scope);
    manager.registerGauge(registry, () -> deleteByIdCommands.longValue(), true, "deletesById", getCategory().toString(), scope);
    manager.registerGauge(registry, () -> deleteByQueryCommands.longValue(), true, "deletesByQuery", getCategory().toString(), scope);
    manager.registerGauge(registry, () -> numErrors.longValue(), true, "errors", getCategory().toString(), scope);

    addCommandsCumulative = manager.meter(registry, "cumulativeAdds", getCategory().toString(), scope);
    deleteByIdCommandsCumulative = manager.meter(registry, "cumulativeDeletesById", getCategory().toString(), scope);
    deleteByQueryCommandsCumulative = manager.meter(registry, "cumulativeDeletesByQuery", getCategory().toString(), scope);
    numErrorsCumulative = manager.meter(registry, "cumulativeErrors", getCategory().toString(), scope);
  }

  private void deleteAll() throws IOException {
    log.info(core.getLogId() + "REMOVING ALL DOCUMENTS FROM INDEX");
    RefCounted<IndexWriter> iw = solrCoreState.getIndexWriter(core);
    try {
      iw.get().deleteAll();
    } finally {
      iw.decref();
    }
  }

  protected void rollbackWriter() throws IOException {
    numDocsPending.reset();
    solrCoreState.rollbackIndexWriter(core);
    
  }

  @Override
  public int addDoc(AddUpdateCommand cmd) throws IOException {
    long startTimeNanosecs = System.nanoTime();
    try {
      return addDoc0(cmd);
    } catch (SolrException e) {
      throw e;
    } catch (IllegalArgumentException iae) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
          String.format(Locale.ROOT, "Exception writing document id %s to the index; possible analysis error: "
              + iae.getMessage()
              + (iae.getCause() instanceof BytesRefHash.MaxBytesLengthExceededException ?
              ". Perhaps the document has an indexed string field (solr.StrField) which is too large" : ""),
              cmd.getPrintableId()), iae);
    } catch (RuntimeException t) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
          String.format(Locale.ROOT, "Exception writing document id %s to the index; possible analysis error.",
          cmd.getPrintableId()), t);
    } finally {
      DistributedUpdateProcessor.updateStats.registerDirectUpdateHandler2AddDoc(startTimeNanosecs);
    }
  }

  /**
   * This is the implementation of {@link #addDoc(AddUpdateCommand)}. It is factored out to allow an exception
   * handler to decorate RuntimeExceptions with information about the document being handled.
   * @param cmd the command.
   * @return the count.
   */
  private int addDoc0(AddUpdateCommand cmd) throws IOException {
    UpdateSemanticsMode semanticsMode = getSemanticsMode(cmd);

    if (semanticsMode.isClassicUpdate(cmd)) {
      // if there is no uniqueKey field, don't overwrite
      if( idField == null ) {
        cmd.classicOverwrite = false;
      }
    }

    SchemaField versionField = cmd.getReq().getSchema().getFieldOrNull(VersionInfo.VERSION_FIELD);
    BytesRef indexedId = cmd.getIndexedId();
    String id = cmd.getPrintableId(null);
    UpdateSemanticsMode.RuleAndReason rar;
    if ((rar = semanticsMode.requireUniqueKeyFieldInSchemaAndDoc(cmd)).ruleEnforced && (idField == null || id == null)) throw new WrongUsage(ErrorCode.UNPROCESSABLE_ENTITY, rar.reason);
    if ((rar = semanticsMode.requireVersionFieldInSchema(cmd)).ruleEnforced && versionField == null) throw new WrongUsage(ErrorCode.UNPROCESSABLE_ENTITY, rar.reason);
    if ((rar = semanticsMode.requireUpdateLog(cmd)).ruleEnforced && ulog == null) throw new WrongUsage(ErrorCode.UNPROCESSABLE_ENTITY, rar.reason);

    int rc = -1;

    addCommands.increment();
    addCommandsCumulative.mark();

    boolean getAndCheckAgainstExisting = semanticsMode.needToGetAndCheckAgainstExistingDocument(cmd) && cmd.isLeaderLogic();

    if (getAndCheckAgainstExisting) {
      // here we do not need latest version originating from delete, because this
      // is not used for reorder check
      Long currentVersion = ulog.lookupVersion(indexedId, DistributedUpdateProcessor.updateStats.getDirectUpdateHandler2AddDocUpdateLogLookupVersionStatsEntries());
      if (currentVersion == null) currentVersion = -1L;

      // TODO actually by currentVersion < 0 we do not know that it does not exist. It may exist but have no version field
      if (currentVersion < 0 && (rar = semanticsMode.requireExistingDocument(cmd)).ruleEnforced) throw new DocumentDoesNotExist(ErrorCode.CONFLICT, rar.reason);
      if (currentVersion >= 0) {
        if ((rar = semanticsMode.requireNoExistingDocument(cmd)).ruleEnforced) throw new DocumentAlreadyExists(ErrorCode.CONFLICT, rar.reason);
        if (semanticsMode.requireVersionCheck(cmd) && currentVersion != cmd.getRequestVersion()) {
          VersionConflict versionConflict = new VersionConflict(ErrorCode.CONFLICT, "Attempt to update document with uniqueKey " + id + " failed. Version in document to be updated " + cmd.getRequestVersion() + " does not match current version " + currentVersion);
          versionConflict.setCurrentVersion(currentVersion);
          throw versionConflict;
        }
      }
    }
    try {
      if (semanticsMode.needToDeleteOldVersionOfDocument(cmd)) {
        // Check for delete by query commands newer (i.e. reordered). This
        // should always be null on a leader
        List<UpdateLog.DBQ> deletesAfter = null;
        if (ulog != null && cmd.version > 0) {
          deletesAfter = ulog.getDBQNewer(cmd.version);
        }

        if (deletesAfter != null) {
          long startTimeAddAndDeleteNanosecs = System.nanoTime();
          addAndDelete(cmd, deletesAfter);
          DistributedUpdateProcessor.updateStats.registerDirectUpdateHandler2AddDocAddAndDelete(startTimeAddAndDeleteNanosecs);
        } else {
          doNormalUpdate(cmd);
        }
      } else {
        allowDuplicateUpdate(cmd);
      }

      if ((cmd.getFlags() & UpdateCommand.IGNORE_AUTOCOMMIT) == 0) {
        if (commitWithinSoftCommit) {
          commitTracker.addedDocument(-1);
          softCommitTracker.addedDocument(cmd.commitWithin);
        } else {
          softCommitTracker.addedDocument(-1);
          commitTracker.addedDocument(cmd.commitWithin);
        }
      }

      rc = 1;
    } finally {
      if (rc != 1) {
        numErrors.increment();
        numErrorsCumulative.mark();
      } else {
        numDocsPending.increment();
      }
    }

    return rc;
  }

  private void allowDuplicateUpdate(AddUpdateCommand cmd) throws IOException {
    long startTimeWriterAddDocNanosecs = System.nanoTime();
    RefCounted<IndexWriter> iw = solrCoreState.getIndexWriter(core);
    try {
      IndexWriter writer = iw.get();

      if (cmd.isBlock()) {
        writer.addDocuments(cmd);
      } else {
        writer.addDocument(cmd.getLuceneDocument());
      }
      DistributedUpdateProcessor.updateStats.registerDirectUpdateHandler2AddDocWriterAddDocument(startTimeWriterAddDocNanosecs);
      if (ulog != null) {
        long startTimeULogAddNanosecs = System.nanoTime();
        ulog.add(cmd);
        DistributedUpdateProcessor.updateStats.registerDirectUpdateHandler2AddDocUlogAdd(startTimeULogAddNanosecs);
      }

    } finally {
      iw.decref();
    }

  }

  private void doNormalUpdate(AddUpdateCommand cmd) throws IOException {
    Term updateTerm;
    Term idTerm = getIdTerm(cmd);
    boolean del = false;
    if (cmd.updateTerm == null) {
      updateTerm = idTerm;
    } else {
      // this is only used by the dedup update processor
      del = true;
      updateTerm = cmd.updateTerm;
    }

    RefCounted<IndexWriter> iw = solrCoreState.getIndexWriter(core);
    try {
      IndexWriter writer = iw.get();

      updateDocOrDocValues(cmd, writer, updateTerm);

      if (del) { // ensure id remains unique
        BooleanQuery.Builder bq = new BooleanQuery.Builder();
        bq.add(new BooleanClause(new TermQuery(updateTerm),
            Occur.MUST_NOT));
        bq.add(new BooleanClause(new TermQuery(idTerm), Occur.MUST));
        long startTimeWriterDeleteDocNanosecs = System.nanoTime();
        writer.deleteDocuments(new DeleteByQueryWrapper(bq.build(), core.getLatestSchema()));
        DistributedUpdateProcessor.updateStats.registerDirectUpdateHandler2AddDocWriterDeleteDocument(startTimeWriterDeleteDocNanosecs);
      }


      // Add to the transaction log *after* successfully adding to the
      // index, if there was no error.
      // This ordering ensures that if we log it, it's definitely been
      // added to the the index.
      // This also ensures that if a commit sneaks in-between, that we
      // know everything in a particular
      // log version was definitely committed.
      if (ulog != null) {
        long startTimeULogAddNanosecs = System.nanoTime();
        ulog.add(cmd);
        DistributedUpdateProcessor.updateStats.registerDirectUpdateHandler2AddDocUlogAdd(startTimeULogAddNanosecs);
      }

    } finally {
      iw.decref();
    }



  }

  private void addAndDelete(AddUpdateCommand cmd, List<UpdateLog.DBQ> deletesAfter) throws IOException {

    log.info("Reordered DBQs detected.  Update=" + cmd + " DBQs="
        + deletesAfter);
    List<Query> dbqList = new ArrayList<>(deletesAfter.size());
    for (UpdateLog.DBQ dbq : deletesAfter) {
      try {
        DeleteUpdateCommand tmpDel = new DeleteUpdateCommand(cmd.req);
        tmpDel.query = dbq.q;
        tmpDel.version = -dbq.version;
        dbqList.add(getQuery(tmpDel));
      } catch (Exception e) {
        log.error("Exception parsing reordered query : " + dbq, e);
      }
    }

    Document luceneDocument = cmd.getLuceneDocument();
    Term idTerm = getIdTerm(cmd);

    RefCounted<IndexWriter> iw = solrCoreState.getIndexWriter(core);
    try {
      IndexWriter writer = iw.get();

      // see comment in deleteByQuery
      synchronized (solrCoreState.getUpdateLock()) {
        updateDocOrDocValues(cmd, writer, idTerm);

        if (cmd.isInPlaceUpdate() && ulog != null) {
          ulog.openRealtimeSearcher(); // This is needed due to LUCENE-7344.
        }
        for (Query q : dbqList) {
          writer.deleteDocuments(new DeleteByQueryWrapper(q, core.getLatestSchema()));
        }
        if (ulog != null) ulog.add(cmd, true); // this needs to be protected by update lock
      }
    } finally {
      iw.decref();
    }

  }

  private Term getIdTerm(AddUpdateCommand cmd) {
    return new Term(cmd.isBlock() ? "_root_" : idField.getName(), cmd.getIndexedId());
  }

  private void updateDeleteTrackers(DeleteUpdateCommand cmd) {
    if ((cmd.getFlags() & UpdateCommand.IGNORE_AUTOCOMMIT) == 0) {
      if (commitWithinSoftCommit) {
        softCommitTracker.deletedDocument(cmd.commitWithin);
      } else {
        commitTracker.deletedDocument(cmd.commitWithin);
      }
      
      if (commitTracker.getTimeUpperBound() > 0) {
        commitTracker.scheduleCommitWithin(commitTracker.getTimeUpperBound());
      }
      
      if (softCommitTracker.getTimeUpperBound() > 0) {
        softCommitTracker.scheduleCommitWithin(softCommitTracker
            .getTimeUpperBound());
      }
    }
  }

  // we don't return the number of docs deleted because it's not always possible to quickly know that info.
  @Override
  public void delete(DeleteUpdateCommand cmd) throws IOException {
    UpdateSemanticsMode semanticsMode = getSemanticsMode(cmd);

    SchemaField versionField = cmd.getReq().getSchema().getFieldOrNull(VersionInfo.VERSION_FIELD);
    BytesRef indexedId = cmd.getIndexedId();
    String id = cmd.getId();
    UpdateSemanticsMode.RuleAndReason rar;
    if ((rar = semanticsMode.requireUniqueKeyFieldInSchemaAndDoc(cmd)).ruleEnforced && (idField == null || id == null)) throw new WrongUsage(ErrorCode.UNPROCESSABLE_ENTITY, rar.reason);
    if ((rar = semanticsMode.requireVersionFieldInSchema(cmd)).ruleEnforced && versionField == null) throw new WrongUsage(ErrorCode.UNPROCESSABLE_ENTITY, rar.reason);
    if ((rar = semanticsMode.requireUpdateLog(cmd)).ruleEnforced && ulog == null) throw new WrongUsage(ErrorCode.UNPROCESSABLE_ENTITY, rar.reason);

    boolean getAndCheckAgainstExisting = semanticsMode.needToGetAndCheckAgainstExistingDocument(cmd) && cmd.isLeaderLogic();

    if (getAndCheckAgainstExisting) {
      // here we do not need latest version originating from delete, because this
      // is not used for reorder check
      Long currentVersion = ulog.lookupVersion(indexedId, null);
      if (currentVersion == null) currentVersion = -1L;

      if (currentVersion < 0 && (rar = semanticsMode.requireExistingDocument(cmd)).ruleEnforced) throw new DocumentDoesNotExist(ErrorCode.CONFLICT, rar.reason);
      if (currentVersion >= 0) {
        if ((rar = semanticsMode.requireNoExistingDocument(cmd)).ruleEnforced) throw new DocumentAlreadyExists(ErrorCode.CONFLICT, rar.reason);
        if (semanticsMode.requireVersionCheck(cmd) && currentVersion != cmd.getRequestVersion()) {
          VersionConflict versionConflict = new VersionConflict(ErrorCode.CONFLICT, "Attempt to update document with uniqueKey " + id + " failed. Version in document to be updated " + cmd.getRequestVersion() + " does not match current version " + currentVersion);
          versionConflict.setCurrentVersion(currentVersion);
          throw versionConflict;
        }
      }
    }

    deleteByIdCommands.increment();
    deleteByIdCommandsCumulative.mark();

    Term deleteTerm = new Term(idField.getName(), cmd.getIndexedId());
    // SolrCore.verbose("deleteDocuments",deleteTerm,writer);
    RefCounted<IndexWriter> iw = solrCoreState.getIndexWriter(core);
    try {
      iw.get().deleteDocuments(deleteTerm);
    } finally {
      iw.decref();
    }
    // SolrCore.verbose("deleteDocuments",deleteTerm,"DONE");

    if (ulog != null) ulog.delete(cmd);

    updateDeleteTrackers(cmd);
  }


  public void clearIndex() throws IOException {
    deleteAll();
    if (ulog != null) {
      ulog.deleteAll();
    }
  }


  private Query getQuery(DeleteUpdateCommand cmd) {
    Query q;
    try {
      // move this higher in the stack?
      QParser parser = QParser.getParser(cmd.getQuery(), cmd.req);
      q = parser.getQuery();
      q = QueryUtils.makeQueryable(q);

      // Make sure not to delete newer versions
      if (ulog != null && cmd.getVersion() != 0 && cmd.getVersion() != -Long.MAX_VALUE) {
        BooleanQuery.Builder bq = new BooleanQuery.Builder();
        bq.add(q, Occur.MUST);
        SchemaField sf = ulog.getVersionInfo().getVersionField();
        ValueSource vs = sf.getType().getValueSource(sf, null);
        ValueSourceRangeFilter filt = new ValueSourceRangeFilter(vs, Long.toString(Math.abs(cmd.getVersion())), null, true, true);
        FunctionRangeQuery range = new FunctionRangeQuery(filt);
        bq.add(range, Occur.MUST_NOT);  // formulated in the "MUST_NOT" sense so we can delete docs w/o a version (some tests depend on this...)
        q = bq.build();
      }

      return q;

    } catch (SyntaxError e) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, e);
    }
  }


  // we don't return the number of docs deleted because it's not always possible to quickly know that info.
  @Override
  public void deleteByQuery(DeleteUpdateCommand cmd) throws IOException {
    deleteByQueryCommands.increment();
    deleteByQueryCommandsCumulative.mark();
    boolean madeIt=false;
    try {
      Query q = getQuery(cmd);
      
      boolean delAll = MatchAllDocsQuery.class == q.getClass();

      // currently for testing purposes.  Do a delete of complete index w/o worrying about versions, don't log, clean up most state in update log, etc
      if (delAll && cmd.getVersion() == -Long.MAX_VALUE) {
        synchronized (solrCoreState.getUpdateLock()) {
          deleteAll();
          ulog.deleteAll();
          return;
        }
      }

      //
      // synchronized to prevent deleteByQuery from running during the "open new searcher"
      // part of a commit.  DBQ needs to signal that a fresh reader will be needed for
      // a realtime view of the index.  When a new searcher is opened after a DBQ, that
      // flag can be cleared.  If those thing happen concurrently, it's not thread safe.
      // Also, ulog.deleteByQuery clears caches and is thus not safe to be called between
      // preSoftCommit/postSoftCommit and thus we use the updateLock to prevent this (just
      // as we use around ulog.preCommit... also see comments in ulog.postSoftCommit)
      //
      synchronized (solrCoreState.getUpdateLock()) {

        // We are reopening a searcher before applying the deletes to overcome LUCENE-7344.
        // Once LUCENE-7344 is resolved, we can consider removing this.
        if (ulog != null) ulog.openRealtimeSearcher();

        if (delAll) {
          deleteAll();
        } else {
          RefCounted<IndexWriter> iw = solrCoreState.getIndexWriter(core);
          try {
            iw.get().deleteDocuments(new DeleteByQueryWrapper(q, core.getLatestSchema()));
          } finally {
            iw.decref();
          }
        }

        if (ulog != null) ulog.deleteByQuery(cmd);  // this needs to be protected by the update lock
      }

      madeIt = true;

      updateDeleteTrackers(cmd);

    } finally {
      if (!madeIt) {
        numErrors.increment();
        numErrorsCumulative.mark();
      }
    }
  }


  @Override
  public int mergeIndexes(MergeIndexesCommand cmd) throws IOException {
    mergeIndexesCommands.mark();
    int rc;

    log.debug("start " + cmd);
    
    List<DirectoryReader> readers = cmd.readers;
    if (readers != null && readers.size() > 0) {
      List<CodecReader> mergeReaders = new ArrayList<>();
      for (DirectoryReader reader : readers) {
        for (LeafReaderContext leaf : reader.leaves()) {
          mergeReaders.add(SlowCodecReaderWrapper.wrap(leaf.reader()));
        }
      }
      RefCounted<IndexWriter> iw = solrCoreState.getIndexWriter(core);
      try {
        iw.get().addIndexes(mergeReaders.toArray(new CodecReader[mergeReaders.size()]));
      } finally {
        iw.decref();
      }
      rc = 1;
    } else {
      rc = 0;
    }
    log.debug("end_mergeIndexes");

    // TODO: consider soft commit issues
    if (rc == 1 && commitTracker.getTimeUpperBound() > 0) {
      commitTracker.scheduleCommitWithin(commitTracker.getTimeUpperBound());
    } else if (rc == 1 && softCommitTracker.getTimeUpperBound() > 0) {
      softCommitTracker.scheduleCommitWithin(softCommitTracker.getTimeUpperBound());
    }

    return rc;
  }
  
  public void prepareCommit(CommitUpdateCommand cmd) throws IOException {

    boolean error=true;

    try {
      log.debug("start "+cmd);
      RefCounted<IndexWriter> iw = solrCoreState.getIndexWriter(core);
      try {
        SolrIndexWriter.setCommitData(iw.get());
        iw.get().prepareCommit();
      } finally {
        iw.decref();
      }

      log.debug("end_prepareCommit");

      error=false;
    }
    finally {
      if (error) {
        numErrors.increment();
        numErrorsCumulative.mark();
      }
    }
  }

  @Override
  public void commit(CommitUpdateCommand cmd) throws IOException {
    if (cmd.prepareCommit) {
      prepareCommit(cmd);
      return;
    }

    if (cmd.optimize) {
      optimizeCommands.mark();
    } else {
      commitCommands.mark();
      if (cmd.expungeDeletes) expungeDeleteCommands.mark();
    }

    Future[] waitSearcher = null;
    if (cmd.waitSearcher) {
      waitSearcher = new Future[1];
    }

    boolean error=true;
    try {
      // only allow one hard commit to proceed at once
      if (!cmd.softCommit) {
        solrCoreState.getCommitLock().lock();
      }

      log.debug("start "+cmd);

      // We must cancel pending commits *before* we actually execute the commit.

      if (cmd.openSearcher) {
        // we can cancel any pending soft commits if this commit will open a new searcher
        softCommitTracker.cancelPendingCommit();
      }
      if (!cmd.softCommit && (cmd.openSearcher || !commitTracker.getOpenSearcher())) {
        // cancel a pending hard commit if this commit is of equal or greater "strength"...
        // If the autoCommit has openSearcher=true, then this commit must have openSearcher=true
        // to cancel.
         commitTracker.cancelPendingCommit();
      }

      RefCounted<IndexWriter> iw = solrCoreState.getIndexWriter(core);
      try {
        IndexWriter writer = iw.get();
        if (cmd.optimize) {
          if (cmd.maxOptimizeSegments == 1) {
            log.warn("Starting optimize... Reading and rewriting the entire index! Use with care.");
          } else {
            log.warn("Starting optimize... Reading and rewriting a potentially large percent of the entire index, reducing to " + cmd.maxOptimizeSegments + " segments");
          }
          writer.forceMerge(cmd.maxOptimizeSegments);
        } else if (cmd.expungeDeletes) {
          log.warn("Starting expungeDeletes... Reading and rewriting segments with enough deletes, potentially the entire index");
          writer.forceMergeDeletes();
        }
        
        if (!cmd.softCommit) {
          synchronized (solrCoreState.getUpdateLock()) { // sync is currently needed to prevent preCommit
                                // from being called between preSoft and
                                // postSoft... see postSoft comments.
            if (ulog != null) ulog.preCommit(cmd);
          }
          
          // SolrCore.verbose("writer.commit() start writer=",writer);

          if (writer.hasUncommittedChanges()) {
            SolrIndexWriter.setCommitData(writer);
            writer.commit();
          } else {
            log.debug("No uncommitted changes. Skipping IW.commit.");
          }

          // SolrCore.verbose("writer.commit() end");
          numDocsPending.reset();
          callPostCommitCallbacks();
        }
      } finally {
        iw.decref();
      }


      if (cmd.optimize) {
        callPostOptimizeCallbacks();
      }


      if (cmd.softCommit) {
        // ulog.preSoftCommit();
        synchronized (solrCoreState.getUpdateLock()) {
          if (ulog != null) ulog.preSoftCommit(cmd);
          core.getSearcher(true, false, waitSearcher, true);
          if (ulog != null) ulog.postSoftCommit(cmd);
        }
        callPostSoftCommitCallbacks();
      } else {
        synchronized (solrCoreState.getUpdateLock()) {
          if (ulog != null) ulog.preSoftCommit(cmd);
          if (cmd.openSearcher) {
            core.getSearcher(true, false, waitSearcher);
          } else {
            // force open a new realtime searcher so realtime-get and versioning code can see the latest
            RefCounted<SolrIndexSearcher> searchHolder = core.openNewSearcher(true, true);
            searchHolder.decref();
          }
          if (ulog != null) ulog.postSoftCommit(cmd);
        }
        if (ulog != null) ulog.postCommit(cmd); // postCommit currently means new searcher has
                              // also been opened
      }

      // reset commit tracking

      if (cmd.softCommit) {
        softCommitTracker.didCommit();
      } else {
        commitTracker.didCommit();
      }
      
      log.debug("end_commit_flush");

      error=false;
    }
    finally {
      if (!cmd.softCommit) {
        solrCoreState.getCommitLock().unlock();
      }

      addCommands.reset();
      deleteByIdCommands.reset();
      deleteByQueryCommands.reset();
      if (error) {
        numErrors.increment();
        numErrorsCumulative.mark();
      }
    }

    // if we are supposed to wait for the searcher to be registered, then we should do it
    // outside any synchronized block so that other update operations can proceed.
    if (waitSearcher!=null && waitSearcher[0] != null) {
       try {
        waitSearcher[0].get();
      } catch (InterruptedException | ExecutionException e) {
        SolrException.log(log,e);
      }
    }
  }

  @Override
  public void newIndexWriter(boolean rollback) throws IOException {
    solrCoreState.newIndexWriter(core, rollback);
  }
  
  /**
   * @since Solr 1.4
   */
  @Override
  public void rollback(RollbackUpdateCommand cmd) throws IOException {
    if (core.getCoreDescriptor().getCoreContainer().isZooKeeperAware()) {
      throw new UnsupportedOperationException("Rollback is currently not supported in SolrCloud mode. (SOLR-4895)");
    }

    rollbackCommands.mark();

    boolean error=true;

    try {
      log.debug("start "+cmd);

      rollbackWriter();

      //callPostRollbackCallbacks();

      // reset commit tracking
      commitTracker.didRollback();
      softCommitTracker.didRollback();
      
      log.debug("end_rollback");

      error=false;
    }
    finally {
      addCommandsCumulative.mark(-addCommands.sumThenReset());
      deleteByIdCommandsCumulative.mark(-deleteByIdCommands.sumThenReset());
      deleteByQueryCommandsCumulative.mark(-deleteByQueryCommands.sumThenReset());
      if (error) {
        numErrors.increment();
        numErrorsCumulative.mark();
      }
    }
  }

  @Override
  public UpdateLog getUpdateLog() {
    return ulog;
  }

  @Override
  public boolean requireUniqueKeyFieldInDocument(AddUpdateCommand cmd) {
  	return getSemanticsMode(cmd).requireUniqueKeyFieldInSchemaAndDoc(cmd).ruleEnforced;
  }

  @Override
  public void close() throws IOException {
    log.debug("closing " + this);
    
    commitTracker.close();
    softCommitTracker.close();

    numDocsPending.reset();
  }


  public static boolean commitOnClose = true;  // TODO: make this a real config option or move it to TestInjection

  // IndexWriterCloser interface method - called from solrCoreState.decref(this)
  @Override
  public void closeWriter(IndexWriter writer) throws IOException {

    assert TestInjection.injectNonGracefullClose(core.getCoreDescriptor().getCoreContainer());
    
    boolean clearRequestInfo = false;
    solrCoreState.getCommitLock().lock();
    try {
      SolrQueryRequest req = new LocalSolrQueryRequest(core, new ModifiableSolrParams());
      SolrQueryResponse rsp = new SolrQueryResponse();
      if (SolrRequestInfo.getRequestInfo() == null) {
        clearRequestInfo = true;
        SolrRequestInfo.setRequestInfo(new SolrRequestInfo(req, rsp));  // important for debugging
      }


      if (!commitOnClose) {
        if (writer != null) {
          writer.rollback();
        }

        // we shouldn't close the transaction logs either, but leaving them open
        // means we can't delete them on windows (needed for tests)
        if (ulog != null) ulog.close(false);

        return;
      }

      // do a commit before we quit?     
      boolean tryToCommit = writer != null && ulog != null && ulog.hasUncommittedChanges() && ulog.getState() == UpdateLog.State.ACTIVE;

      try {
        if (tryToCommit) {
          log.info("Committing on IndexWriter close.");
          CommitUpdateCommand cmd = new CommitUpdateCommand(req, false);
          cmd.openSearcher = false;
          cmd.waitSearcher = false;
          cmd.softCommit = false;

          // TODO: keep other commit callbacks from being called?
         //  this.commit(cmd);        // too many test failures using this method... is it because of callbacks?

          synchronized (solrCoreState.getUpdateLock()) {
            ulog.preCommit(cmd);
          }

          // todo: refactor this shared code (or figure out why a real CommitUpdateCommand can't be used)
          SolrIndexWriter.setCommitData(writer);
          writer.commit();

          synchronized (solrCoreState.getUpdateLock()) {
            ulog.postCommit(cmd);
          }
        }
      } catch (Throwable th) {
        log.error("Error in final commit", th);
        if (th instanceof OutOfMemoryError) {
          throw (OutOfMemoryError) th;
        }
      }

      // we went through the normal process to commit, so we don't have to artificially
      // cap any ulog files.
      try {
        if (ulog != null) ulog.close(false);
      }  catch (Throwable th) {
        log.error("Error closing log files", th);
        if (th instanceof OutOfMemoryError) {
          throw (OutOfMemoryError) th;
        }
      }

      if (writer != null) {
        writer.close();
      }

    } finally {
      solrCoreState.getCommitLock().unlock();
      if (clearRequestInfo) SolrRequestInfo.clearRequestInfo();
    }
  }

  @Override
  public void split(SplitIndexCommand cmd) throws IOException {
    commit(new CommitUpdateCommand(cmd.req, false));
    SolrIndexSplitter splitter = new SolrIndexSplitter(cmd);
    splitCommands.mark();
    try {
      splitter.split();
    } catch (IOException e) {
      numErrors.increment();
      numErrorsCumulative.mark();
    }
  }

  /**
   * Calls either {@link IndexWriter#updateDocValues} or {@link IndexWriter#updateDocument} as 
   * needed based on {@link AddUpdateCommand#isInPlaceUpdate}.
   * <p>
   * If the this is an UPDATE_INPLACE cmd, then all fields inclued in 
   * {@link AddUpdateCommand#getLuceneDocument} must either be the uniqueKey field, or be DocValue 
   * only fields.
   * </p>
   *
   * @param cmd - cmd apply to IndexWriter
   * @param writer - IndexWriter to use
   * @param updateTerm - used if this cmd results in calling {@link IndexWriter#updateDocument}
   */
  private void updateDocOrDocValues(AddUpdateCommand cmd, IndexWriter writer, Term updateTerm) throws IOException {
    assert null != cmd;
    final SchemaField uniqueKeyField = cmd.req.getSchema().getUniqueKeyField();
    final String uniqueKeyFieldName = null == uniqueKeyField ? null : uniqueKeyField.getName();

    if (cmd.isInPlaceUpdate()) {
      Document luceneDocument = cmd.getLuceneDocument(true);

      final List<IndexableField> origDocFields = luceneDocument.getFields();
      final List<Field> fieldsToUpdate = new ArrayList<>(origDocFields.size());
      for (IndexableField field : origDocFields) {
        if (! field.name().equals(uniqueKeyFieldName) ) {
          fieldsToUpdate.add((Field)field);
        }
      }
      log.debug("updateDocValues({})", cmd);
      writer.updateDocValues(updateTerm, fieldsToUpdate.toArray(new Field[fieldsToUpdate.size()]));
    } else {
      updateDocument(cmd, writer, updateTerm);
    }
  }

  private void updateDocument(AddUpdateCommand cmd, IndexWriter writer, Term updateTerm) throws IOException {
    long startTimeWriterUpdateDocNanosecs = System.nanoTime();
    if (cmd.isBlock()) {
      log.debug("updateDocuments({})", cmd);
      writer.updateDocuments(updateTerm, cmd);
    } else {
      Document luceneDocument = cmd.getLuceneDocument(false);
      log.debug("updateDocument({})", cmd);
      writer.updateDocument(updateTerm, luceneDocument);
    }
    DistributedUpdateProcessor.updateStats.registerDirectUpdateHandler2AddDocWriterUpdateDocument(startTimeWriterUpdateDocNanosecs);
  }


  /////////////////////////////////////////////////////////////////////
  // SolrInfoMBean stuff: Statistics and Module Info
  /////////////////////////////////////////////////////////////////////

  @Override
  public String getName() {
    return DirectUpdateHandler2.class.getName();
  }

  @Override
  public String getVersion() {
    return SolrCore.version;
  }

  @Override
  public String getDescription() {
    return "Update handler that efficiently directly updates the on-disk main lucene index";
  }

  @Override
  public String getSource() {
    return null;
  }

  @Override
  public URL[] getDocs() {
    return null;
  }

  @Override
  public NamedList getStatistics() {
    NamedList lst = new SimpleOrderedMap();
    lst.add("commits", commitCommands.getCount());
    if (commitTracker.getDocsUpperBound() > 0) {
      lst.add("autocommit maxDocs", commitTracker.getDocsUpperBound());
    }
    if (commitTracker.getTimeUpperBound() > 0) {
      lst.add("autocommit maxTime", "" + commitTracker.getTimeUpperBound() + "ms");
    }
    lst.add("autocommits", commitTracker.getCommitCount());
    if (softCommitTracker.getDocsUpperBound() > 0) {
      lst.add("soft autocommit maxDocs", softCommitTracker.getDocsUpperBound());
    }
    if (softCommitTracker.getTimeUpperBound() > 0) {
      lst.add("soft autocommit maxTime", "" + softCommitTracker.getTimeUpperBound() + "ms");
    }
    lst.add("soft autocommits", softCommitTracker.getCommitCount());
    lst.add("optimizes", optimizeCommands.getCount());
    lst.add("rollbacks", rollbackCommands.getCount());
    lst.add("expungeDeletes", expungeDeleteCommands.getCount());
    lst.add("docsPending", numDocsPending.longValue());
    // pset.size() not synchronized, but it should be fine to access.
    // lst.add("deletesPending", pset.size());
    lst.add("adds", addCommands.longValue());
    lst.add("deletesById", deleteByIdCommands.longValue());
    lst.add("deletesByQuery", deleteByQueryCommands.longValue());
    lst.add("errors", numErrors.longValue());
    lst.add("cumulative_adds", addCommandsCumulative.getCount());
    lst.add("cumulative_deletesById", deleteByIdCommandsCumulative.getCount());
    lst.add("cumulative_deletesByQuery", deleteByQueryCommandsCumulative.getCount());
    lst.add("cumulative_errors", numErrorsCumulative.getCount());
    if (this.ulog != null) {
      lst.add("transaction_logs_total_size", ulog.getTotalLogsSize());
      lst.add("transaction_logs_total_number", ulog.getTotalLogsNumber());
    }
    return lst;
  }

  protected UpdateSemanticsMode getSemanticsMode(UpdateCommand cmd) {
    UpdateSemanticsMode semanticsMode = configedSemanticsMode;
    if (cmd.getSemanticsMode() != null && cmd.getSemanticsMode() != configedSemanticsMode) {
      semanticsMode = cmd.getSemanticsMode();
      UpdateSemanticsMode.RuleAndReason rar;
      if ((rar = configedSemanticsMode.requireUniqueKeyFieldInSchema()).ruleEnforced && idField == null) throw new WrongUsage(ErrorCode.UNPROCESSABLE_ENTITY, rar.reason);
      if ((rar = configedSemanticsMode.requireUpdateLog()).ruleEnforced && ulog == null) throw new WrongUsage(ErrorCode.UNPROCESSABLE_ENTITY, rar.reason);
    }
    return semanticsMode;
  }

  @Override
  public String toString() {
    return "DirectUpdateHandler2" + getStatistics();
  }
  
  @Override
  public SolrCoreState getSolrCoreState() {
    return solrCoreState;
  }

  // allow access for tests
  public CommitTracker getCommitTracker() {
    return commitTracker;
  }

  // allow access for tests
  public CommitTracker getSoftCommitTracker() {
    return softCommitTracker;
  }

}
