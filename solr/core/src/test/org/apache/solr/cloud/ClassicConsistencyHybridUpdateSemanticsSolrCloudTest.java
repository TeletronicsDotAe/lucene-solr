package org.apache.solr.cloud;

import static org.apache.solr.client.solrj.embedded.JettySolrRunner.SEARCH_CREDENTIALS;
import static org.apache.solr.client.solrj.embedded.JettySolrRunner.UPDATE_CREDENTIALS;

import java.util.ArrayList;
import java.util.List;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.exceptions.PartialErrors;
import org.apache.solr.common.exceptions.update.DocumentAlreadyExists;
import org.apache.solr.common.exceptions.update.VersionConflict;
import org.apache.solr.common.util.NamedList;
import org.junit.Test;

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

public class ClassicConsistencyHybridUpdateSemanticsSolrCloudTest extends AbstractFullDistribZkTestBase {

  public ClassicConsistencyHybridUpdateSemanticsSolrCloudTest() {
    super();
    fixShardCount(4);
    sliceCount = 2;
    configFile = "solrconfig-classic-consistency-hybrid-semantics.xml";
    schemaFile = "schema15.xml";
  }
  
  @Test
  public void test() throws Exception {
    handle.clear();
    handle.put("QTime", SKIPVAL);
    handle.put("timestamp", SKIPVAL);
    
    waitForRecoveriesToFinish(false);
    assertEquals(0, cloudClient.query(new SolrQuery("*:*"), SEARCH_CREDENTIALS).getResults().getNumFound());
    
    int DOC_COUNT = 50;
    
    // Prepare 1/3 of the docs and put them in docsForAlreadyExists list
    List<SolrInputDocument> docsForAlreadyExists = new ArrayList<SolrInputDocument>(); 
    for (int i = 0; i < DOC_COUNT/3; i++) {
      // Setting _version_ negative means "insert" (requires document to not already exist)
      SolrInputDocument doc = new SolrInputDocument();
      doc.addField("id", i);
      doc.addField(SolrInputDocument.VERSION_FIELD, -1);
      docsForAlreadyExists.add(doc);
    }
    
    // Prepare another 1/3 of the docs and put them in docsForVersionConflict list
    List<SolrInputDocument> docsForVersionConflict = new ArrayList<SolrInputDocument>();
    for (int i = DOC_COUNT/3; i < (DOC_COUNT*2)/3; i++) {
      // Setting _version_ negative means "insert" (requires document to not already exist)
      SolrInputDocument doc = new SolrInputDocument();
      doc.addField("id", i);
      doc.addField(SolrInputDocument.VERSION_FIELD, -1);
      docsForVersionConflict.add(doc);
    }
    
    // Prepare the last 1/3 of the docs and put them in docsForSuccessCreate list
    List<SolrInputDocument> docsForSuccessCreate = new ArrayList<SolrInputDocument>(); 
    for (int i = (DOC_COUNT*2)/3; i < DOC_COUNT; i++) {
      // Setting _version_ negative means "insert" (requires document to not already exist)
      SolrInputDocument doc = new SolrInputDocument();
      doc.addField("id", i);
      doc.addField(SolrInputDocument.VERSION_FIELD, -1);
      docsForSuccessCreate.add(doc);
    }

    boolean failedWithPartialErrors = false;
    StringBuffer errorList = new StringBuffer();

    // Insert the first 2/3 of the docs into Solr
    List<SolrInputDocument> docs = new ArrayList<SolrInputDocument>();
    docs.addAll(docsForAlreadyExists);
    docs.addAll(docsForVersionConflict);
    try {
      cloudClient.add(null, docs, -1, UPDATE_CREDENTIALS);
    } catch (PartialErrors e) {
      failedWithPartialErrors = true;
      UpdateResponse response = (UpdateResponse)e.getSpecializedResponse();
      for (SolrInputDocument doc : docsForAlreadyExists) {
        SolrException pe = response.getPartialError(doc);
        if (pe != null) errorList.append("Failed for " + doc.getFieldValue("id") + " with " + pe + "\n");
      }
    }

    // Should not result in any partial error (or any other error for that matter)
    assertFalse("Inserting " + (DOC_COUNT*2)/3 + " in empty Solr failed\n" + errorList.toString(), failedWithPartialErrors);
    
    cloudClient.commit(UPDATE_CREDENTIALS);
    
    QueryResponse resp = cloudClient.query(new SolrQuery("*:*").set("rows", (DOC_COUNT*2)/3), SEARCH_CREDENTIALS);
    assertEquals((DOC_COUNT*2)/3, resp.getResults().getNumFound());

    // Prepare all docs that we want to fail with VersionConflict to have a "real wrong" version number
    for (int i = 0; i < resp.getResults().getNumFound(); i++) {
      int id = (Integer)resp.getResults().get(i).getFieldValue("id"); 
      if ((id >= DOC_COUNT/3) && (id < (DOC_COUNT*2)/3)) {
        long currentVersion = (Long)resp.getResults().get(i).getFieldValue("_version_");
        docsForVersionConflict.get(id - DOC_COUNT/3).setField("_version_", currentVersion + 1);
      }
    }
    
    // Try to insert all docs
    docs = new ArrayList<SolrInputDocument>(DOC_COUNT);
    docs.addAll(docsForAlreadyExists);
    docs.addAll(docsForVersionConflict);
    docs.addAll(docsForSuccessCreate);
    UpdateResponse response;
    try {
      response = cloudClient.add(null, docs, -1, UPDATE_CREDENTIALS);
    } catch (PartialErrors e) {
      failedWithPartialErrors = true;
      response = (UpdateResponse)e.getSpecializedResponse();
      int expectedNoOfPartialErrors = docsForAlreadyExists.size() + docsForVersionConflict.size();
      int expectedNoOfHandledParts = expectedNoOfPartialErrors + docsForSuccessCreate.size();
      assertGenericPartialErrorsPayload(e, expectedNoOfPartialErrors, expectedNoOfHandledParts);
    }
    
    errorList = new StringBuffer("Excepted to fail with DocumentAlreadyExists for docs with id: ");
    boolean first = true;
    for (SolrInputDocument doc : docsForAlreadyExists) {
      if (!first) errorList.append(", ");
      else first = false;
      errorList.append(doc.getFieldValue("id"));
    }
    errorList.append("\nExcepted to fail with VersionConflict for docs with id: ");
    first = true;
    for (SolrInputDocument doc : docsForVersionConflict) {
      if (!first) errorList.append(", ");
      else first = false;
      errorList.append(doc.getFieldValue("id"));
    }
    errorList.append("\nExcepted not to fail for docs with id: ");
    first = true;
    for (SolrInputDocument doc : docsForSuccessCreate) {
      if (!first) errorList.append(", ");
      else first = false;
      errorList.append(doc.getFieldValue("id"));
    }
    errorList.append("\nBut failed with the following errors:\n");
    for (SolrInputDocument doc : docs) {
      SolrException pe;
      if ((pe = response.getPartialError(doc)) != null) {
        errorList.append("Failed for " + doc.getFieldValue("id") + " with " + pe + "\n");
      }
    }

    List<String> handledPartsRef = response.getHandledPartsRef();
    assertTrue(errorList.toString(), failedWithPartialErrors);
    // Expected to fail for the first 1/3 of the docs already existing with an DocumentAlreadyExists
    for (SolrInputDocument doc : docsForAlreadyExists) {
      SolrException pe = response.getPartialError(doc);
      assertNotNull(errorList.toString(), pe);
      assertEquals(errorList.toString(), DocumentAlreadyExists.class, pe.getClass());
      assertTrue(handledPartsRef + " does not contain " + doc.getUniquePartRef(), handledPartsRef.contains(doc.getUniquePartRef()));
    }
    // Expected to fail for the second 1/3 of the docs with an VersionConflict
    for (SolrInputDocument doc : docsForVersionConflict) {
      SolrException pe = response.getPartialError(doc);
      assertNotNull(errorList.toString(), pe);
      assertEquals(errorList.toString(), VersionConflict.class, pe.getClass());
      // We added 1 to the current version-number in the request we sent, so the actual version-number in Solr
      // ought to be "what we sent minus 1"
      // Would be nice if it also included responseHeader
      assertVersionConflict((VersionConflict)pe, ((Long)doc.getFieldValue("_version_"))-1, doc.getUniquePartRef(), false);
      assertTrue(handledPartsRef + " does not contain " + doc.getUniquePartRef(), handledPartsRef.contains(doc.getUniquePartRef()));
    }
    // ... no errors for the last 1/3
    for (SolrInputDocument doc : docsForSuccessCreate) {
      SolrException pe = response.getPartialError(doc);
      assertNull(errorList.toString(), pe);
      assertTrue(handledPartsRef + " does not contain " + doc.getUniquePartRef(), handledPartsRef.contains(doc.getUniquePartRef()));
    }
    
    cloudClient.commit(UPDATE_CREDENTIALS);
    
    resp = cloudClient.query(new SolrQuery("*:*"), SEARCH_CREDENTIALS);
    assertEquals(DOC_COUNT, resp.getResults().getNumFound());
    
    // Finally try to update again with several docs, but where only one ought to end in version-conflict error
    first = true;
    SolrInputDocument failDoc = null;
    // Correct version-number for all docsForVersionConflict except one
    for (SolrInputDocument doc : docsForVersionConflict) {
      if (!first) {
        doc.setField("_version_", ((Long)doc.getFieldValue("_version_"))-1);
      } else {
        failDoc = doc;
        first = false;
      }
    }
    failedWithPartialErrors = false;
    try {
      cloudClient.add(null, docsForVersionConflict, -1, UPDATE_CREDENTIALS);
    } catch (PartialErrors pas) {
      failedWithPartialErrors = true;
      
      // Assert on pas
      assertGenericPartialErrorsPayload(pas, 1, docsForVersionConflict.size());
      NamedList<Object> innerPayload = ((List<NamedList<Object>>)pas.getPayload().get("partialerrors")).get(0);
      assertNotNull(innerPayload.get("properties"));
      
      response = (UpdateResponse)pas.getSpecializedResponse();

      errorList = new StringBuffer("Should fail for " + failDoc.getFieldValue("id") + " only with VersionConflict, but failed with the following errors:\n");
      boolean failed = false;
      for (SolrInputDocument doc : docsForVersionConflict) {
        if (doc != failDoc) {
          SolrException pe = response.getPartialError(doc);
          if (pe != null) {
            failed = true;
            errorList.append("Failed for " + doc.getFieldValue("id") + " with " + pe + "\n");
          }
        } else {
          SolrException pe = response.getPartialError(failDoc);
          if (pe == null) {
            failed = true;
          } else {
            errorList.append("Failed for " + doc.getFieldValue("id") + " with " + pe + "\n");
            assertEquals(errorList.toString(), VersionConflict.class, pe.getClass());
            assertVersionConflict((VersionConflict)pe, ((Long)failDoc.getFieldValue("_version_"))-1, failDoc.getUniquePartRef(), false);
          }
        }
      }
      assertFalse(errorList.toString(), failed);
      
      // Assert on pas again, to verify that nothing on that has been changed by extracting the particular PartialErrors from it
      assertGenericPartialErrorsPayload(pas, 1, docsForVersionConflict.size());
      innerPayload = ((List<NamedList<Object>>)pas.getPayload().get("partialerrors")).get(0);
      assertNotNull(innerPayload.get("properties"));
    }
    assertTrue("Ought to fail with VersionConflict for doc with id " + failDoc.getFieldValue("id") + " only", failedWithPartialErrors);
    
    // Finally try to update again with one doc that ought to end in version-conflict error
    boolean failedWithVersionConflict = false;
    try {
      cloudClient.add(failDoc, -1, UPDATE_CREDENTIALS);
    } catch (VersionConflict vc) {
      failedWithVersionConflict = true;
      assertVersionConflict(vc, ((Long)failDoc.getFieldValue("_version_"))-1, null, true);
    }
    assertTrue("Ought to fail with VersionConflict for doc with id " + failDoc.getFieldValue("id") + " only", failedWithVersionConflict);
  }
  
}
