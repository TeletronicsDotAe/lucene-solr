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

import org.apache.solr.SolrTestCaseJ4;

// FIXME - Figure put how much of this test is still relevant, after moving to TolerantUpdateProcessor
public class ClassicConsistencyHybridUpdateSemanticsPartialErrorsTest extends SolrTestCaseJ4 {
//  private static final String WORLD = "WORLD";
//  private static final String HELLO = "HELLO";
//
//	// TODO: fix this test to not require FSDirectory
//	static String savedFactory;
//
//	@BeforeClass
//	public static void beforeClass() throws Exception {
//		savedFactory = System.getProperty("solr.DirectoryFactory");
//		System.setProperty("solr.directoryFactory",
//				"org.apache.solr.core.MockFSDirectoryFactory");
//		initCore("solrconfig-classic-consistency-hybrid-semantics.xml", "schema15.xml");
//	}
//
//	@AfterClass
//	public static void afterClass() throws Exception {
//		if (savedFactory == null) {
//			System.clearProperty("solr.directoryFactory");
//		} else {
//			System.setProperty("solr.directoryFactory", savedFactory);
//		}
//	}
//
//	@Override
//	@Before
//	public void setUp() throws Exception {
//		super.setUp();
//		clearIndex();
//		assertU(commit());
//	}
//
//	protected SolrException assertUpdateSemanticsException(String add, Class<? extends SolrException> expectedSE) {
//		SolrException se = assertFailedU(add);
//		assertNotNull(se);
//		assertEquals(expectedSE, se.getClass());
//		return se;
//	}
//
//	// GIVEN empty db
//	// WHEN inserting (_version_ set to negative number) doc ID = A and doc ID = B
//	// THEN no partial errors
//	// AND docs ID = A and ID = B exist in db
//	@Test
//	public void twoDocInsertShouldNotResultInErrorOnEmptyDB() throws Exception {
//		XmlDoc dA = doc(new String[]{"partref", "refA"}, "id", "A", SolrInputDocument.VERSION_FIELD, "-1");
//		XmlDoc dB = doc(new String[]{"partref", "refB"}, "id", "B", SolrInputDocument.VERSION_FIELD, "-1");
//		String add = add(new XmlDoc[]{dA, dB});
//		assertU(add);
//
//    assertU(commit());
//
//		assertQ("\"A\" and \"B\" should be found once.", req(CommonParams.Q, "*:*", "indent", "true"), "//*[@numFound='2']",
//				"//result/*/str[@name='id'][.='A']", "//result/*/str[@name='id'][.='B']");
//	}
//
//	// GIVEN doc ID = A exists in db
//	// WHEN inserting (_version_ set to negative number) doc ID = A and doc ID = B
//	// THEN partial error DocumentAlreadyExists for doc ID = A
//	// AND docs ID = A and ID = B exist in db
//	@Test
//	public void twoDocInsertShouldResultInOnePartialErrorOnDBAlreadyContainingOneOfTheDocs() throws Exception {
//		assertU(adoc("id", "A"));
//
//		XmlDoc dA = doc(new String[]{"partref", "refA"}, "id", "A", SolrInputDocument.VERSION_FIELD, "-1");
//		XmlDoc dB = doc(new String[]{"partref", "refB"}, "id", "B", SolrInputDocument.VERSION_FIELD, "-1");
//		String add = add(new XmlDoc[]{dA, dB});
//		PartialErrors pa = (PartialErrors)assertUpdateSemanticsException(add, PartialErrors.class);
//		assertEquals(DocumentAlreadyExists.class, SolrResponse.getPartialError(null, pa.getPayload(), "refA").getClass());
//		assertNull(SolrResponse.getPartialError(null, pa.getPayload(), "refB"));
//
//		assertU(commit());
//
//		assertQ("\"A\" and \"B\" should be found once.", req(CommonParams.Q, "*:*", "indent", "true"), "//*[@numFound='2']",
//				"//result/*/str[@name='id'][.='A']", "//result/*/str[@name='id'][.='B']");
//	}
//
//	// GIVEN doc ID = A and (_version_=X) doc ID = B (_version_=Y) exists in db
//	// WHEN updating (version check, because setting _version_ to positive number) doc ID = A and doc ID = B, but doc ID = B has
//	// changed since loading (_version=X for doc ID = A and _version_=Y+1 for doc ID = B)
//	// THEN partial fail: VersionConflict for doc ID = B
//	@Test
//	public void twoDocUpdateShouldResultInOnePartialErrorOnDBAlreadyContainingBothDocsButOneDocHasChangedSinceLoad() throws Exception {
//		SolrInputDocument docA = new SolrInputDocument();
//    docA.addField("id", "A");
//    docA.addField("subject", HELLO);
//    Long versionA = addAndGetVersion(docA, null);
//		SolrInputDocument docB = new SolrInputDocument();
//    docB.addField("id", "B");
//    docB.addField("subject", HELLO);
//    Long versionB = addAndGetVersion(docB, null);
//
//		XmlDoc dA = doc(new String[]{"partref", "refA"}, "id", "A", "subject", WORLD, SolrInputDocument.VERSION_FIELD, versionA.toString());
//		XmlDoc dB = doc(new String[]{"partref", "refB"}, "id", "B", "subject", WORLD, SolrInputDocument.VERSION_FIELD, new Long(versionB + 1).toString());
//		String add = add(new XmlDoc[]{dA, dB});
//		PartialErrors pa = (PartialErrors)assertUpdateSemanticsException(add, PartialErrors.class);
//		assertGenericPartialErrorsPayload(pa, 1, 2);
//		assertNull(SolrResponse.getPartialError(null, pa.getPayload(), "refA"));
//		SolrException refBError = SolrResponse.getPartialError(null, pa.getPayload(), "refB");
//		assertEquals(VersionConflict.class, refBError.getClass());
//		assertVersionConflict((VersionConflict)refBError, versionB, "refB", false);
//
//		assertU(commit());
//
//		assertQ("\"A\" and \"B\" should be found once.", req(CommonParams.Q, "*:*", "indent", "true"), "//*[@numFound='2']",
//				"//result/doc[1]/str[@name='id'][.='B']", "//result/doc[1]/str[@name='subject'][.='" + HELLO + "']",
//				"//result/doc[2]/str[@name='id'][.='A']", "//result/doc[2]/str[@name='subject'][.='" + WORLD + "']");
//	}
//
//	// GIVEN empty db
//	// WHEN updating (_version_ set to positive number) doc ID = A
//	// AND inserting (_version_ set to negative number) doc ID = B
//	// THEN partial fail: DocumentDoesNotExist for doc ID = A
//	@Test
//	public void updateOfNonexistingDocAndInsertOfNonexistingDocShouldResultInOnePartialErrorOnEmptyDB() throws Exception {
//		XmlDoc dA = doc(new String[]{"partref", "refA"}, "id", "A", "subject", WORLD, SolrInputDocument.VERSION_FIELD, "1234");
//		XmlDoc dB = doc(new String[]{"partref", "refB"}, "id", "B", "subject", WORLD, SolrInputDocument.VERSION_FIELD, "-1234");
//		String add = add(new XmlDoc[]{dA, dB});
//		PartialErrors pa = (PartialErrors)assertUpdateSemanticsException(add, PartialErrors.class);
//		assertGenericPartialErrorsPayload(pa, 1, 2);
//		assertEquals(DocumentDoesNotExist.class, SolrResponse.getPartialError(null, pa.getPayload(), "refA").getClass());
//		assertNull(SolrResponse.getPartialError(null, pa.getPayload(), "refB"));
//
//		assertU(commit());
//
//		assertQ("\"A\" and \"B\" should be found once.", req(CommonParams.Q, "*:*", "indent", "true"), "//*[@numFound='1']",
//				"//result/doc[1]/str[@name='id'][.='B']", "//result/doc[1]/str[@name='subject'][.='" + WORLD + "']");
//	}
//
//	// GIVEN doc ID = A and doc ID = B (_version_=X) exists in db
//	// WHEN inserting (_version_ negative) doc ID = A
//	// AND updating doc ID = B with wrong version (_version_=Y+1)
//	// AND inserting (_version_ negative) doc ID = C
//	// THEN partial fail: DocumentAlreadyExists for doc ID = A and VersionConflict for doc ID = B
//	// AND docs ID = A, ID = B and ID = C exist in db
//	@Test
//	public void testMultiplePartialErrors() throws Exception {
//		assertU(adoc("id", "A", "subject", HELLO));
//		SolrInputDocument docB = new SolrInputDocument();
//    docB.addField("id", "B");
//    docB.addField("subject", HELLO);
//    Long versionB = addAndGetVersion(docB, null);
//
//		XmlDoc dA = doc(new String[]{"partref", "refA"}, "id", "A", "subject", WORLD, SolrInputDocument.VERSION_FIELD, "-1");
//		XmlDoc dB = doc(new String[]{"partref", "refB"}, "id", "B", "subject", WORLD, SolrInputDocument.VERSION_FIELD, new Long(versionB+1).toString());
//		XmlDoc dC = doc(new String[]{"partref", "refC"}, "id", "C", "subject", WORLD, SolrInputDocument.VERSION_FIELD, "-1");
//		String add = add(new XmlDoc[]{dA, dB, dC});
//		PartialErrors pa = (PartialErrors)assertUpdateSemanticsException(add, PartialErrors.class);
//		assertGenericPartialErrorsPayload(pa, 2, 3);
//		assertEquals(DocumentAlreadyExists.class, SolrResponse.getPartialError(null, pa.getPayload(), "refA").getClass());
//		SolrException refBError = SolrResponse.getPartialError(null, pa.getPayload(), "refB");
//		assertEquals(VersionConflict.class, refBError.getClass());
//		assertVersionConflict((VersionConflict)refBError, versionB, "refB", false);
//		assertNull(SolrResponse.getPartialError(null, pa.getPayload(), "refC"));
//
//		assertU(commit());
//
//		assertQ("\"A\" and \"B\" should be found once.", req(CommonParams.Q, "*:*", "indent", "true"), "//*[@numFound='3']",
//				"//result/doc[1]/str[@name='id'][.='A']", "//result/doc[1]/str[@name='subject'][.='" + HELLO + "']",
//				"//result/doc[2]/str[@name='id'][.='B']", "//result/doc[2]/str[@name='subject'][.='" + HELLO + "']",
//				"//result/doc[3]/str[@name='id'][.='C']", "//result/doc[3]/str[@name='subject'][.='" + WORLD + "']");
//	}
//
//	// GIVEN doc ID = A and doc ID = B (_version_=X) exists in db
//	// WHEN inserting (_version_ negative) doc ID = A
//	// AND updating doc ID = B with wrong version (_version_=Y+1)
//	// AND updating (_version_ positive) doc ID = C
//	// THEN partial fail: DocumentAlreadyExists for doc ID = A and VersionConflict for doc ID = B and DocumentDoesNotExist for doc ID = C
//	// AND docs ID = A and ID = B exist in db
//	@Test
//	public void testAllPartialErrors() throws Exception {
//		assertU(adoc("id", "A", "subject", HELLO));
//		SolrInputDocument docB = new SolrInputDocument();
//    docB.addField("id", "B");
//    docB.addField("subject", HELLO);
//    Long versionB = addAndGetVersion(docB, null);
//
//		XmlDoc dA = doc(new String[]{"partref", "refA"}, "id", "A", "subject", WORLD, SolrInputDocument.VERSION_FIELD, "-1");
//		XmlDoc dB = doc(new String[]{"partref", "refB"}, "id", "B", "subject", WORLD, SolrInputDocument.VERSION_FIELD, new Long(versionB+1).toString());
//		XmlDoc dC = doc(new String[]{"partref", "refC"}, "id", "C", "subject", WORLD, SolrInputDocument.VERSION_FIELD, "1234");
//		String add = add(new XmlDoc[]{dA, dB, dC});
//		PartialErrors pa = (PartialErrors)assertUpdateSemanticsException(add, PartialErrors.class);
//		assertGenericPartialErrorsPayload(pa, 3, 3);
//		assertEquals(DocumentAlreadyExists.class, SolrResponse.getPartialError(null, pa.getPayload(), "refA").getClass());
//		SolrException refBError = SolrResponse.getPartialError(null, pa.getPayload(), "refB");
//		assertEquals(VersionConflict.class, refBError.getClass());
//		assertVersionConflict((VersionConflict)refBError, versionB, "refB", false);
//		assertEquals(DocumentDoesNotExist.class, SolrResponse.getPartialError(null, pa.getPayload(), "refC").getClass());
//
//		assertU(commit());
//
//		assertQ("\"A\" and \"B\" should be found once.", req(CommonParams.Q, "*:*", "indent", "true"), "//*[@numFound='2']",
//				"//result/doc[1]/str[@name='id'][.='A']", "//result/doc[1]/str[@name='subject'][.='" + HELLO + "']",
//				"//result/doc[2]/str[@name='id'][.='B']", "//result/doc[2]/str[@name='subject'][.='" + HELLO + "']");
//	}

}
