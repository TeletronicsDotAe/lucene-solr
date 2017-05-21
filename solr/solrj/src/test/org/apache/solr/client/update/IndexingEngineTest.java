package org.apache.solr.client.update;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.naming.OperationNotSupportedException;

import junit.framework.Assert;

import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.client.update.IndexingEngineTest.IndexingEngineBatchJob.IndexInstruction;
import org.apache.solr.client.update.IndexingEngineTest.IndexingEngineBatchJob.Merger;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.exceptions.update.DocumentAlreadyExists;
import org.apache.solr.common.exceptions.update.DocumentDoesNotExist;
import org.apache.solr.common.exceptions.update.VersionConflict;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.params.UpdateParams;
import org.apache.solr.update.UpdateSemanticsMode;
import org.apache.solr.util.DefaultSolrThreadFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

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

// WORK IN PROGRESS. NOT USABLE YET!!! Deal with TODOs below and in code and much more
// TODO JavaDoc
// TODO Everything except the @Test methods are the actual implementation
// and have to be extracted to non-test classes
// TODO Make an overview of class and which roles they have. Check that same aspects
// are not handled twice, or that a class handle aspects that ought to be handled by another
// class
// TODO Reconsider nested classes - more/less? static or not? final or not?
// TODO Make it as much a framework as possible in reasonable time. That is, make it possible
// to plug in / override as much of the stuff that you potentially want to override
// TODO Rewrite eDR to use IndexingEngine - then use it for multiparts as well
@Ignore
public class IndexingEngineTest extends SolrTestCaseJ4 {
  
  @BeforeClass
  public static void beforeTests() throws Exception {
    initCore("solrconfig.xml","schema.xml");
  }

  private SolrClient client;
  private ExecutorService executorService;
  private IndexingEngine engine;
  
  @Before
  public void before() throws Exception {
    /*SolrCore core = h.getCoreInc();
    DirectSolrConnection connection = new DirectSolrConnection(core);*/
    client = new HttpSolrClient("http://127.0.0.1:8983/solr");
    executorService = Executors.newFixedThreadPool(10, new DefaultSolrThreadFactory("indexing-engine"));
    engine = new IndexingEngine(client, executorService, 10, false);
  }
  
  @After
  public void after() throws IOException {
    engine.shutdown();
    executorService.shutdown();
    client.close();
  }
  
  public static class IndexingEngine {
    
    // TODO probably only want to support CloudSolrClient (to begin with) because it can do some routing for us.
    // If it or server-side cannot already route the request we need/want to do, maybe we should introduce that feature. E.g. for
    // * Doing only one /get request with ids, that are routed correctly across collections and shards.
    // * Same thing for /getcorrentity
    private final SolrClient client;
    private final ExecutorService executorService;
    
    protected volatile boolean shutdown;
    protected final SynchedBatchJobsList jobs;
    protected final OrchestraterThread orchestraterThread;
    protected final FinishedReceiver finishedReceiver;
    
    public IndexingEngine(SolrClient client, ExecutorService executorService, int maxPendingJobs, boolean classicConsistencyHybridIsGloballyConfigured) {
      this.client = client;
      this.executorService = executorService;
      shutdown = false;
      jobs = new SynchedBatchJobsList(maxPendingJobs);
      orchestraterThread = new OrchestraterThread(classicConsistencyHybridIsGloballyConfigured);
      orchestraterThread.start();
      finishedReceiver = new FinishedReceiver(); 
    }
    
    public void shutdown() {
      shutdown = true;
      jobs.waitForEmpty();
      try {
        orchestraterThread.join();
      } catch (InterruptedException e) {
        // ignore - should not happen
      }
    }
    
    public Future<IndexingEngineBatchJobResult> index(IndexingEngineBatchJob batchJob) throws NoMoreJobsAllowedException, JobTooBigException {
      IndexingEngineBatchJobResult result = new IndexingEngineBatchJobResult();
      IndexingEngineBatchJobAndResultFuture batchJobAndResultFuture = new IndexingEngineBatchJobAndResultFuture(batchJob, new IndexingEngineBatchJobResultFuture(result));
      
      if (shutdown) throw new NoMoreJobsAllowedException();
      if (!jobs.everRoomFor(batchJobAndResultFuture)) throw new JobTooBigException();
      
      jobs.add(batchJobAndResultFuture);

      return batchJobAndResultFuture.resultFuture;
    }
    
    @SuppressWarnings("serial")
    public static final class NoMoreJobsAllowedException extends Exception {};
    @SuppressWarnings("serial")
    public static final class JobTooBigException extends Exception {};
    
    private final class OrchestraterThread extends Thread {
      
      private final boolean classicConsistencyHybridIsGloballyConfigured;
      
      public OrchestraterThread(boolean classicConsistencyHybridIsGloballyConfigured) {
        this.classicConsistencyHybridIsGloballyConfigured = classicConsistencyHybridIsGloballyConfigured;
      }
      
      @Override
      public void run() {
        while (!shutdown) {
          IndexingEngineBatchJobAndResultFuture batchJobAndResultFuture;
          while ((batchJobAndResultFuture = jobs.pull()) != null) {
            // TODO handle get-updated-doc-and-merge jobs/requests also
            //Collection<?> getRequests = batchJobAndResultFuture.job.getGetRequests(classicConsistencyHybridIsGloballyConfigured);
            
            Collection<UpdateRequest> updateRequests = batchJobAndResultFuture.job.getUpdateRequests(classicConsistencyHybridIsGloballyConfigured);
            for (UpdateRequest updateRequest : updateRequests) {
              while (true) {
                try {
                  finishedReceiver.resetFinished();
                  batchJobAndResultFuture.updateResponseFutureToUpdateRequestMap.put(
                      executorService.submit(new UpdateCallable(updateRequest)), 
                      updateRequest);
                  continue;
                } catch (RejectedExecutionException e) {
                  // This is probably due to a limit reached - we better wait for Callables to finish
                  finishedReceiver.waitForFinishedSinceReset();
                }
              }
            }
            
            batchJobAndResultFuture.handleFinished();
            
            if (batchJobAndResultFuture.job.finished) {
              batchJobAndResultFuture.resultFuture.finished();
            } else {
              jobs.add(batchJobAndResultFuture);              
            }
            
          }
          
          // TODO something about waiting for jobs if there are currently none or if they do not produce any requests
        }
      }
      
    }
    
    public static class FinishedReceiver {
      
      private boolean finishedSinceReset = false;
      
      public synchronized void finished(GenericCallable<?> callable) {
        finishedSinceReset = true;
        this.notifyAll();
      }
      
      public synchronized void waitForFinishedSinceReset() {
        try {
          if (!finishedSinceReset) this.wait();
        } catch (InterruptedException e) {
          // ignore - should not happen
        }
      }
      
      public void resetFinished() {
        finishedSinceReset = false;
      }
      
    }
    
    protected abstract class GenericCallable<T> implements Callable<T> {
      
      @Override
      public final T call() throws Exception {
        try {
          return doCall();
        } finally {
          finishedReceiver.finished(this);
        }
      }
      
      protected abstract T doCall() throws Exception;
      
    }
    
    protected final class UpdateCallable extends GenericCallable<UpdateResponse> {
      
      private final UpdateRequest updateRequest;
      
      protected UpdateCallable(UpdateRequest updateRequest) {
        this.updateRequest = updateRequest;
      }
      
      public UpdateResponse doCall() throws SolrServerException, IOException {
        return updateRequest.process(client);
      }
    }
    
  }
  
  public static class IndexingEngineBatchJob {
    
    public static enum IndexInstruction {
      SKIP_IF_EXISTS,
      OVERWRITE_IF_EXISTS,
      INDEX_NO_MATTER_IF_EXISTS,
      OPTIMISTIC_LOCKING;
    }
    
    public static class DocumentStateMachine {
      
      // This have to continue being the original document provided to constructor
      // Merging must produce another document
      private final SolrInputDocument doc;
      private final IndexInstruction indexInstruction;
      private final Merger merger;
      
      private NextStep nextStep;
      private boolean indexInProgress;
      private SolrInputDocument mergedDoc; // A merge between doc and alreadyInSolr
      private int indexFailedCount;
      private int getFailedCount;
      
      public DocumentStateMachine(SolrInputDocument doc, IndexInstruction indexInstruction, Merger merger) {
        this.doc = doc;
        this.indexInstruction = indexInstruction;
        this.merger = merger;
        
        if (indexInstruction == IndexInstruction.OPTIMISTIC_LOCKING) {
          nextStep = NextStep.GET_UPDATED_DOC_AND_MERGE;
        } else {
          nextStep = NextStep.INDEX;
          if (indexInstruction == IndexInstruction.SKIP_IF_EXISTS) {
            doc.setField(SolrInputDocument.VERSION_FIELD, -1);
          } else {
            doc.setField(SolrInputDocument.VERSION_FIELD, 0);
            // TODO support for setting overwrite per document. NOT AS FIELD, we do not want a field called "overwrite" stored. Maybe same way
            // that uniquePartRef is transfered per document in updage-requests
            // Then we would not need overwrite as a field in DefaultDocumentGrouper.Group, because we can mix overwrite and non-overwrite
            // documents in the same request. If all DocumentStateMachines in a request have same overwrite value we still 
            // should set overwrite at req-level, though
            // if (indexInstruction == IndexInstruction.INDEX_NO_MATTER_IF_EXISTS) doc.setField(UpdateParams.OVERWRITE, Boolean.FALSE.toString());
            // DEFAULT: else if (indexInstruction == IndexInstruction.OVERWRITE_IF_EXISTS) doc.setField(UpdateParams.OVERWRITE, Boolean.TRUE.toString()); 
          }
        }
        indexInProgress = false;
        mergedDoc = null;
        indexFailedCount = 0;
        getFailedCount = 0;
      }
      
      public void moveForwardException(Exception e) {
        if (nextStep == NextStep.INDEX) {
          if (e instanceof DocumentAlreadyExists) {
            assert indexInstruction == IndexInstruction.SKIP_IF_EXISTS || indexInstruction == IndexInstruction.OPTIMISTIC_LOCKING;
            
            if (indexInstruction == IndexInstruction.SKIP_IF_EXISTS) nextStep = NextStep.NONE;
            if (indexInstruction == IndexInstruction.OPTIMISTIC_LOCKING) nextStep = NextStep.GET_UPDATED_DOC_AND_MERGE;
          } else if (e instanceof DocumentDoesNotExist) {
            assert indexInstruction == IndexInstruction.OPTIMISTIC_LOCKING;
            
            mergedDoc = null;
            if (indexInstruction == IndexInstruction.OPTIMISTIC_LOCKING) nextStep = NextStep.INDEX;
          } else if (e instanceof VersionConflict) {
            assert indexInstruction == IndexInstruction.OPTIMISTIC_LOCKING;
            
            if (indexInstruction == IndexInstruction.OPTIMISTIC_LOCKING) nextStep = NextStep.GET_UPDATED_DOC_AND_MERGE;
          } else {
            // Count unexpected fails - maybe stop at some point. Currently continue same operation forever
            indexFailedCount++;
          }
          
          indexInProgress = false;
        } else if (nextStep == NextStep.GET_UPDATED_DOC_AND_MERGE) {
          // Count unexpected fails - maybe stop at some point. Currently continue same operation forever
          getFailedCount++;
        }
      }
      
      public void moveForwardIndexSucceeded() {
        assert nextStep == NextStep.INDEX;
        
        nextStep = NextStep.NONE;
        indexInProgress = false;
      }
      
      public void moveForwardUpdatedDoc(SolrDocument alreadyInSolr) {
        assert nextStep == NextStep.GET_UPDATED_DOC_AND_MERGE;
        
        if (nextStep == NextStep.GET_UPDATED_DOC_AND_MERGE) {
          mergedDoc = merger.merge(alreadyInSolr, doc);
          if (alreadyInSolr == null) {
            mergedDoc.setField(SolrInputDocument.VERSION_FIELD, -1);
          } else {
            mergedDoc.setField(SolrInputDocument.VERSION_FIELD, alreadyInSolr.getFieldValue(SolrInputDocument.VERSION_FIELD));
          }
          
          nextStep = NextStep.INDEX;
        }
      }
      
      public boolean readyToIndex() {
        return nextStep == NextStep.INDEX && !indexInProgress;
      }
      
      public SolrInputDocument getDocForIndexing() {
        SolrInputDocument docToBeIndexed = (mergedDoc != null)?mergedDoc:doc;
        indexInProgress = true;

        return docToBeIndexed;
      }
      
      public String getTargetCollection() {
        // TODO calculate correct collection. Unless that is controlled by providing correct SolrClient
        // e.g. correct HttpSolrClient or ClourSolrClient with correctly configured default-collection or ...
        return null;
      }
      
      public boolean finished() {
        return nextStep == NextStep.NONE;
      }

      public static enum NextStep {
        INDEX,
        GET_UPDATED_DOC_AND_MERGE,
        NONE
      }
      
    }
    
    public static interface Merger {
      
      /**
       * Merge alreadyInSolr and doc. Not allowed to modify any of them.
       * 
       * @param alreadyInSolr A document already in Solr found when trying to index doc
       * @param doc The document we tried to index
       * @return A merge between alreadyInSolr and doc. This can only be toBeIndexed object
       *         itself if merge does not need to modify it
       */
      SolrInputDocument merge(SolrDocument alreadyInSolr, SolrInputDocument doc);

    }
    
    public static interface DocumentGrouper<GROUP> {
      
      GROUP getGroup(DocumentStateMachine dsm);
      
      void populateUpdateRequest(GROUP group, UpdateRequest updateRequest); 
      
    }
    
    public static class DefaultDocumentGrouper implements DocumentGrouper<DefaultDocumentGrouper.Group> {
      
      private static class Group {

        private static class OverwriteAndNotGroups {
          Group overwrite;
          Group notOverwrite;
        }
        
        private static Map<String, OverwriteAndNotGroups> instances = new HashMap<String, OverwriteAndNotGroups>();
        
        private static Group getGroup(String collection, boolean overwrite) {
          OverwriteAndNotGroups oang;
          if ((oang = instances.get(collection)) == null) {
            synchronized (instances) {
              oang = new OverwriteAndNotGroups();
              oang.overwrite = new Group(collection, true);
              oang.notOverwrite = new Group(collection, false);
            }
          }
          return (overwrite)?oang.overwrite:oang.notOverwrite;
        }
        
        private final String collection;
        private final boolean overwrite;

        public Group(String collection, boolean overwrite) {
          this.collection = collection;
          this.overwrite = overwrite;
        }
        
      }
      
      public Group getGroup(DocumentStateMachine dsm) {
        return Group.getGroup(dsm.getTargetCollection(), dsm.indexInstruction == IndexInstruction.INDEX_NO_MATTER_IF_EXISTS);
      }
      
      public void populateUpdateRequest(Group group, UpdateRequest updateRequest) {
        updateRequest.setParam(CoreAdminParams.COLLECTION, group.collection);
        updateRequest.setParam(UpdateParams.OVERWRITE, Boolean.toString(group.overwrite));
      }
      
    }
    
    private final Merger merger;
    private final DefaultDocumentGrouper docGrouper;
    private final Map<Object, DocumentStateMachine> idToDocs;
    private boolean finished;
    
    public IndexingEngineBatchJob(Merger merger) {
      this.merger = merger;
      // TODO control from outside?
      docGrouper = new DefaultDocumentGrouper();
      idToDocs = new HashMap<Object, DocumentStateMachine>();
      finished = false;
    }
    
    public void add(SolrInputDocument doc, IndexInstruction indexInstruction) {
      idToDocs.put(doc.getFieldValue("id"), new DocumentStateMachine(doc, indexInstruction, merger));
    }
    
    public Collection<UpdateRequest> getUpdateRequests(boolean classicConsistencyHybridIsGloballyConfigured) {
      Map<DefaultDocumentGrouper.Group, UpdateRequest> collectionToUpdateRequestMap = new HashMap<DefaultDocumentGrouper.Group, UpdateRequest>();
      for (Map.Entry<Object, DocumentStateMachine> idToDoc : idToDocs.entrySet()) {
        DocumentStateMachine dsm = idToDoc.getValue(); 
        if (dsm.readyToIndex()) {
          UpdateRequest updateRequest; 
          DefaultDocumentGrouper.Group group = docGrouper.getGroup(dsm);
          if ((updateRequest = collectionToUpdateRequestMap.get(group)) == null) {
            updateRequest = createBasicUpdateRequest(classicConsistencyHybridIsGloballyConfigured, group);
            collectionToUpdateRequestMap.put(group, updateRequest);
          }
          updateRequest.add(dsm.getDocForIndexing());
        }
      }
      
      return collectionToUpdateRequestMap.values();
    }
    
    protected UpdateRequest createBasicUpdateRequest(boolean classicConsistencyHybridIsGloballyConfigured, DefaultDocumentGrouper.Group group) {
      UpdateRequest updateRequest = new UpdateRequest();
      if (!classicConsistencyHybridIsGloballyConfigured) updateRequest.setParam(UpdateParams.REQ_SEMANTICS_MODE, UpdateSemanticsMode.CLASSIC_CONSISTENCY_HYBRID.toString());
      docGrouper.populateUpdateRequest(group, updateRequest);
      return updateRequest;
    }
    
    public void updateRequestSucceeded(UpdateRequest updateRequest, UpdateResponse updateResponse) {
      for (SolrInputDocument doc : updateRequest.getDocuments()) {
        DocumentStateMachine dsm = idToDocs.get(doc.getFieldValue("id"));
        dsm.moveForwardIndexSucceeded();
      }
      
      updateFinished();
    }
    
    public void updateRequestFailed(UpdateRequest updateRequest, Exception e) {
      //FIXME - change partial errors to use solr's TolerantUpdateProcessor. Maybe just delete this class - should be verified
      // by the other tests in this package
      /*
      if (e instanceof PartialErrors) {
        Map<String, SolrException> partialErrors = ((PartialErrors)e).getSpecializedResponse().getPartialErrors();
        for (SolrInputDocument doc : updateRequest.getDocuments()) {
          DocumentStateMachine dsm = idToDocs.get(doc.getFieldValue("id"));
          if (partialErrors.containsKey(doc.getUniquePartRef())) {
            SolrException docException = partialErrors.get(doc.getUniquePartRef());
            dsm.moveForwardException(docException);
          } else {
            dsm.moveForwardIndexSucceeded();
          }
        }
      }
      */
      // defaulting
      //else {
        for (SolrInputDocument doc : updateRequest.getDocuments()) {
          DocumentStateMachine dsm = idToDocs.get(doc.getFieldValue("id"));
          dsm.moveForwardException(e);
        }
      //}
      
      updateFinished();
    }
    
    private void updateFinished() {
      boolean finished = true;
      for (Map.Entry<Object, DocumentStateMachine> idToDoc : idToDocs.entrySet()) {
        if (!idToDoc.getValue().finished()) {
          finished = false;
          break;
        }
      }
      this.finished = finished;
    }
    
  }
  
  public static class IndexingEngineBatchJobResult {
    
    private final List<SolrInputDocument> alreadyExisted;
    private final Map<SolrInputDocument, Exception> failed;
    private boolean finished;
    
    public IndexingEngineBatchJobResult() {
      alreadyExisted = new ArrayList<SolrInputDocument>();
      failed = new HashMap<SolrInputDocument, Exception>();
      finished = false;
    }

    public void addAlreadyExisted(SolrInputDocument doc) {
      alreadyExisted.add(doc);
    }
    
    public void addFailed(SolrInputDocument doc, Exception e) {
      failed.put(doc, e);
    }
    
    public void finished() {
      finished = true;
    }
    
    public List<SolrInputDocument> getAlreaydExisted() {
      return alreadyExisted;
    }
    
    public Map<SolrInputDocument, Exception> getFailed() {
      return failed;
    }
    
    public boolean isFinished() {
      return finished;
    }
    
  }
  
  private static class IndexingEngineBatchJobAndResultFuture {
    
    private final IndexingEngineBatchJob job;
    private final IndexingEngineBatchJobResultFuture resultFuture;
    private final Map<Future<UpdateResponse>, UpdateRequest> updateResponseFutureToUpdateRequestMap;
    
    public IndexingEngineBatchJobAndResultFuture(IndexingEngineBatchJob job, IndexingEngineBatchJobResultFuture resultFuture) {
      this.job = job;
      this.resultFuture = resultFuture;
      updateResponseFutureToUpdateRequestMap = new HashMap<Future<UpdateResponse>, UpdateRequest>(); 
    }
    
    public void handleFinished() {
      for (Map.Entry<Future<UpdateResponse>, UpdateRequest> updateResponseFutureToUpdateRequestMapEntry : updateResponseFutureToUpdateRequestMap.entrySet()) {
        if (updateResponseFutureToUpdateRequestMapEntry.getKey().isDone()) {
          try {
            try {
              job.updateRequestSucceeded(updateResponseFutureToUpdateRequestMapEntry.getValue(), updateResponseFutureToUpdateRequestMapEntry.getKey().get());
            } catch (InterruptedException e) {
              // ignore - not supposed to happen
            }
          } catch (ExecutionException e) {
            job.updateRequestFailed(updateResponseFutureToUpdateRequestMapEntry.getValue(), (Exception)e.getCause());
          }
        }
      }

    }
    
  }
  
  //TODO check synchronization issues
  protected static final class SynchedBatchJobsList {
    
    private final List<IndexingEngineBatchJobAndResultFuture> toBeProcessedList;
    private final int maxPendingJobs;
    
    protected SynchedBatchJobsList(int maxPendingJobs) {
      this.maxPendingJobs = maxPendingJobs;
      // TODO use the most efficient List implementation for the operations we do on it
      toBeProcessedList = new LinkedList<IndexingEngineBatchJobAndResultFuture>();
    }
    
    protected IndexingEngineBatchJobAndResultFuture pull() {
      synchronized (toBeProcessedList) {
        IndexingEngineBatchJobAndResultFuture batchJob = toBeProcessedList.remove(0);
        if (batchJob != null) {
          toBeProcessedList.notifyAll();
        }
        return batchJob;
      }
    }
    
    protected void add(IndexingEngineBatchJobAndResultFuture batchJob) {
      synchronized (toBeProcessedList) {
        while (true) {
          if (roomFor(batchJob)) {
            toBeProcessedList.add(batchJob);
            continue;
          } else {
            try {
              toBeProcessedList.wait();
            } catch (InterruptedException e) {
              // ignore - should not happen
            }
          }
        }
      }
    }
    
    protected boolean roomFor(IndexingEngineBatchJobAndResultFuture batchJob) {
      return toBeProcessedList.size() < maxPendingJobs;
    }

    protected boolean everRoomFor(IndexingEngineBatchJobAndResultFuture batchJob) {
      return true;
    }

    protected void waitForEmpty() {
      synchronized (toBeProcessedList) {
        while (toBeProcessedList.size() > 0) {
          try {
            toBeProcessedList.wait();
          } catch (InterruptedException e) {
            // ignore - should not happen
          }
        }
      }
    }
    
    protected int size() {
      return toBeProcessedList.size();
    }
    
  }
  
  public static class IndexingEngineBatchJobResultFuture implements Future<IndexingEngineBatchJobResult> {

    private final IndexingEngineBatchJobResult indexingEngineBatchJobResult;
    private Object finishedSyncObj;
    private volatile boolean finished;
    
    public IndexingEngineBatchJobResultFuture(IndexingEngineBatchJobResult indexingEngineBatchJobResult) {
      this.indexingEngineBatchJobResult = indexingEngineBatchJobResult;
      finishedSyncObj = new Object();
      finished = false;
    }
    
    public void finished() {
      synchronized (finishedSyncObj) {
        finished = true;
        finishedSyncObj.notifyAll();
      }
    }
    
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      throw new RuntimeException(new OperationNotSupportedException());
    }

    @Override
    public IndexingEngineBatchJobResult get() throws InterruptedException {
      synchronized (finishedSyncObj) {
        if (!finished) finishedSyncObj.wait();
        return indexingEngineBatchJobResult;
      }
    }

    @Override
    public IndexingEngineBatchJobResult get(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
      synchronized (finishedSyncObj) {
        if (!finished) finishedSyncObj.wait(unit.toMillis(timeout));
        if (finished) {
          return indexingEngineBatchJobResult;
        } else {
          throw new TimeoutException();
        }
      }
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public boolean isDone() {
      return finished;
    }
    
  }

  
  @Test
  public void testIt() throws Exception {
    
    Merger merger = new Merger() {

      @Override
      public SolrInputDocument merge(SolrDocument alreadyInSolr, SolrInputDocument doc) {
        SolrInputDocument mergedDoc = new SolrInputDocument();
        doc.copyExceptUniquePartRef(mergedDoc);
        mergedDoc.setField("val", (String)alreadyInSolr.getFieldValue("val") + doc.getFieldValue("val") );
        return mergedDoc;
      }
      
    };
 
    IndexingEngineBatchJob indexingEngineBatchJob1 = new IndexingEngineBatchJob(merger);
    IndexingEngineBatchJob indexingEngineBatchJob2 = new IndexingEngineBatchJob(merger);
    
    // Non-correlatable, if already exists, this is a duplicate, so just drop it
    SolrInputDocument doc1_1 = new SolrInputDocument();
    doc1_1.addField("id", "id1");
    doc1_1.addField("val", "val1_1");
    indexingEngineBatchJob1.add(doc1_1, IndexInstruction.SKIP_IF_EXISTS);
    SolrInputDocument doc1_2 = new SolrInputDocument();
    doc1_2.addField("id", "id1");
    doc1_2.addField("val", "val1_2");
    indexingEngineBatchJob1.add(doc1_2, IndexInstruction.INDEX_NO_MATTER_IF_EXISTS);
    SolrInputDocument doc1_3 = new SolrInputDocument();
    doc1_3.addField("id", "id1");
    doc1_3.addField("val", "val1_3");
    indexingEngineBatchJob1.add(doc1_3, IndexInstruction.SKIP_IF_EXISTS);

    
    SolrInputDocument doc2_1 = new SolrInputDocument();
    doc2_1.addField("id", "id2");
    doc2_1.addField("val", "val2_1");
    indexingEngineBatchJob1.add(doc2_1, IndexInstruction.OPTIMISTIC_LOCKING);
    SolrInputDocument doc2_2 = new SolrInputDocument();
    doc2_2.addField("id", "id2");
    doc2_2.addField("val", "val2_2");
    indexingEngineBatchJob1.add(doc2_2, IndexInstruction.OPTIMISTIC_LOCKING);
    
    SolrInputDocument doc2_3 = new SolrInputDocument();
    doc2_3.addField("id", "id2");
    doc2_3.addField("val", "val2_3");
    indexingEngineBatchJob2.add(doc2_3, IndexInstruction.OPTIMISTIC_LOCKING);
    SolrInputDocument doc2_4 = new SolrInputDocument();
    doc2_4.addField("id", "id2");
    doc2_4.addField("val", "val2_4");
    indexingEngineBatchJob2.add(doc2_4, IndexInstruction.OPTIMISTIC_LOCKING);

    
    Future<IndexingEngineBatchJobResult> indexingEngineBatchJobResultFuture1 = engine.index(indexingEngineBatchJob1);
    Future<IndexingEngineBatchJobResult> indexingEngineBatchJobResultFuture2 = engine.index(indexingEngineBatchJob2);
    IndexingEngineBatchJobResult indexingEngineBatchJobResult1 = indexingEngineBatchJobResultFuture1.get();
    IndexingEngineBatchJobResult indexingEngineBatchJobResult2 = indexingEngineBatchJobResultFuture2.get();
    
    Assert.assertEquals(1, indexingEngineBatchJobResult1.getAlreaydExisted().size());
    SolrInputDocument existingDoc = indexingEngineBatchJobResult1.getAlreaydExisted().get(0);
    Assert.assertEquals(doc1_3, existingDoc);
    
    Assert.assertEquals(0, indexingEngineBatchJobResult2.getAlreaydExisted().size());
    
    // TODO get all documents
    List<SolrDocument> allDocs = null; 
    
    Assert.assertEquals(2, allDocs.size());
    SolrDocument doc1 = findSolrDocumentById("id1", allDocs);
    Assert.assertEquals("val1_2", doc1.getFieldValue("val"));
    
    SolrDocument doc2 = findSolrDocumentById("id2", allDocs);
    String doc2Val = (String)doc2.getFieldValue("val");
    Assert.assertTrue(doc2Val.contains("val2_1"));
    Assert.assertTrue(doc2Val.contains("val2_2"));
    Assert.assertTrue(doc2Val.contains("val2_3"));
    Assert.assertTrue(doc2Val.contains("val2_4"));
    Assert.assertEquals(24, doc2Val.length());
  }
  
  public SolrDocument findSolrDocumentById(String id, List<SolrDocument> docs) {
    for (SolrDocument doc : docs) {
      if (((String)doc.getFieldValue("id")).equals(id)) return doc;
    }
    return null;
  }
  
  // TODO make support for at test that you can try to get updated-document from one collection, but if it is not there
  // you want to get from another collection. Just as we do for multiparts. This will probably involve moveForwardUpdatedDoc
  // implementations that keeps nextStep == NextStep.GET_UPDATED_DOC_AND_MERGE
  
  // TODO In general more testing - 100% code-coverage
  
}
