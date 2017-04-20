package org.apache.solr;

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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.lucene.util.LuceneTestCase.Slow;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.embedded.JettySolrRunner;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.ShardParams;
import org.apache.solr.core.SolrCore;
import org.apache.solr.search.LeafReaderTestWrappers.CountingWrapper;
import org.apache.solr.search.SolrReturnFields;
import org.apache.solr.servlet.SolrDispatchFilter;
import org.junit.Test;

@Slow
public class TestDistributedQueryAlgorithm extends BaseDistributedSearchTestCase {

  private final static int ROWS = 10;
  private final static int MAX_START = 5;
  private final static int TLONG_START = 1000;
  
  private int docCount;
  
  String t1="a_t";
  String tlong = "other_tl1";
  String existing_word = "hello";

  @Test
  @ShardsFixed(num = 2)
  public void testDQA() throws Exception {
    // This test is explicitly testing DQA. Make sure we run with the real DefaultProvider
    switchToOriginalDQADefaultProvider();

    del("*:*");
    
    // Making sure there are at least ROWS + MAX_START matching (existing_word) and ROWS + MAX_START non-matching
    // documents on each shard. With a completely fair distribution algorithm that will be true
    // if we index numShards = jettys.size * 2 * (ROWS + MAX_START) documents. To deal with a not completely fair
    // distribution we index jettys.size * 2 * (ROWS + MAX_START) + 10
    docCount = jettys.size() * 2 * (ROWS + MAX_START) + 10;
    
    for(int i = 0; i < docCount; i++)
    {
      indexr(id, i, 
          tlong, TLONG_START-i,
          t1, (i&1)==0 ? existing_word + " there" : "hi there");
    }

    commit();

    testDocReads();
    
    testQuerySortField();

    testQueryFieldList();
    
    testShardParamsDQA();
    
  }

  // Test the number of documents read from store using FIND_RELEVANCE_FIND_IDS_LIMITED_ROWS_FETCH_BY_IDS
  // vs FIND_ID_RELEVANCE_FETCH_BY_IDS. This demonstrates the advantage of FIND_RELEVANCE_FIND_IDS_LIMITED_ROWS_FETCH_BY_IDS
  // over FIND_ID_RELEVANCE_FETCH_BY_IDS (and vice versa)
  private void testDocReads() throws Exception {
    for (int startValue = 0; startValue <= MAX_START; startValue++) {
      // FIND_RELEVANCE_FIND_IDS_LIMITED_ROWS_FETCH_BY_IDS (assuming skipGetIds used - default)
      // Only reads data (required fields) from store for "rows + (#shards * start) documents across all shards
      // This can be optimized to become only "rows" 
      // Only reads the data once
      testDQADocReads(ShardParams.DQA.FIND_RELEVANCE_FIND_IDS_LIMITED_ROWS_FETCH_BY_IDS, startValue, ROWS, ROWS + (startValue * jettys.size()), ROWS + (startValue * jettys.size()));
  
      // DQA.FIND_ID_RELEVANCE_FETCH_BY_IDS (assuming skipGetIds not used - default)
      // Reads data (ids only) from store for "(rows + startValue) * #shards" documents for each shard
      // Besides that reads data (required fields) for "rows" documents across all shards
      testDQADocReads(ShardParams.DQA.FIND_ID_RELEVANCE_FETCH_BY_IDS, startValue, ROWS, (ROWS + startValue) * jettys.size(), ROWS + ((ROWS + startValue) * jettys.size()));
    }
  }
  
  // Test that all DQA's works no matter which sort field is used.
  // White-box test focusing on one area that easily breaks in the implementation of distinct DQAs
  private void testQuerySortField() throws Exception {
    assertTrue("FieldNames list is empty!", getFieldNames().length > 0);
    for (ShardParams.DQA dqa : ShardParams.DQA.values()) {
      for (String f : getFieldNames()) {
        assertSortQuery(dqa, 0, f, true);
        assertSortQuery(dqa, MAX_START, f, true);
        assertSortQuery(dqa, 0, f, false);
        assertSortQuery(dqa, MAX_START, f, false);
      }
      assertSortQuery(dqa, 0, id, true);
      assertSortQuery(dqa, MAX_START, id, true);
      assertSortQuery(dqa, 0, id, false);
      assertSortQuery(dqa, MAX_START, id, false);
      assertSortQuery(dqa, 0, SolrReturnFields.SCORE, true);
      assertSortQuery(dqa, MAX_START, SolrReturnFields.SCORE, true);
      assertSortQuery(dqa, 0, SolrReturnFields.SCORE, false);
      assertSortQuery(dqa, MAX_START, SolrReturnFields.SCORE, false);
      assertSortQuery(dqa, 0, tlong, true);
      assertSortQuery(dqa, MAX_START, tlong, true);
      assertSortQuery(dqa, 0, tlong, false);
      assertSortQuery(dqa, MAX_START, tlong, false);
    }
  }

  // Test that DQA's works no matter which fl (field list) is used.
  // White-box test focusing on one area that easily breaks in the implementation of distinct DQA's
  private void testQueryFieldList() throws SolrServerException, IOException {
    for (ShardParams.DQA dqa : ShardParams.DQA.values()) {
      assertFieldListQuery(dqa, id);
      assertFieldListQuery(dqa, id, SolrReturnFields.SCORE);
      assertFieldListQuery(dqa, t1);
      assertFieldListQuery(dqa, tlong);
    }
  }
  
  private void testShardParamsDQA() {
    for (ShardParams.DQA dqa : ShardParams.DQA.values()) {
      ModifiableSolrParams params = new ModifiableSolrParams();
      dqa.apply(params);
      assertEquals(dqa, ShardParams.DQA.get(params));
      for (String aliasId : dqa.getAliasIds()) {
        params = new ModifiableSolrParams();
        params.add(ShardParams.DQA.QUERY_PARAM, aliasId);
        assertEquals(dqa, ShardParams.DQA.get(params));
      }
    }
  }
  
  // Helpers
  
  private void testDQADocReads(ShardParams.DQA dqa, int start, int rows, int expectedUniqueIdCount, int expectedTotalCount) throws Exception {
    List<SolrCoreTestWrapper<CountingWrapper>> wrappers = new ArrayList<SolrCoreTestWrapper<CountingWrapper>>();
    
    try {
      for (JettySolrRunner jetty : jettys) {
        for (SolrCore solrCore : ((SolrDispatchFilter) jetty.getDispatchFilter().getFilter()).getCores().getCores()) {
          wrappers.add(new SolrCoreTestWrapper<CountingWrapper>(solrCore, CountingWrapper.getFactory()));
        }
      }
      
      QueryResponse resp = queryDQA(dqa, CommonParams.Q, existing_word, CommonParams.START, start, CommonParams.ROWS, rows);
      assertEquals(rows, resp.getResults().size());
      
      int totalCount = 0;
      int uniqueIdCount = 0;
      
      for (SolrCoreTestWrapper<CountingWrapper> wrapper : wrappers) {
        totalCount += CountingWrapper.getTotalDocumentCountAndReset(wrapper.getWrappers());
        uniqueIdCount += CountingWrapper.getUniqueDocumentCountAndReset(wrapper.getWrappers());
      }

      assertEquals(expectedUniqueIdCount, uniqueIdCount);
      assertEquals(expectedTotalCount, totalCount);
    } finally {
      for (SolrCoreTestWrapper<CountingWrapper> wrapper : wrappers) {
        wrapper.unwrap();
      }
    }
  }
  
  private QueryResponse queryDQA(ShardParams.DQA dqa, Object... q) throws SolrServerException, IOException {
    final ModifiableSolrParams params = new ModifiableSolrParams();

    for (int i = 0; i < q.length; i += 2) {
      params.add(q[i].toString(), q[i + 1].toString());
    }
    
    setDistributedParams(params);
    dqa.apply(params);
    
    return queryServer(params);
  }
  
  private void assertSortQuery(ShardParams.DQA dqa, int start, String fieldName, boolean isAscending) throws SolrServerException, InstantiationException, IllegalAccessException, IOException {
    String sortDirection = (isAscending ? "asc" : "desc");
    String generalMsg = "DQA: " + dqa.getId() + ", start: " + start + ", fieldName: " + fieldName + ", sort-direction: " + sortDirection;
    QueryResponse resp = queryDQA(dqa, CommonParams.Q, existing_word, CommonParams.START, start, CommonParams.SORT, fieldName + " " + sortDirection, CommonParams.FL, fieldName);

    Number previousFieldValue = null;

    int idNextValue = (isAscending)?(2 * start):(docCount - 2 - (2 * start));
    int tlongNextValue = (isAscending)?((TLONG_START - docCount) + 2 + (2 * start)):(TLONG_START - (2 * start));
    int resultIndex = 0;
    for(SolrDocument doc : resp.getResults())
    {
      String msg = generalMsg + ", result-index: " + resultIndex;
      
      Object fieldValue = doc.getFieldValue(fieldName);
      assertNotNull("'" + fieldName + "' was not found in the document!", fieldValue);
      
      Number fieldValueAsNumber;
      
      if (fieldValue instanceof Date) {
        fieldValueAsNumber = new Long(((Date) fieldValue).getTime());
      } else {
        fieldValueAsNumber = (Number) fieldValue;
      }
     
      if (previousFieldValue == null) {
        previousFieldValue = fieldValueAsNumber;
      }
      
      assertTrue(msg, isAscending ? fieldValueAsNumber.doubleValue() >= previousFieldValue.doubleValue() : fieldValueAsNumber.doubleValue() <= previousFieldValue.doubleValue());
      if (fieldName == id) {
        assertEquals(msg, idNextValue, fieldValueAsNumber);
        idNextValue += (isAscending)?2:-2;
      }
      if (fieldName == tlong) {
        assertEquals(msg, tlongNextValue, fieldValueAsNumber.longValue());
        tlongNextValue += (isAscending)?2:-2;
      }
      
      previousFieldValue = fieldValueAsNumber;
      resultIndex++;
    }
    
  }

  private void assertFieldListQuery(ShardParams.DQA dqa, String... fieldNames) throws SolrServerException, IOException {
    String generalMsg = "DQA: " + dqa.getId() + ", fieldNames: " + fieldNames;
    QueryResponse resp = queryDQA(dqa, CommonParams.Q, existing_word, CommonParams.FL, StringUtils.join(fieldNames, " "));
    assertFalse(generalMsg + ". Response is empty!", resp.getResults().isEmpty());

    int resultIndex = 0;
    for (SolrDocument doc : resp.getResults()) {
      String msg = generalMsg + ", result-index: " + resultIndex; 
      
      Map<String,Object> valueMap = doc.getFieldValueMap();
      
      assertEquals(msg, fieldNames.length, valueMap.size());
      
      for (String fieldName : fieldNames) {
        assertTrue(msg + ". '" + fieldName + "' was not found in the document!", valueMap.containsKey(fieldName));
      }
      
      resultIndex++;
    }
  }


}
