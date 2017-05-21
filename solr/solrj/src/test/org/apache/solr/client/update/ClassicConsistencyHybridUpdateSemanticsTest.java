/**
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

package org.apache.solr.client.update;

import org.apache.solr.SolrJettyTestBase;

// FIXME - Figure put how much of this test is still relevant, after moving to TolerantUpdateProcessor
public class ClassicConsistencyHybridUpdateSemanticsTest extends
    SolrJettyTestBase {
  /*
  @BeforeClass
  public static void beforeTest() throws Exception {
    // Not necessary to set solr.semantics.mode to anything, because classic-consistency-hybrid is default
    System.setProperty("solr.semantics.mode", "classic-consistency-hybrid");
    createJetty(legacyExampleCollection1SolrHome());
  }
  
  @Before
  public void doBefore() throws IOException, SolrServerException {
    SolrClient solrClient = getSolrClient();
    solrClient.deleteByQuery("*:*");
    solrClient.commit();
  }
  
  // GIVEN empty db 
  // WHEN inserting doc ID = A 
  // THEN doc ID = A exists and no error
  @Test
  public void shouldSuccessfullyInsertDocumentInSunshineDB_INSERTScenario()
      throws Exception {
    SolrClient solrClient = getSolrClient();
    assertFalse(searchFindsIt("*"));
    
    // Setting _version_ negative means "insert" (requires document to not already exist)
    SolrInputDocument document = new SolrInputDocument();
    document.addField("id", "A");
    document.addField(SolrInputDocument.VERSION_FIELD, -1);
    
    solrClient.add(document);
    solrClient.commit();
    
    assertTrue(searchFindsIt("id:A"));
  }

  // GIVEN empty db 
  // WHEN updating doc ID = A
  // THEN error: DocumentDoesNotExist
  @Test
  public void shouldPropagateDocumentDoesNotExistExceptionCorrectly()
      throws Exception {
    SolrClient solrClient = getSolrClient();
    assertFalse(searchFindsIt("*"));
    
    // Setting _version_ positive means "update" (requires document to already exist)
    SolrInputDocument document = new SolrInputDocument();
    document.addField("id", "A");
    document.addField(SolrInputDocument.VERSION_FIELD, 1234);
    
    boolean failedAsExpected = false;
    try {
      solrClient.add(document);
    } catch (DocumentDoesNotExist e) {
      failedAsExpected = true;
    }
    assertTrue("Processing of the request did not fail as expected.",
        failedAsExpected);
  }
  
  // GIVEN doc ID = A exists in db with _version_=X
  // WHEN updating doc ID = A with _version_!=X (X+1)
  // THEN error: VersionConflict
  @Test
  public void shouldPropagateVersionConflictExceptionCorrectly()
      throws Exception {
    SolrClient solrClient = getSolrClient();
    assertFalse(searchFindsIt("*:*"));
    
    SolrInputDocument document = new SolrInputDocument();
    document.addField("id", "A");
    client.add(document);
    
    SolrQuery q = new SolrQuery();
    q.setRequestHandler("/get");
    q.set("id", "A");
    QueryRequest req = new QueryRequest( q );
    req.setResponseParser(new XMLResponseParser());
    QueryResponse rsp = req.process(client);
    SolrDocument out = (SolrDocument)rsp.getResponse().get("doc");
    Long version = (Long)out.getFieldValue(SolrInputDocument.VERSION_FIELD);
    
    document.addField(SolrInputDocument.VERSION_FIELD, version+1);
    boolean failedAsExpected = false;
    try {
      solrClient.add(document);
    } catch (VersionConflict e) {
      failedAsExpected = true;
      assertVersionConflict(e, version.longValue(), null, true);
      assertEquals(version.longValue(), e.getCurrentVersion());
    }
    
    assertTrue("Processing of the request did not fail as expected.",
        failedAsExpected);
  }
  
  // GIVEN doc ID = A exists in db 
  // WHEN inserting doc ID = A and doc ID = B 
  // THEN partial error: DocumentAlreadyExists for doc ID = A
  @Test
  public void shouldHandlePartialErrorInMultidocumentUpdatesCorrectly() throws Exception {
    SolrClient solrClient = getSolrClient();
    assertFalse(searchFindsIt("*:*"));
    
    // Setting _version_ negative means "insert" (requires document to not already exist)
    SolrInputDocument docA = new SolrInputDocument();
    docA.addField("id", "A");
    docA.addField(SolrInputDocument.VERSION_FIELD, -2);
    client.add(docA);

    // Setting _version_ negative means "insert" (requires document to not already exist)
    SolrInputDocument docB = new SolrInputDocument();
    docB.addField("id", "B");
    docB.addField(SolrInputDocument.VERSION_FIELD, -1234);

    boolean failedAsExpected = false;
    UpdateResponse response;
    try {
      response = solrClient.add(Arrays.asList(new SolrInputDocument[]{docA, docB}));
    } catch (PartialErrors pas) {
      failedAsExpected = true;
      assertGenericPartialErrorsPayload(pas, 1, 2);
      response = (UpdateResponse)pas.getSpecializedResponse();
    }
    
    assertTrue("Processing of the request did not fail as expected.", failedAsExpected);
    
    assertEquals(DocumentAlreadyExists.class, response.getPartialError(docA).getClass());
    assertNull(response.getPartialError(docB));
  }
  
  private boolean searchFindsIt(String queryString) throws SolrServerException, IOException {
    SolrQuery query = new SolrQuery();
    query.setQuery(queryString);
    QueryResponse rsp = getSolrClient().query(query);
    return rsp.getResults().getNumFound() != 0;
  }
  */
}

