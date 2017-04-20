package org.apache.solr.cloud;

import static org.apache.solr.client.solrj.embedded.JettySolrRunner.SEARCH_CREDENTIALS;
import static org.apache.solr.client.solrj.embedded.JettySolrRunner.UPDATE_CREDENTIALS;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.BinaryResponseParser;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.junit.Assert;

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

public class CloudRealTimeGetTest extends AbstractFullDistribZkTestBase {

  private String mycollection = "mycollection";
  private String i1="a_si";
  
  public CloudRealTimeGetTest() {
    fixShardCount(4);
    sliceCount = 2;
  }
  
  public void test() throws Exception {
    CloudSolrClient client = createCloudClient(mycollection);
    client.connect();
    
    try {
      int numLiveNodes = getCommonCloudSolrClient().getZkStateReader().getClusterState().getLiveNodes().size();
      
      Assert.assertTrue("This test has to run in a setup with at least two solr-nodes", numLiveNodes >= 2);
      
      int numShards = numLiveNodes * 2;
      Assert.assertTrue("For this test it is important that there are at least two shards on each solr-node", numShards >= (numLiveNodes * 2));
      int replicationFactor = 1;
      int maxShardsPerNode = 4;
      createCollection(null, mycollection, numShards, replicationFactor, maxShardsPerNode, client, null);
      
      ZkStateReader zkStateReader = client.getZkStateReader();
      // make sure we have leaders for each shard
      for (int j = 1; j < numShards; j++) {
        zkStateReader.getLeaderRetry(mycollection, "shard" + j, 10000);
      }
      
      waitForRecoveriesToFinish(mycollection, false);
  
      for (int i = 0; i < 100; i++) {
        SolrInputDocument doc = new SolrInputDocument();
        addFields(doc, id, i, i1, i);
        client.add(doc, -1, UPDATE_CREDENTIALS);
      }
      
      for (int i = 0; i < 100; i++) {
        final SolrQuery query = new SolrQuery();
        ModifiableSolrParams commonSolrParams = new ModifiableSolrParams();
        commonSolrParams.set(CoreAdminParams.COLLECTION, mycollection);
        query.add(commonSolrParams);
        query.set(CommonParams.QT, "/get");
        query.set(id, i);
        query.set("distrib", "false");
        query.setIncludeScore(false);
        query.setTerms(false);
        QueryRequest req = new QueryRequest(query);
        req.setAuthCredentials(SEARCH_CREDENTIALS);
        req.setResponseParser(new BinaryResponseParser());
        QueryResponse rsp = req.process(client);
        SolrDocument out = (SolrDocument) rsp.getResponse().get("doc");
        Assert.assertNotNull(out);
        Assert.assertEquals(i, out.getFieldValue(i1));
      }
    } finally {
      client.close();
    }
  }
}
