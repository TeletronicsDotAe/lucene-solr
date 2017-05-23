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

package org.apache.solr.update;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;

import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.exceptions.update.DocumentAlreadyExists;
import org.apache.solr.common.exceptions.update.DocumentDoesNotExist;
import org.apache.solr.common.exceptions.update.VersionConflict;
import org.apache.solr.common.params.CommonParams;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class ClassicConsistencyHybridUpdateSemanticsTest extends
    SolrTestCaseJ4 {
  
  private static final String WORLD = "WORLD";
  private static final String HELLO = "HELLO";
  // TODO: fix this test to not require FSDirectory
  static String savedFactory;
  
  @BeforeClass
  public static void beforeClass() throws Exception {
    savedFactory = System.getProperty("solr.DirectoryFactory");
    System.setProperty("solr.directoryFactory",
        "org.apache.solr.core.MockFSDirectoryFactory");
    initCore("solrconfig-classic-consistency-hybrid-semantics.xml", "schema15.xml");
  }
  
  @AfterClass
  public static void afterClass() throws Exception {
    if (savedFactory == null) {
      System.clearProperty("solr.directoryFactory");
    } else {
      System.setProperty("solr.directoryFactory", savedFactory);
    }
  }
  
  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();
    clearIndex();
    assertU(commit());
  }
  
  protected SolrException assertUpdateSemanticsException(String add, Class<? extends SolrException> expectedSE) {
    SolrException se = assertFailedU(add);
    assertNotNull(se);
    assertEquals(expectedSE, se.getClass());
    return se;
  }

  @Test
  public void testRequireUniqueKey() throws Exception {
    // Add a valid document
    assertU(adoc("id", "1"));

    // More than one id should fail
    assertFailedU(adoc("id", "2", "id", "ignore_exception", "text", "foo"));

    // No id should fail
    ignoreException("id");
    assertFailedU(adoc("text", "foo"));
    resetExceptionIgnores();
  }

  @Test
  public void testBasics() throws Exception {
    assertU(adoc("id", "5"));

    // search - not committed - "5" should not be found.
    assertQ(req("q", "id:5"), "//*[@numFound='0']");

    assertU(commit());

    // now it should be there
    assertQ(req("q", "id:5"), "//*[@numFound='1']");

    // now delete it
    assertU(delI("5"));

    // not committed yet
    assertQ(req("q", "id:5"), "//*[@numFound='1']");

    assertU(commit());

    // should be gone
    assertQ(req("q", "id:5"), "//*[@numFound='0']");

  }

  // GIVEN empty db 
  // WHEN inserting doc ID = A 
  // THEN no error and doc ID = A exists
  @Test
  public void testBasicUpdateSemanticsDBInsertShouldSucceed() throws Exception {
    // Setting _version_ negative means "insert" (requires document to not already exist)
    XmlDoc d = doc("id", "A", VersionInfo.VERSION_FIELD, "-1");
    String add = add(d);
    assertU(add);

    assertU(commit());

    assertQ("\"A\" should be found once.", req(CommonParams.Q, "id:A", "indent", "true"), "//*[@numFound='1']",
        "//result/doc[1]/str[@name='id'][.='A']");
  }

  // GIVEN doc ID = A exists in db 
  // WHEN inserting doc ID = A 
  // THEN error: DocumentAlreadyExists
  @Test
  public void testBasicUpdateSemanticsDBInsertShouldFail() throws Exception {
    assertU(adoc("id", "A"));
    
    // Setting _version_ negative means "insert" (requires document to not already exist)
    XmlDoc d = doc("id", "A", VersionInfo.VERSION_FIELD, "-1");
    String add = add(d);
    assertUpdateSemanticsException(add, DocumentAlreadyExists.class);

    assertU(commit());

    assertQ("\"A\" should be found once.", req(CommonParams.Q, "id:A", "indent", "true"), "//*[@numFound='1']",
        "//result/doc[1]/str[@name='id'][.='A']");
  }

  // GIVEN doc ID = A exists in db 
  // WHEN deleting doc A before inserting doc ID = A again
  // THEN no error
  @Test
  public void testBasicUpdateSemanticsDBInsertDeleteBefore() throws Exception {
    assertU(adoc("id", "A"));
    assertU(delI("A"));
    
    // Setting _version_ negative means "insert" (requires document to not already exist)
    XmlDoc d = doc("id", "A", VersionInfo.VERSION_FIELD, "-100");
    String add = add(d);
    assertU(add);

    assertU(commit());

    assertQ("\"A\" should be found once.", req(CommonParams.Q, "id:A", "indent", "true"), "//*[@numFound='1']",
        "//result/doc[1]/str[@name='id'][.='A']");
  }

  // GIVEN doc ID = A exists in db and comitted 
  // WHEN inserting doc ID = A
  // THEN error: DocumentAlreadyExists
  @Test
  public void testBasicUpdateSemanticsDBInsertShouldFailWithCommit()
      throws Exception {
    assertU(adoc("id", "A"));
    
    assertU(commit());
    
    // Setting _version_ negative means "insert" (requires document to not already exist)
    XmlDoc d = doc("id", "A", VersionInfo.VERSION_FIELD, "-10000");
    String add = add(d);
    assertUpdateSemanticsException(add, DocumentAlreadyExists.class);

    assertU(commit());

    assertQ("\"A\" should be found once.", req(CommonParams.Q, "id:A", "indent", "true"), "//*[@numFound='1']",
        "//result/doc[1]/str[@name='id'][.='A']");
  }

  // GIVEN doc ID = A exists in db and committing, deleting doc A again (no commit now) 
  // WHEN inserting doc ID = A
  // THEN no error
  @Test
  public void testBasicUpdateSemanticsDBInsertDeleteCommitBefore()
      throws Exception {
    assertU(adoc("id", "A"));
    
    assertU(commit());
    
    assertU(delI("A"));

    // Setting _version_ negative means "insert" (requires document to not already exist)
    XmlDoc d = doc("id", "A", VersionInfo.VERSION_FIELD, "-20");
    String add = add(d);
    assertU(add);

    assertU(commit());

    assertQ("\"A\" should be found once.", req(CommonParams.Q, "id:A", "indent", "true"), "//*[@numFound='1']",
        "//result/doc[1]/str[@name='id'][.='A']");
  }
  
  // GIVEN doc ID = A exists in db
  // WHEN classic adding (_version_=0) doc ID = A
  // THEN no error
  @Test
  public void shouldDoSimpleUpdateNoCommit() throws Exception {
    XmlDoc initialDoc = doc("id", "5", "text", HELLO);
    
    assertU(add(initialDoc));
    
    XmlDoc doc = doc("id", "5", "text", WORLD, VersionInfo.VERSION_FIELD, "0");
    assertU(add(doc));
    
    assertU(commit());
    assertQ(req("q", "id:5 AND text:" + WORLD), "//*[@numFound='1']");
  }
  
  // GIVEN doc ID = A exists in db and comitting
  // WHEN classic adding (_version_=0) doc ID = A
  // THEN no error
  @Test
  public void shouldDoSimpleUpdateWithCommit() throws Exception {
    XmlDoc initialDoc = doc("id", "5", "text", HELLO);
    
    assertU(add(initialDoc));
    assertU(commit());
    
    XmlDoc doc = doc("id", "5", "text", WORLD, VersionInfo.VERSION_FIELD, "0");
    assertU(add(doc));
    
    assertU(commit());
    assertQ(req("q", "id:5 AND text:" + WORLD), "//*[@numFound='1']");
  }
  
  // GIVEN doc ID = A exists in db with _version_=X
  // WHEN updating (doc existence and version check, because _version_ is positive) doc ID = A with _version_=X
  // THEN no error
  @Test
  public void shouldDoSimpleUpdateSunshineScenario() throws Exception {
    SolrInputDocument document = new SolrInputDocument();
    document.addField("id", "A");
    
    Long version = addAndGetVersion(document, null);
    
    assertU(commit());
    String versionString = version.toString();
    
    assertQ(req("q", "id:A AND _version_:" + versionString),
        "//*[@numFound='1']");
    
    XmlDoc doc = doc("id", "A", "text", WORLD, VersionInfo.VERSION_FIELD, versionString);
    assertU(add(doc));
    
    assertU(commit());
    assertQ(req("q", "id:A AND _version_:" + versionString),
        "//*[@numFound='0']");
  }
  
  // GIVEN empty db
  // WHEN updating (doc existence and version check, because _version_ is positive) doc ID = A with _version_=100
  // THEN error: DocumentDoesNotExist
  @Test
  public void shouldFailOnMissingDocument() throws Exception {
    XmlDoc doc = doc("id", "A", "text", WORLD, VersionInfo.VERSION_FIELD, "100");
    boolean failedAsExpected = false;
    
    try {
      assertU(add(doc));
    } catch (DocumentDoesNotExist e) {
    	failedAsExpected = true;
    }
    
    assertTrue("Did not receive expected DocumentDoesNotExistError exception",
        failedAsExpected);
    assertU(commit());
    assertQ(req("q", "id:A"), "//*[@numFound='0']");
  }

  // GIVEN doc ID = A exists in db
  // WHEN deleting doc A before updating (doc existence and version check, because _version_ is positive) doc ID = A with _version_=10000
  // THEN error: DocumentDoesNotExist
  @Test
  public void shouldFailOnDeletedDocumentNoCommit() throws Exception {
    XmlDoc initialDoc = doc("id", "A", "text", HELLO);
    
    assertU(add(initialDoc));
    assertU(delI("A"));
    
    XmlDoc doc = doc("id", "A", "text", WORLD, VersionInfo.VERSION_FIELD, "10000");
    
    boolean failedAsExpected = false;
    
    try {
      assertU(add(doc));
    } catch (DocumentDoesNotExist e) {
    	failedAsExpected = true;
    }
    
    assertTrue("Did not receive expected DocumentDoesNotExistError exception",
        failedAsExpected);
    
    assertU(commit());
    assertQ(req("q", "id:A"), "//*[@numFound='0']");
  }
  
  // GIVEN doc ID = A exists in db comitting, deleting doc A again (no commit now)
  // WHEN updating (doc existence and version check, because _version_ is positive) doc ID = A with _version_=1
  // THEN error: DocumentDoesNotExist
  @Test
  public void shouldFailOnDeletedDocumentWithCommit() throws Exception {
    XmlDoc initialDoc = doc("id", "A", "text", HELLO);
    
    assertU(add(initialDoc));
    assertU(commit());
    assertU(delI("A"));
    
    XmlDoc doc = doc("id", "A", "text", WORLD, VersionInfo.VERSION_FIELD, "1");
    
    boolean failedAsExpected = false;
    
    try {
      assertU(add(doc));
    } catch (DocumentDoesNotExist e) {
    	failedAsExpected = true;
    }
    
    assertTrue("Did not receive expected DocumentDoesNotExistError exception",
        failedAsExpected);
    
    assertU(commit());
    assertQ(req("q", "id:A"), "//*[@numFound='0']");
  }
  
  // GIVEN doc ID = A exists in db with _version_=X
  // WHEN updating (doc existence and version check, because _version_ is positive) doc ID = A with _version_!=X (X+1)
  // THEN error: VersionConflict
  @Test
  public void shouldFailWhenVersionDiffersFromExpectedNoCommit()
      throws Exception {
    
    SolrInputDocument document = new SolrInputDocument();
    document.addField("id", "A");
    
    Long version = addAndGetVersion(document, null);
    
    XmlDoc doc = doc("id", "A", "text", WORLD, VersionInfo.VERSION_FIELD, new Long(version + 1).toString());
    
    boolean failedAsExpected = false;
    
    try {
      assertU(add(doc));
    } catch (VersionConflict e) {
    	failedAsExpected = true;
    	assertVersionConflict(e, version);
    }
    
    assertTrue("Did not receive expected VersionConflict exception",
        failedAsExpected);
    
    assertU(commit());
    assertQ(req("q", "id:A AND _version_:" + version.toString()), "//*[@numFound='1']");
  }
  
  // GIVEN doc ID = A exists in db with _version_=X and comitting
  // WHEN updating (doc existence and version check, because _version_ is positive) doc ID = A with _version_!=X (X+1)
  // THEN error: VersionConflict
  @Test
  public void shouldFailWhenVersionDiffersFromExpectedWithCommit()
      throws Exception {
    
    SolrInputDocument document = new SolrInputDocument();
    document.addField("id", "A");
    
    Long version = addAndGetVersion(document, null);
    assertU(commit());
    
    XmlDoc doc = doc("id", "A", "text", WORLD, VersionInfo.VERSION_FIELD, new Long(version + 1).toString());
    
    boolean failedAsExpected = false;
    
    try {
      String update = add(doc);
      assertU(update);
    } catch (VersionConflict e) {
    	failedAsExpected = true;
    	// partRef expected (assigned on server-side) but unknown that it ought to be (not explicitly added to request)
      assertVersionConflict(e, version, true, null, false);
    }
    
    assertTrue("Did not receive expected VersionConflict exception",
        failedAsExpected);
    
    assertU(commit());
    assertQ(req("q", "id:A AND _version_:" + version), "//*[@numFound='1']");
  }
  
  // GIVEN doc ID = A exists in db
  // WHEN doc ID = A retrieved from database
  // AND new doc ID = A with same version saved to database
  // THEN no error
  @Test
  public void shouldDoUpdateWhenUsingVersionRetrievedFromQueryResult()
      throws Exception {
    SolrInputDocument document = new SolrInputDocument();
    document.addField("id", "A");
    document.addField("text", HELLO);
    
    addAndGetVersion(document, null);
    assertU(commit());
    
    String result = h.query(req("q", "id:A"));
    
    String version = extractVersionStringFromQueryResult(result);
    XmlDoc doc = doc("id", "A", "text", WORLD, VersionInfo.VERSION_FIELD, version.toString());
    
    assertU(add(doc));
    
    assertU(commit());
    assertQ(req("q", "id:A AND text:" + WORLD), "//*[@numFound='1']");
  }
  
  // GIVEN doc ID = A exists in the db (committed)
  // AND the version of the doc ID = A is well known
  // WHEN inserting doc ID = B
  // THEN doc ID = A has the same version
  @Test
  public void updatesShouldNotChangeVersionOfOtherDocuments() throws Exception {
    SolrInputDocument document = new SolrInputDocument();
    document.addField("id", "A");
    
    Long wellknownVersion = addAndGetVersion(document, null);
    assertU(commit());
    
    assertU(add(doc("id", "B")));
    assertU(commit());
    
    String result = h.query(req("q", "id:A"));
    String versionAfterNewInsert = extractVersionStringFromQueryResult(result);
    
    assertEquals(wellknownVersion.toString(), versionAfterNewInsert);
  }
  
  private String extractVersionStringFromQueryResult(String result)
      throws XPathExpressionException, ParserConfigurationException,
      SAXException, IOException {
    
    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document doc = dBuilder.parse(new InputSource(new java.io.StringReader(result)));
    
    // find a doc with the id A, return the inner text of the version tag
    String xpath = "//doc[str[@name=\"id\"] and str=\"A\"]/long[@name=\"_version_\"]/text()";
    XPathExpression expression = XPathFactory.newInstance().newXPath()
        .compile(xpath);
    return expression.evaluate(doc);
  }
  
}
