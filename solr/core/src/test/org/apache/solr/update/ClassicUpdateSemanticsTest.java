package org.apache.solr.update;

import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.UpdateParams;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class ClassicUpdateSemanticsTest extends SolrTestCaseJ4 {

	// TODO: fix this test to not require FSDirectory
	static String savedFactory;

	@BeforeClass
	public static void beforeClass() throws Exception {
		savedFactory = System.getProperty("solr.DirectoryFactory");
		System.setProperty("solr.directoryFactory",
				"org.apache.solr.core.MockFSDirectoryFactory");
		initCore("solrconfig-classic-semantics.xml", "schema15.xml");
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

	/**
	 * GIVEN doc ID = A exists in db 
	 * WHEN updating doc ID = A with negative _version_ 
	 * THEN no error
	 */
	@Test
	public void testBasicClassicUpdateSemanticsDBInsert() throws Exception {
		assertU(adoc("id", "A"));
		
		XmlDoc d = doc("id", "A", SolrInputDocument.VERSION_FIELD, "-1");
		String add = add(d);
		assertU(add);

		assertU(commit());

		assertQ("\"A\" should be found once.", req(CommonParams.Q, "id:A", "indent", "true"), "//*[@numFound='1']",
				"//result/doc[1]/str[@name='id'][.='A']");
	}

	/**
	 * GIVEN doc ID = A exists in db 
	 * WHEN updating doc ID = A with negative _version_ using JSON
	 * THEN no error
	 */
	@Test
	public void testJSONBasicClassicUpdateSemanticsDBInsert() throws Exception {
		assertU(adoc("id", "A"));
		
		updateJ(jsonAdd(sdoc("id", "A", SolrInputDocument.VERSION_FIELD, "-1")), null);

		assertU(commit());

		assertQ("\"A\" should be found once.", req(CommonParams.Q, "id:A", "indent", "true"), "//*[@numFound='1']",
				"//result/doc[1]/str[@name='id'][.='A']");
	}
	
	/**
	 * GIVEN doc ID = A exists in db 
	 * WHEN inserting doc ID = A with positive _version_ and overwrite = true
	 * THEN two docs ID = A should exist
	 */
	@Test
	public void testBasicClassicUpdateDontOverwriteSemanticsDBInsert() throws Exception {
		assertU(adoc("id", "A"));
		
		XmlDoc d = doc("id", "A", SolrInputDocument.VERSION_FIELD, "100");
		String add = add(d, UpdateParams.OVERWRITE, "false");
		assertU(add);

		assertU(commit());

		assertQ("\"A\" should be found twice.", req(CommonParams.Q, "id:A", "indent", "true"), "//*[@numFound='2']",
				"//result/doc[1]/str[@name='id'][.='A']");
	}

}
