package org.apache.solr.client.update;

import java.io.IOException;

import org.apache.solr.SolrJettyTestBase;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.exceptions.update.DocumentAlreadyExists;
import org.apache.solr.common.params.UpdateParams;
import org.apache.solr.update.VersionInfo;
import org.junit.Before;
import org.junit.BeforeClass;
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

public class UpdateSemanticsRequestOverrideTest extends SolrJettyTestBase {
  @BeforeClass
  public static void beforeTest() throws Exception {
    // Not necessary to set solr.semantics.mode to anything, because
    // classic-consistency-hybrid is default
    System.setProperty("solr.semantics.mode", "classic");
    createJetty(legacyExampleCollection1SolrHome());
  }
  
  @Before
  public void doBefore() throws IOException, SolrServerException {
    SolrClient solrClient = getSolrClient();
    solrClient.deleteByQuery("*:*");
    solrClient.commit();
  }
  
  // GIVEN configured to classic-mode, contains doc with id "A" 
  // WHEN updating doc ID = A
  // THEN success overriding
  // WHEN updating doc ID = A with consistency-mode on request and _version_ = -1
  // THEN error: DocumentAlreadyExists
  @Test
  public void shouldPropagateDocumentDoesNotExistExceptionCorrectly()
      throws Exception {
    SolrClient solrClient = getSolrClient();
    assertFalse(searchFindsIt("*"));
    
    // Setting _version_ positive means "update" (requires document to already exist)
    SolrInputDocument document = new SolrInputDocument();
    document.addField("id", "A");
    document.addField(VersionInfo.VERSION_FIELD, -1);
    solrClient.add(document);
    
    solrClient.add(document);
    
    boolean failedAsExpected = false;
    try {
      UpdateRequest req = new UpdateRequest();
      req.add(document);
      req.setParam(UpdateParams.REQ_SEMANTICS_MODE, "consistency");
      req.process(solrClient);
    } catch (Exception e) {
      if (e instanceof SolrException) {
        SolrException se = (SolrException) e;
        assertEquals(DocumentAlreadyExists.class.getName(), se.getThrowable());
        failedAsExpected = true;
      }
    }
    assertTrue("Processing of the request did not fail as expected.",
        failedAsExpected);
  }

  private boolean searchFindsIt(String queryString) throws SolrServerException, IOException {
    SolrQuery query = new SolrQuery();
    query.setQuery(queryString);
    QueryResponse rsp = getSolrClient().query(query);
    return rsp.getResults().getNumFound() != 0;
  }
  
}
