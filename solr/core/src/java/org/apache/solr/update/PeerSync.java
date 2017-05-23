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
import java.net.ConnectException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Timer;
import org.apache.http.NoHttpResponseException;
import org.apache.http.client.HttpClient;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.cloud.ZkController;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.IOUtils;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrInfoMBean;
import org.apache.solr.handler.component.HttpShardHandlerFactory;
import org.apache.solr.handler.component.ShardHandler;
import org.apache.solr.handler.component.ShardHandlerFactory;
import org.apache.solr.handler.component.ShardRequest;
import org.apache.solr.handler.component.ShardResponse;
import org.apache.solr.logging.MDCLoggingContext;
import org.apache.solr.metrics.SolrMetricManager;
import org.apache.solr.metrics.SolrMetricProducer;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.update.processor.UpdateRequestProcessor;
import org.apache.solr.update.processor.UpdateRequestProcessorChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.solr.update.processor.DistributedUpdateProcessor.DistribPhase.FROMLEADER;
import static org.apache.solr.update.processor.DistributingUpdateProcessorFactory.DISTRIB_UPDATE_PARAM;

/**
 * @lucene.experimental
 */
public class PeerSync implements SolrMetricProducer {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  // comparator that sorts by absolute value, putting highest first
  public static Comparator<Long> absComparator = (o1, o2) -> {
    long l1 = Math.abs(o1);
    long l2 = Math.abs(o2);
    if (l1 > l2) return -1;
    if (l1 < l2) return 1;
    return 0;
  };
  // comparator that sorts update records by absolute value of version, putting lowest first
  private static Comparator<Object> updateRecordComparator = (o1, o2) -> {
    if (!(o1 instanceof List)) return 1;
    if (!(o2 instanceof List)) return -1;

    List lst1 = (List) o1;
    List lst2 = (List) o2;

    long l1 = Math.abs((Long) lst1.get(1));
    long l2 = Math.abs((Long) lst2.get(1));

    if (l1 > l2) return 1;
    if (l1 < l2) return -1;
    return 0;
  };
  private final HttpShardHandlerFactory shardHandlerFactory;
  private final ShardHandler shardHandler;
  private final boolean cantReachIsSuccess;
  private final boolean getNoVersionsIsSuccess;
  private final boolean doFingerprint;
  private final HttpClient client;
  private final boolean onlyIfActive;
  private boolean debug = log.isDebugEnabled();
  private List<String> replicas;
  private int nUpdates;
  private int maxUpdates;  // maximum number of updates to request before failing
  private UpdateHandler uhandler;
  private UpdateLog ulog;
  private List<SyncShardRequest> requests = new ArrayList<>();
  private List<Long> startingVersions;
  private List<Long> ourUpdates;
  private Set<Long> ourUpdateSet;
  private Set<Long> requestedUpdateSet;
  private long ourLowThreshold;  // 20th percentile
  private long ourHighThreshold; // 80th percentile
  private long ourHighest;  // currently just used for logging/debugging purposes
  private SolrCore core;
  // metrics
  private Timer syncTime;
  private Counter syncErrors;
  private Counter syncSkipped;

  public PeerSync(SolrCore core, List<String> replicas, int nUpdates) {
    this(core, replicas, nUpdates, false, true);
  }

  public PeerSync(SolrCore core, List<String> replicas, int nUpdates, boolean cantReachIsSuccess, boolean getNoVersionsIsSuccess) {
    this(core, replicas, nUpdates, cantReachIsSuccess, getNoVersionsIsSuccess, false, true);
  }

  public PeerSync(SolrCore core, List<String> replicas, int nUpdates, boolean cantReachIsSuccess, boolean getNoVersionsIsSuccess, boolean onlyIfActive, boolean doFingerprint) {
    this.core = core;
    this.replicas = replicas;
    this.nUpdates = nUpdates;
    this.maxUpdates = nUpdates;
    this.cantReachIsSuccess = cantReachIsSuccess;
    this.getNoVersionsIsSuccess = getNoVersionsIsSuccess;
    this.doFingerprint = doFingerprint && !("true".equals(System.getProperty("solr.disableFingerprint")));
    this.client = core.getCoreDescriptor().getCoreContainer().getUpdateShardHandler().getHttpClient();
    this.onlyIfActive = onlyIfActive;

    uhandler = core.getUpdateHandler();
    ulog = uhandler.getUpdateLog();

    shardHandlerFactory = (HttpShardHandlerFactory) core.getCoreDescriptor().getCoreContainer().getShardHandlerFactory();
    shardHandler = shardHandlerFactory.getShardHandler(client);

    core.getCoreMetricManager().registerMetricProducer(SolrInfoMBean.Category.REPLICATION.toString(), this);
  }

  /**
   * Requests and applies recent updates from peers
   */
  public static void sync(SolrCore core, List<String> replicas, int nUpdates) {
    ShardHandlerFactory shardHandlerFactory = core.getCoreDescriptor().getCoreContainer().getShardHandlerFactory();

    ShardHandler shardHandler = shardHandlerFactory.getShardHandler();

    for (String replica : replicas) {
      ShardRequest sreq = new ShardRequest();
      sreq.shards = new String[]{replica};
      sreq.params = new ModifiableSolrParams();
      sreq.params.set("qt", "/get");
      sreq.params.set("distrib", false);
      sreq.params.set("getVersions", nUpdates);
      shardHandler.submit(sreq, replica, sreq.params);
    }

    for (String replica : replicas) {
      ShardResponse srsp = shardHandler.takeCompletedOrError();
    }

  }

  @Override
  public void initializeMetrics(SolrMetricManager manager, String registry, String scope) {
    syncTime = manager.timer(registry, "time", scope);
    syncErrors = manager.counter(registry, "errors", scope);
    syncSkipped = manager.counter(registry, "skipped", scope);
  }

  /**
   * optional list of updates we had before possibly receiving new updates
   */
  public void setStartingVersions(List<Long> startingVersions) {
    this.startingVersions = startingVersions;
  }

  public long percentile(List<Long> arr, float frac) {
    int elem = (int) (arr.size() * frac);
    return Math.abs(arr.get(elem));
  }

  // start of peersync related debug messages.  includes the core name for correlation.
  private String msg() {
    ZkController zkController = uhandler.core.getCoreDescriptor().getCoreContainer().getZkController();

    String myURL = "";

    if (zkController != null) {
      myURL = zkController.getBaseUrl();
    }

    // TODO: core name turns up blank in many tests - find URL if cloud enabled?
    return "PeerSync: core=" + uhandler.core.getName() + " url=" + myURL + " ";
  }

  /**
   * Returns true if peer sync was successful, meaning that this core may be considered to have the latest updates.
   * It does not mean that the remote replica is in sync with us.
   */
  public PeerSyncResult sync() {
    if (ulog == null) {
      syncErrors.inc();
      return PeerSyncResult.failure();
    }
    MDCLoggingContext.setCore(core);
    Timer.Context timerContext = null;
    try {
      log.info(msg() + "START replicas=" + replicas + " nUpdates=" + nUpdates);

      if (debug) {
        if (startingVersions != null) {
          log.debug(msg() + "startingVersions=" + startingVersions.size() + " " + startingVersions);
        }
      }
      // check if we already in sync to begin with
      if (doFingerprint && alreadyInSync()) {
        syncSkipped.inc();
        return PeerSyncResult.success();
      }

      // measure only when actual sync is performed
      timerContext = syncTime.time();

      // Fire off the requests before getting our own recent updates (for better concurrency)
      // This also allows us to avoid getting updates we don't need... if we got our updates and then got their updates,
      // they would
      // have newer stuff that we also had (assuming updates are going on and are being forwarded).
      for (String replica : replicas) {
        requestVersions(replica);
      }

      try (UpdateLog.RecentUpdates recentUpdates = ulog.getRecentUpdates()) {
        ourUpdates = recentUpdates.getVersions(nUpdates);
      }

      Collections.sort(ourUpdates, absComparator);

      if (startingVersions != null) {
        if (startingVersions.size() == 0) {
          log.warn("no frame of reference to tell if we've missed updates");
          syncErrors.inc();
          return PeerSyncResult.failure();
        }
        Collections.sort(startingVersions, absComparator);

        ourLowThreshold = percentile(startingVersions, 0.8f);
        ourHighThreshold = percentile(startingVersions, 0.2f);

        // now make sure that the starting updates overlap our updates
        // there shouldn't be reorders, so any overlap will do.

        long smallestNewUpdate = Math.abs(ourUpdates.get(ourUpdates.size() - 1));

        if (Math.abs(startingVersions.get(0)) < smallestNewUpdate) {
          log.warn(msg()
              + "too many updates received since start - startingUpdates no longer overlaps with our currentUpdates");
          syncErrors.inc();
          return PeerSyncResult.failure();
        }

        // let's merge the lists
        List<Long> newList = new ArrayList<>(ourUpdates);
        for (Long ver : startingVersions) {
          if (Math.abs(ver) < smallestNewUpdate) {
            newList.add(ver);
          }
        }

        ourUpdates = newList;
        Collections.sort(ourUpdates, absComparator);
      } else {

        if (ourUpdates.size() > 0) {
          ourLowThreshold = percentile(ourUpdates, 0.8f);
          ourHighThreshold = percentile(ourUpdates, 0.2f);
        } else {
          // we have no versions and hence no frame of reference to tell if we can use a peers
          // updates to bring us into sync
          log.info(msg() + "DONE.  We have no versions.  sync failed.");
          for (; ; ) {
            ShardResponse srsp = shardHandler.takeCompletedOrError();
            if (srsp == null) break;
            if (srsp.getException() == null) {
              List<Long> otherVersions = (List<Long>) srsp.getSolrResponse().getResponse().get("versions");
              if (otherVersions != null && !otherVersions.isEmpty()) {
                syncErrors.inc();
                return PeerSyncResult.failure(true);
              }
            }
          }
          syncErrors.inc();
          return PeerSyncResult.failure(false);
        }
      }

      ourHighest = ourUpdates.get(0);
      ourUpdateSet = new HashSet<>(ourUpdates);
      requestedUpdateSet = new HashSet<>();

      for (; ; ) {
        ShardResponse srsp = shardHandler.takeCompletedOrError();
        if (srsp == null) break;
        boolean success = handleResponse(srsp);
        if (!success) {
          log.info(msg() + "DONE. sync failed");
          shardHandler.cancelAll();
          syncErrors.inc();
          return PeerSyncResult.failure();
        }
      }

      // finish up any comparisons with other shards that we deferred
      boolean success = true;
      for (SyncShardRequest sreq : requests) {
        if (sreq.doFingerprintComparison) {
          success = compareFingerprint(sreq);
          if (!success) break;
        }
      }

      log.info(msg() + "DONE. sync " + (success ? "succeeded" : "failed"));
      if (!success) {
        syncErrors.inc();
      }
      return success ? PeerSyncResult.success() : PeerSyncResult.failure();
    } finally {
      if (timerContext != null) {
        timerContext.close();
      }
      MDCLoggingContext.clear();
    }
  }

  /**
   * Check if we are already in sync. Simple fingerprint comparison should do
   */
  private boolean alreadyInSync() {
    for (String replica : replicas) {
      requestFingerprint(replica);
    }

    for (; ; ) {
      ShardResponse srsp = shardHandler.takeCompletedOrError();
      if (srsp == null) break;

      Object replicaFingerprint = srsp.getSolrResponse().getResponse().get("fingerprint");
      if (replicaFingerprint == null) {
        log.warn("Replica did not return a fingerprint - possibly an older Solr version");
        continue;
      }

      try {
        IndexFingerprint otherFingerprint = IndexFingerprint.fromObject(replicaFingerprint);
        IndexFingerprint ourFingerprint = IndexFingerprint.getFingerprint(core, Long.MAX_VALUE);
        if (IndexFingerprint.compare(otherFingerprint, ourFingerprint) == 0) {
          log.info("We are already in sync. No need to do a PeerSync ");
          return true;
        }
      } catch (IOException e) {
        log.warn("Could not cofirm if we are already in sync. Continue with PeerSync");
      }
    }

    return false;
  }

  private void requestFingerprint(String replica) {
    SyncShardRequest sreq = new SyncShardRequest();
    requests.add(sreq);

    sreq.shards = new String[]{replica};
    sreq.actualShards = sreq.shards;
    sreq.params = new ModifiableSolrParams();
    sreq.params = new ModifiableSolrParams();
    sreq.params.set("qt", "/get");
    sreq.params.set("distrib", false);
    sreq.params.set("getFingerprint", String.valueOf(Long.MAX_VALUE));

    shardHandler.submit(sreq, replica, sreq.params);
  }

  private void requestVersions(String replica) {
    SyncShardRequest sreq = new SyncShardRequest();
    requests.add(sreq);
    sreq.purpose = 1;
    sreq.shards = new String[]{replica};
    sreq.actualShards = sreq.shards;
    sreq.params = new ModifiableSolrParams();
    sreq.params.set("qt", "/get");
    sreq.params.set("distrib", false);
    sreq.params.set("getVersions", nUpdates);
    sreq.params.set("fingerprint", doFingerprint);
    shardHandler.submit(sreq, replica, sreq.params);
  }

  private boolean handleResponse(ShardResponse srsp) {
    ShardRequest sreq = srsp.getShardRequest();

    if (srsp.getException() != null) {

      // TODO: look at this more thoroughly - we don't want
      // to fail on connection exceptions, but it may make sense
      // to determine this based on the number of fails
      //
      // If the replica went down between asking for versions and asking for specific updates, that
      // shouldn't be treated as success since we counted on getting those updates back (and avoided
      // redundantly asking other replicas for them).
      if (cantReachIsSuccess && sreq.purpose == 1 && srsp.getException() instanceof SolrServerException) {
        Throwable solrException = ((SolrServerException) srsp.getException())
            .getRootCause();
        boolean connectTimeoutExceptionInChain = connectTimeoutExceptionInChain(srsp.getException());
        if (connectTimeoutExceptionInChain || solrException instanceof ConnectException || solrException instanceof ConnectTimeoutException
            || solrException instanceof NoHttpResponseException || solrException instanceof SocketException) {
          log.warn(msg() + " couldn't connect to " + srsp.getShardAddress() + ", counting as success", srsp.getException());

          return true;
        }
      }

      if (cantReachIsSuccess && sreq.purpose == 1 && srsp.getException() instanceof SolrException && ((SolrException) srsp.getException()).code() == 503) {
        log.warn(msg() + " got a 503 from " + srsp.getShardAddress() + ", counting as success", srsp.getException());
        return true;
      }

      if (cantReachIsSuccess && sreq.purpose == 1 && srsp.getException() instanceof SolrException && ((SolrException) srsp.getException()).code() == 404) {
        log.warn(msg() + " got a 404 from " + srsp.getShardAddress() + ", counting as success. " +
            "Perhaps /get is not registered?", srsp.getException());
        return true;
      }

      // TODO: we should return the above information so that when we can request a recovery through zookeeper, we do
      // that for these nodes

      // TODO: at least log???
      // srsp.getException().printStackTrace(System.out);

      log.warn(msg() + " exception talking to " + srsp.getShardAddress() + ", failed", srsp.getException());

      return false;
    }

    if (sreq.purpose == 1) {
      return handleVersions(srsp);
    } else {
      return handleUpdates(srsp);
    }
  }

  // sometimes the root exception is a SocketTimeoutException, but ConnectTimeoutException
  // is in the chain
  private boolean connectTimeoutExceptionInChain(Throwable exception) {
    Throwable t = exception;
    while (true) {
      if (t instanceof ConnectTimeoutException) {
        return true;
      }
      Throwable cause = t.getCause();
      if (cause != null) {
        t = cause;
      } else {
        return false;
      }
    }
  }

  private boolean canHandleVersionRanges(String replica) {
    SyncShardRequest sreq = new SyncShardRequest();
    requests.add(sreq);

    // determine if leader can handle version ranges
    sreq.shards = new String[]{replica};
    sreq.actualShards = sreq.shards;
    sreq.params = new ModifiableSolrParams();
    sreq.params.set("qt", "/get");
    sreq.params.set("distrib", false);
    sreq.params.set("checkCanHandleVersionRanges", false);

    ShardHandler sh = shardHandlerFactory.getShardHandler(client);
    sh.submit(sreq, replica, sreq.params);

    ShardResponse srsp = sh.takeCompletedIncludingErrors();
    Boolean canHandleVersionRanges = srsp.getSolrResponse().getResponse().getBooleanArg("canHandleVersionRanges");

    if (canHandleVersionRanges == null || canHandleVersionRanges.booleanValue() == false) {
      return false;
    }

    return true;
  }

  private boolean handleVersionsWithRanges(ShardResponse srsp, List<Long> otherVersions, SyncShardRequest sreq,
                                           boolean completeList, long otherHigh, long otherHighest) {
    // we may endup asking for updates for too many versions, causing 2MB post payload limit. Construct a range of
    // versions to request instead of asking individual versions
    List<String> rangesToRequest = new ArrayList<>();

    // construct ranges to request
    // both ourUpdates and otherVersions are sorted with highest range first
    // may be we can create another reverse the lists and avoid confusion
    int ourUpdatesIndex = ourUpdates.size() - 1;
    int otherUpdatesIndex = otherVersions.size() - 1;
    long totalRequestedVersions = 0;

    while (otherUpdatesIndex >= 0) {
      // we have run out of ourUpdates, pick up all the remaining versions from the other versions
      if (ourUpdatesIndex < 0) {
        String range = otherVersions.get(otherUpdatesIndex) + "..." + otherVersions.get(0);
        rangesToRequest.add(range);
        totalRequestedVersions += otherUpdatesIndex + 1;
        break;
      }

      // stop when the entries get old enough that reorders may lead us to see updates we don't need
      if (!completeList && Math.abs(otherVersions.get(otherUpdatesIndex)) < ourLowThreshold) break;

      if (ourUpdates.get(ourUpdatesIndex).longValue() == otherVersions.get(otherUpdatesIndex).longValue()) {
        ourUpdatesIndex--;
        otherUpdatesIndex--;
      } else if (Math.abs(ourUpdates.get(ourUpdatesIndex)) < Math.abs(otherVersions.get(otherUpdatesIndex))) {
        ourUpdatesIndex--;
      } else {
        long rangeStart = otherVersions.get(otherUpdatesIndex);
        while ((otherUpdatesIndex < otherVersions.size())
            && (Math.abs(otherVersions.get(otherUpdatesIndex)) < Math.abs(ourUpdates.get(ourUpdatesIndex)))) {
          otherUpdatesIndex--;
          totalRequestedVersions++;
        }
        // construct range here
        rangesToRequest.add(rangeStart + "..." + otherVersions.get(otherUpdatesIndex + 1));
      }
    }

    // TODO, do we really need to hold on to all the ranges we requested
    // keeping track of totalRequestedUpdates should suffice for verification
    sreq.requestedRanges = rangesToRequest;
    sreq.totalRequestedUpdates = totalRequestedVersions;

    if (rangesToRequest.isEmpty()) {
      log.info(msg() + " No additional versions requested. ourLowThreshold=" + ourLowThreshold + " otherHigh="
          + otherHigh + " ourHighest=" + ourHighest + " otherHighest=" + otherHighest);

      // we had (or already requested) all the updates referenced by the replica

      // If we requested updates from another replica, we can't compare fingerprints yet with this replica, we need to
      // defer
      if (doFingerprint) {
        sreq.doFingerprintComparison = true;
      }

      return true;
    }

    if (totalRequestedVersions > maxUpdates) {
      log.info(msg() + " Failing due to needing too many updates:" + maxUpdates);
      return false;
    }

    String rangesToRequestStr = rangesToRequest.stream().collect(Collectors.joining(","));
    return requestUpdates(srsp, rangesToRequestStr, totalRequestedVersions);
  }

  private boolean handleVersions(ShardResponse srsp) {
    // we retrieved the last N updates from the replica
    List<Long> otherVersions = (List<Long>) srsp.getSolrResponse().getResponse().get("versions");
    // TODO: how to handle short lists?

    SyncShardRequest sreq = (SyncShardRequest) srsp.getShardRequest();
    sreq.reportedVersions = otherVersions;

    Object fingerprint = srsp.getSolrResponse().getResponse().get("fingerprint");

    log.info(msg() + " Received " + otherVersions.size() + " versions from " + sreq.shards[0] + " fingerprint:" + fingerprint);
    if (fingerprint != null) {
      sreq.fingerprint = IndexFingerprint.fromObject(fingerprint);
    }

    if (otherVersions.size() == 0) {
      return getNoVersionsIsSuccess;
    }

    boolean completeList = otherVersions.size() < nUpdates;  // do we have their complete list of updates?

    Collections.sort(otherVersions, absComparator);

    if (debug) {
      log.debug(msg() + " sorted versions from " + sreq.shards[0] + " = " + otherVersions);
    }

    long otherHigh = percentile(otherVersions, .2f);
    long otherLow = percentile(otherVersions, .8f);
    long otherHighest = otherVersions.get(0);

    if (ourHighThreshold < otherLow) {
      // Small overlap between version windows and ours is older
      // This means that we might miss updates if we attempted to use this method.
      // Since there exists just one replica that is so much newer, we must
      // fail the sync.
      log.info(msg() + " Our versions are too old. ourHighThreshold=" + ourHighThreshold + " otherLowThreshold=" + otherLow + " ourHighest=" + ourHighest + " otherHighest=" + otherHighest);
      return false;
    }

    if (ourLowThreshold > otherHigh) {
      // Small overlap between windows and ours is newer.
      // Using this list to sync would result in requesting/replaying results we don't need
      // and possibly bringing deleted docs back to life.
      log.info(msg() + " Our versions are newer. ourLowThreshold=" + ourLowThreshold + " otherHigh=" + otherHigh + " ourHighest=" + ourHighest + " otherHighest=" + otherHighest);

      // Because our versions are newer, IndexFingerprint with the remote would not match us.
      // We return true on our side, but the remote peersync with us should fail.
      return true;
    }

    if (core.getSolrConfig().useRangeVersionsForPeerSync && canHandleVersionRanges(sreq.shards[0])) {
      return handleVersionsWithRanges(srsp, otherVersions, sreq, completeList, otherHigh, otherHighest);
    } else {
      return handleIndividualVersions(srsp, otherVersions, sreq, completeList, otherHigh, otherHighest);
    }
  }

  private boolean handleIndividualVersions(ShardResponse srsp, List<Long> otherVersions, SyncShardRequest sreq,
                                           boolean completeList, long otherHigh, long otherHighest) {
    List<Long> toRequest = new ArrayList<>();
    for (Long otherVersion : otherVersions) {
      // stop when the entries get old enough that reorders may lead us to see updates we don't need
      if (!completeList && Math.abs(otherVersion) < ourLowThreshold) break;

      if (ourUpdateSet.contains(otherVersion) || requestedUpdateSet.contains(otherVersion)) {
        // we either have this update, or already requested it
        // TODO: what if the shard we previously requested this from returns failure (because it goes
        // down)
        continue;
      }

      toRequest.add(otherVersion);
      requestedUpdateSet.add(otherVersion);
    }

    // TODO, do we really need to hold on to all the version numbers we requested.
    // keeping track of totalRequestedUpdates should suffice for verification
    sreq.requestedUpdates = toRequest;
    sreq.totalRequestedUpdates = toRequest.size();

    if (toRequest.isEmpty()) {
      log.info(msg() + " No additional versions requested. ourLowThreshold=" + ourLowThreshold + " otherHigh=" + otherHigh + " ourHighest=" + ourHighest + " otherHighest=" + otherHighest);

      // we had (or already requested) all the updates referenced by the replica

      // If we requested updates from another replica, we can't compare fingerprints yet with this replica, we need to defer
      if (doFingerprint) {
        sreq.doFingerprintComparison = true;
      }

      return true;
    }

    if (toRequest.size() > maxUpdates) {
      log.info(msg() + " Failing due to needing too many updates:" + maxUpdates);
      return false;
    }

    return requestUpdates(srsp, StrUtils.join(toRequest, ','), toRequest.size());
  }

  private boolean compareFingerprint(SyncShardRequest sreq) {
    if (sreq.fingerprint == null) return true;
    try {
      // check our fingerprint only upto the max version in the other fingerprint.
      // Otherwise for missed updates (look at missed update test in PeerSyncTest) ourFingerprint won't match with otherFingerprint
      IndexFingerprint ourFingerprint = IndexFingerprint.getFingerprint(core, sreq.fingerprint.getMaxVersionSpecified());
      int cmp = IndexFingerprint.compare(sreq.fingerprint, ourFingerprint);
      log.info("Fingerprint comparison: {}", cmp);
      if (cmp != 0) {
        log.info("Other fingerprint: {}, Our fingerprint: {}", sreq.fingerprint, ourFingerprint);
      }
      return cmp == 0;  // currently, we only check for equality...
    } catch (IOException e) {
      log.error(msg() + "Error getting index fingerprint", e);
      return false;
    }
  }

  private boolean requestUpdates(ShardResponse srsp, String versionsAndRanges, long totalUpdates) {
    String replica = srsp.getShardRequest().shards[0];

    log.info(msg() + "Requesting updates from " + replica + "n=" + totalUpdates + " versions=" + versionsAndRanges);

    // reuse our original request object
    ShardRequest sreq = srsp.getShardRequest();

    sreq.purpose = 0;
    sreq.params = new ModifiableSolrParams();
    sreq.params.set("qt", "/get");
    sreq.params.set("distrib", false);
    sreq.params.set("getUpdates", versionsAndRanges);
    sreq.params.set("onlyIfActive", onlyIfActive);

    // fingerprint should really be requested only for the maxversion  we are requesting updates for
    // In case updates are coming in while node is coming up after restart, node would have already
    // buffered some of the updates. fingerprint we requested with versions would reflect versions
    // in our buffer as well and will definitely cause a mismatch
    sreq.params.set("fingerprint", doFingerprint);
    sreq.responses.clear();  // needs to be zeroed for correct correlation to occur

    shardHandler.submit(sreq, sreq.shards[0], sreq.params);

    return true;
  }

  private boolean handleUpdates(ShardResponse srsp) {
    // we retrieved the last N updates from the replica
    List<Object> updates = (List<Object>) srsp.getSolrResponse().getResponse().get("updates");

    SyncShardRequest sreq = (SyncShardRequest) srsp.getShardRequest();
    if (updates.size() < sreq.totalRequestedUpdates) {
      log.error(msg() + " Requested " + sreq.totalRequestedUpdates + " updates from " + sreq.shards[0] + " but retrieved " + updates.size());
      return false;
    }

    // overwrite fingerprint we saved in 'handleVersions()'
    Object fingerprint = srsp.getSolrResponse().getResponse().get("fingerprint");

    if (fingerprint != null) {
      sreq.fingerprint = IndexFingerprint.fromObject(fingerprint);
    }


    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set(DISTRIB_UPDATE_PARAM, FROMLEADER.toString());
    params.set("peersync", true); // debugging
    SolrQueryRequest req = new LocalSolrQueryRequest(uhandler.core, params);
    SolrQueryResponse rsp = new SolrQueryResponse();

    UpdateRequestProcessorChain processorChain = req.getCore().getUpdateProcessingChain(null);
    UpdateRequestProcessor proc = processorChain.createProcessor(req, rsp);

    Collections.sort(updates, updateRecordComparator);

    Object o = null;
    long lastVersion = 0;
    try {
      // Apply oldest updates first
      for (Object obj : updates) {
        // should currently be a List<Oper,Ver,Doc/Id>
        o = obj;
        List<Object> entry = (List<Object>) o;

        if (debug) {
          log.debug(msg() + "raw update record " + o);
        }

        int oper = (Integer) entry.get(0) & UpdateLog.OPERATION_MASK;
        long version = (Long) entry.get(1);
        if (version == lastVersion && version != 0) continue;
        lastVersion = version;

        switch (oper) {
          case UpdateLog.ADD: {
            // byte[] idBytes = (byte[]) entry.get(2);
            SolrInputDocument sdoc = (SolrInputDocument) entry.get(entry.size() - 1);
            AddUpdateCommand cmd = new AddUpdateCommand(req);
            // cmd.setIndexedId(new BytesRef(idBytes));
            cmd.solrDoc = sdoc;
            cmd.setVersion(version);
            cmd.setRequestVersion(version);
            cmd.setLeaderLogic(false);
            cmd.setFlags(UpdateCommand.PEER_SYNC | UpdateCommand.IGNORE_AUTOCOMMIT);
            if (debug) {
              log.debug(msg() + "add " + cmd + " id " + sdoc.getField("id"));
            }
            proc.processAdd(cmd);
            break;
          }
          case UpdateLog.DELETE: {
            byte[] idBytes = (byte[]) entry.get(2);
            DeleteUpdateCommand cmd = new DeleteUpdateCommand(req);
            cmd.setIndexedId(new BytesRef(idBytes));
            cmd.setVersion(version);
            cmd.setRequestVersion(version);
            cmd.setLeaderLogic(false);
            cmd.setFlags(UpdateCommand.PEER_SYNC | UpdateCommand.IGNORE_AUTOCOMMIT);
            if (debug) {
              log.debug(msg() + "delete " + cmd + " " + new BytesRef(idBytes).utf8ToString());
            }
            proc.processDelete(cmd);
            break;
          }

          case UpdateLog.DELETE_BY_QUERY: {
            String query = (String) entry.get(2);
            DeleteUpdateCommand cmd = new DeleteUpdateCommand(req);
            cmd.query = query;
            cmd.setVersion(version);
            cmd.setRequestVersion(version);
            cmd.setLeaderLogic(false);
            cmd.setFlags(UpdateCommand.PEER_SYNC | UpdateCommand.IGNORE_AUTOCOMMIT);
            if (debug) {
              log.debug(msg() + "deleteByQuery " + cmd);
            }
            proc.processDelete(cmd);
            break;
          }
          case UpdateLog.UPDATE_INPLACE: {
            AddUpdateCommand cmd = UpdateLog.convertTlogEntryToAddUpdateCommand(req, entry, oper, version);
            cmd.setFlags(UpdateCommand.PEER_SYNC | UpdateCommand.IGNORE_AUTOCOMMIT);
            if (debug) {
              log.debug(msg() + "inplace update " + cmd + " prevVersion=" + cmd.prevVersion + ", doc=" + cmd.solrDoc);
            }
            proc.processAdd(cmd);
            break;
          }

          default:
            throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Unknown Operation! " + oper);
        }

      }

    } catch (IOException e) {
      // TODO: should this be handled separately as a problem with us?
      // I guess it probably already will by causing replication to be kicked off.
      sreq.updateException = e;
      log.error(msg() + "Error applying updates from " + sreq.shards + " ,update=" + o, e);
      return false;
    } catch (Exception e) {
      sreq.updateException = e;
      log.error(msg() + "Error applying updates from " + sreq.shards + " ,update=" + o, e);
      return false;
    } finally {
      try {
        proc.finish();
      } catch (Exception e) {
        sreq.updateException = e;
        log.error(msg() + "Error applying updates from " + sreq.shards + " ,finish()", e);
        return false;
      } finally {
        IOUtils.closeQuietly(proc);
      }
    }

    return compareFingerprint(sreq);
  }

  private static class SyncShardRequest extends ShardRequest {
    List<Long> reportedVersions;
    IndexFingerprint fingerprint;
    boolean doFingerprintComparison;
    List<Long> requestedUpdates;
    Exception updateException;
    List<String> requestedRanges;
    long totalRequestedUpdates;
  }

  public static class PeerSyncResult {
    private final boolean success;
    private final Boolean otherHasVersions;

    public PeerSyncResult(boolean success, Boolean otherHasVersions) {
      this.success = success;
      this.otherHasVersions = otherHasVersions;
    }

    public static PeerSyncResult success() {
      return new PeerSyncResult(true, null);
    }

    public static PeerSyncResult failure() {
      return new PeerSyncResult(false, null);
    }

    public static PeerSyncResult failure(boolean otherHasVersions) {
      return new PeerSyncResult(false, otherHasVersions);
    }

    public boolean isSuccess() {
      return success;
    }

    public Optional<Boolean> getOtherHasVersions() {
      return Optional.ofNullable(otherHasVersions);
    }
  }

}
