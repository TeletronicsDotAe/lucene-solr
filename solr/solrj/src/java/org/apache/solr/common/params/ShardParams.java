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
package org.apache.solr.common.params;

/**
 * Parameters used for distributed search.
 * 
 * When adding a new parameter here, please also add the corresponding
 * one-line test case in the ShardParamsTest class.
 * 
 */
public interface ShardParams {
  /** the shards to use (distributed configuration) */
  public static final String SHARDS = "shards";
  
  /** per-shard start and rows */
  public static final String SHARDS_ROWS = "shards.rows";
  public static final String SHARDS_START = "shards.start";

  /** IDs of the shard documents */
  public static final String IDS = "ids";
  
  /** whether the request goes to a shard */
  public static final String IS_SHARD = "isShard";
  
  /** The requested URL for this shard */
  public static final String SHARD_URL = "shard.url";
  
  /** The Request Handler for shard requests */
  public static final String SHARDS_QT = "shards.qt";
  
  /** Request detailed match info for each shard (true/false) */
  public static final String SHARDS_INFO = "shards.info";

  /** Should things fail if there is an error? (true/false) */
  public static final String SHARDS_TOLERANT = "shards.tolerant";
  
  /** query purpose for shard requests */
  public static final String SHARDS_PURPOSE = "shards.purpose";

  public static final String _ROUTE_ = "_route_";

  
  /**
   * Distributed Query Algorithm (DQA). Controlling which query-algorithm to use for distributed searches
   */
  public enum DQA {
    /**
     * Algorithm
     * - Shard-queries 1) Ask, by forwarding the outer query, each shard for id and relevance of the (up to) #rows most relevant matching documents
     * - Find among those id/relevances the #rows id's with the highest global relevances (lets call this set of id's X)
     * - Shard-queries 2) Ask, by sending id's, each shard to return the documents from set X that it holds
     * - Return the fetched documents to the client
     */
    FIND_ID_RELEVANCE_FETCH_BY_IDS("find-id-relevance_fetch-by-ids", new String[]{"firfbi"}, 2) {
      public boolean forceSkipGetIds(SolrParams params) {
        // Default do not force skip get-ids phase
        return params.getBool(FORCE_SKIP_GET_IDS_PARAM, false);
      }
    },
    /**
     * Algorithm
     * - Shard-queries 1) Ask, by forwarding the outer query, each shard for relevance of the (up to) #rows most relevant matching documents
     * - Find among those relevances the #rows highest global relevances
     * Note for each shard (S) how many entries (docs_among_most_relevant(S)) it has among the #rows globally highest relevances
     * - Shard-queries 2) Ask, by forwarding the outer query, each shard S for id and relevance of the (up to) #docs_among_most_relevant(S) most relevant matching documents
     * - Find among those id/relevances the #rows id's with the highest global relevances (lets call this set of id's X)
     * - Shard-queries 3) Ask, by sending id's, each shard to return the documents from set X that it holds
     * - Return the fetched documents to the client 
     * 
     * Advantages
     * Asking for data from store (id in shard-queries 1) of FIND_ID_RELEVANCE_FETCH_BY_IDS) can be expensive, therefore sometimes you want to ask for data
     * from as few documents as possible.
     * The main purpose of this algorithm it to limit the rows asked for in shard-queries 2) compared to shard-queries 1) of FIND_ID_RELEVANCE_FETCH_BY_IDS.
     * Lets call the number of rows asked for by the outer request for "outer-rows"
     * shard-queries 2) will never ask for data from more than "outer-rows" documents total across all involved shards. shard-queries 1) of FIND_ID_RELEVANCE_FETCH_BY_IDS
     * will ask each shard for data from "outer-rows" documents, and in worst case if each shard contains "outer-rows" matching documents you will
     * fetch data for "number of shards involved" * "outer-rows".
     * Using FIND_RELEVANCE_FIND_IDS_LIMITED_ROWS_FETCH_BY_IDS will become more beneficial the more
     * - shards are involved
     * - and/or the more matching documents each shard holds
     */
    FIND_RELEVANCE_FIND_IDS_LIMITED_ROWS_FETCH_BY_IDS("find-relevance_find-ids-limited-rows_fetch-by-ids", new String[]{"frfilrfbi"}, 3) {
      public boolean forceSkipGetIds(SolrParams params) {
        // Default force skip get-ids phase. In this algorithm there are really never any reason not to skip it
        return params.getBool(FORCE_SKIP_GET_IDS_PARAM, true);
      }
    };
    
    private final String id;
    private final String[] aliasIds;
    private final int distributedSteps;
    
    private DQA(final String id, String[] aliasIds, int distributedSteps) {
        this.id = id;
        this.aliasIds = aliasIds;
        this.distributedSteps = distributedSteps;
    }

    /** Request parameter to use to select the DQA. Value: id or aliasId of the chosen DQA */
    public static final String QUERY_PARAM = "dqa";
    /** Request parameter to force skip get-ids phase of the distributed query? Value: true or false 
     * Even if you do not force it, the system might choose to do it anyway
     * Skipping the get-ids phase
     * - FIND_ID_RELEVANCE_FETCH_BY_IDS: Fetch entire documents in Shard-queries 1) and skip Shard-queries 2)
     * - FIND_RELEVANCE_FIND_IDS_LIMITED_ROWS_FETCH_BY_IDS: Fetch entire documents in Shard-queries 2) and skip Shard-queries 3)
     */
    public static final String FORCE_SKIP_GET_IDS_PARAM = QUERY_PARAM + ".forceSkipGetIds";

    /**
     * Interface to implement to change the default DQA
     */
    public static interface DefaultProvider {
      /**
       * Default DQA to use for this particular request
       * @param params The request params to use for the decision
       * @return The decided DQA
       */
      public DQA getDefault(SolrParams params);
    }

    /**
     * By default the default DQA is FIND_ID_RELEVANCE_FETCH_BY_IDS for all requests
     */
    public static class DefaultDefaultProvider implements DefaultProvider {
      private static final DQA DEFAULT = FIND_ID_RELEVANCE_FETCH_BY_IDS;
      
      public DQA getDefault(SolrParams params) {
        // Here we can implement a more sophisticated default-DQA decision algorithm, if we know that for some types of request
        // a certain DQA will in general perform better
        return DEFAULT;
      }
    }
    
    /**
     * Override the default DQA provider
     * @param newDefaultProvider The new default DQA provider to use
     * @return The DQA provider just replaced
     */
    public static DefaultProvider setDefaultProvider(DefaultProvider newDefaultProvider) {
      DefaultProvider oldDefaultProvider = defaultProvider;
      defaultProvider = newDefaultProvider;
      return oldDefaultProvider;
    }
    
    private static DefaultProvider defaultProvider = new DefaultDefaultProvider();

    /**
     * Get the default DQA for a particular request
     * @param params The request params to use for the decision
     * @return The default DQA for this request
     */
    public static DQA getDefault(SolrParams params) {
      return defaultProvider.getDefault(params);
    }
    
    /**
     * Get the DQA to be used for a particular request
     * @param params The request params to use for the decision
     * @return The DQA for this request
     */
    public static DQA get(SolrParams params) {
      String id = params.get(QUERY_PARAM);
      for (DQA dqa : values()) {
        if (dqa.id.equals(id)) return dqa;
        for (String aliasId : dqa.aliasIds) {
          if (aliasId.equals(id)) return dqa;
        }
      }
      return getDefault(params);
    }
    
    /**
     * @return The id of this DQA. Set request param QUERY_PARAM to this value to
     * select this DQA
     */
    public String getId() {
      return id;
    }
    
    /**
     * @return Alternative ids of this DQA. Set request param QUERY_PARAM to this one of the
     * values to select this DQA
     */
    public String[] getAliasIds() {
      return aliasIds;
    }

    /**
     * @return The number of sets of shard-queries this DQA will do
     */
    public int getDistributedSteps() {
      return distributedSteps;
    }

    /**
     * @see java.lang.Enum#toString()
     */
    @Override
    public String toString() {
      return getId();
    }
    
    /**
     * Easy way to make sure this DQA is used for a request you are about to fire 
     * @param params The params if the request you are about to fire
     */
    public void apply(ModifiableSolrParams params) {
      params.set(QUERY_PARAM, getId());
    }
    
    /**
     * Force skip of the get-ids phase when executing a particular request
     * @param params The request params to use for the decision
     * @return Force of skip get-ids phase
     */
    public abstract boolean forceSkipGetIds(SolrParams params);
  }
}
