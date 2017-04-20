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
package org.apache.solr.rest.schema.analysis;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.commons.io.FileUtils;
import org.apache.solr.util.RestTestBase;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.noggit.JSONUtil;
import org.restlet.ext.servlet.ServerServlet;

public class TestManagedSynonymFilterFactory extends RestTestBase {
  
  private static File tmpSolrHome;

  /**
   * Setup to make the schema mutable
   */
  @Before
  public void before() throws Exception {
    tmpSolrHome = createTempDir().toFile();
    FileUtils.copyDirectory(new File(TEST_HOME()), tmpSolrHome.getAbsoluteFile());

    final SortedMap<ServletHolder,String> extraServlets = new TreeMap<>();
    final ServletHolder solrRestApi = new ServletHolder("SolrSchemaRestApi", ServerServlet.class);
    solrRestApi.setInitParameter("org.restlet.application", "org.apache.solr.rest.SolrSchemaRestApi");
    extraServlets.put(solrRestApi, "/schema/*");

    System.setProperty("managed.schema.mutable", "true");
    System.setProperty("enable.update.log", "false");
    createJettyAndHarness(tmpSolrHome.getAbsolutePath(), "solrconfig-managed-schema.xml", "schema-rest.xml",
                          "/solr", true, extraServlets);
  }

  @After
  private void after() throws Exception {
    jetty.stop();
    jetty = null;
    FileUtils.deleteDirectory(tmpSolrHome);
    System.clearProperty("managed.schema.mutable");
    System.clearProperty("enable.update.log");
    
    if (restTestHarness != null) {
      restTestHarness.close();
    }
    restTestHarness = null;
  }
  
  @Test
  public void testManagedSynonyms() throws Exception {
    // this endpoint depends on at least one field type containing the following
    // declaration in the schema-rest.xml:
    // 
    //   <filter class="solr.ManagedSynonymFilterFactory" managed="english" />
    //      
    String endpoint = "/schema/analysis/synonyms/english";
    
    assertJQ(endpoint, 
             "/synonymMappings/initArgs/ignoreCase==false",
             "/synonymMappings/managedMap=={}");
      
    // put a new mapping into the synonyms
    Map<String,List<String>> syns = new HashMap<>();
    syns.put("happy", Arrays.asList("glad","cheerful","joyful"));    
    assertJPut(endpoint, 
               JSONUtil.toJSON(syns),
               "/responseHeader/status==0");
    
    assertJQ(endpoint,
        "/synonymMappings/managedMap/happy==['cheerful','glad','joyful']");

    // request to a specific mapping
    assertJQ(endpoint+"/happy", 
             "/happy==['cheerful','glad','joyful']");

    // does not exist
    assertJQ(endpoint+"/sad", 
             404, "sad not found in /schema/analysis/synonyms/english");
    
    // verify the user can update the ignoreCase initArg
    assertJPut(endpoint, 
               json("{ 'initArgs':{ 'ignoreCase':true } }"), 
               "responseHeader/status==0");

    assertJQ(endpoint, 
             "/synonymMappings/initArgs/ignoreCase==true");
    
    syns = new HashMap<>();
    syns.put("sad", Arrays.asList("unhappy"));    
    syns.put("SAD", Arrays.asList("bummed"));    
    assertJPut(endpoint, 
               JSONUtil.toJSON(syns),
               "/responseHeader/status==0");
    
    assertJQ(endpoint, 
             "/synonymMappings/managedMap/sad==['unhappy']");
    assertJQ(endpoint, 
        "/synonymMappings/managedMap/SAD==['bummed']");
    
    // expect a union of values when requesting the "sad" child
    assertJQ(endpoint+"/sad", 
        "/sad==['bummed','unhappy']");
    
    // verify delete works
    assertJDelete(endpoint+"/sad",
                  "/responseHeader/status==0");
    
    assertJQ(endpoint, 
        "/synonymMappings/managedMap=={'happy':['cheerful','glad','joyful']}");
    
    // should fail with 404 as foo doesn't exist
    assertJDelete(endpoint+"/foo",
                  404, "foo not found in /schema/analysis/synonyms/english");
    
    // verify that a newly added synonym gets expanded on the query side after core reload
    
    String newFieldName = "managed_en_field";
    // make sure the new field doesn't already exist
    assertQ("/schema/fields/" + newFieldName + "?indent=on&wt=xml",
            404, "Field '" + newFieldName + "' not found.");

    // add the new field
    assertJPost("/schema", "{ add-field :  { name: managed_en_field, type : managed_en}}",
               "/responseHeader/status==0");

    // make sure the new field exists now
    assertQ("/schema/fields/" + newFieldName + "?indent=on&wt=xml",
            "count(/response/lst[@name='field']) = 1",
            "/response/lst[@name='responseHeader']/int[@name='status'] = '0'");

    assertU(adoc(newFieldName, "I am a happy test today but yesterday I was angry", "id", "5150"));
    assertU(commit());

    assertQ("/select?q=" + newFieldName + ":angry",
            "/response/lst[@name='responseHeader']/int[@name='status'] = '0'",
            "/response/result[@name='response'][@numFound='1']",
            "/response/result[@name='response']/doc/str[@name='id'][.='5150']");    
    
    // add a mapping that will expand a query for "mad" to match docs with "angry"
    syns = new HashMap<>();
    syns.put("mad", Arrays.asList("angry"));    
    assertJPut(endpoint, 
               JSONUtil.toJSON(syns),
               "/responseHeader/status==0");
    
    assertJQ(endpoint, 
        "/synonymMappings/managedMap/mad==['angry']");

    // should not match as the synonym mapping between mad and angry does not    
    // get applied until core reload
    assertQ("/select?q=" + newFieldName + ":mad",
        "/response/lst[@name='responseHeader']/int[@name='status'] = '0'",
        "/response/result[@name='response'][@numFound='0']");    
    
    restTestHarness.reload();

    // now query for mad and we should see our test doc
    assertQ("/select?q=" + newFieldName + ":mad",
        "/response/lst[@name='responseHeader']/int[@name='status'] = '0'",
        "/response/result[@name='response'][@numFound='1']",
        "/response/result[@name='response']/doc/str[@name='id'][.='5150']");    
    
    // test for SOLR-6015
    syns = new HashMap<>();
    syns.put("mb", Arrays.asList("megabyte"));    
    assertJPut(endpoint,
        JSONUtil.toJSON(syns),
        "/responseHeader/status==0");

    syns.put("MB", Arrays.asList("MiB", "Megabyte"));    
    assertJPut(endpoint,
        JSONUtil.toJSON(syns),
        "/responseHeader/status==0");
    
    assertJQ(endpoint + "/MB",
        "/MB==['Megabyte','MiB','megabyte']");

    // test for SOLR-6878 - by default, expand is true, but only applies when sending in a list
    List<String> m2mSyns = new ArrayList<>();
    m2mSyns.addAll(Arrays.asList("funny", "entertaining", "whimiscal", "jocular"));
    assertJPut(endpoint, JSONUtil.toJSON(m2mSyns), "/responseHeader/status==0");

    assertJQ(endpoint + "/funny",
        "/funny==['entertaining','funny','jocular','whimiscal']");
    assertJQ(endpoint + "/entertaining",
        "/entertaining==['entertaining','funny','jocular','whimiscal']");
    assertJQ(endpoint + "/jocular",
        "/jocular==['entertaining','funny','jocular','whimiscal']");
    assertJQ(endpoint + "/whimiscal",
        "/whimiscal==['entertaining','funny','jocular','whimiscal']");
  }

  /**
   * Can we add and remove stopwords with umlauts
   */
  @Test
  public void testCanHandleDecodingAndEncodingForSynonyms() throws Exception  {
    String endpoint = "/schema/analysis/synonyms/german";

    assertJQ(endpoint,
        "/synonymMappings/initArgs/ignoreCase==false",
        "/synonymMappings/managedMap=={}");

    // does not exist
    assertJQ(endpoint+"/fröhlich",
        404, "fröhlich not found in /schema/analysis/synonyms/german");

    Map<String,List<String>> syns = new HashMap<>();

    // now put a synonym
    syns.put("fröhlich", Arrays.asList("glücklick"));
    assertJPut(endpoint,
        JSONUtil.toJSON(syns),
        "/responseHeader/status==0");

    // and check if it exists
    assertJQ(endpoint,
        "/synonymMappings/managedMap/fröhlich==['glücklick']");

    // verify delete works
    assertJDelete(endpoint+"/fröhlich",
        "/responseHeader/status==0");


    // was it really deleted?
    assertJDelete(endpoint+"/fröhlich",
        404, "fröhlich not found in /schema/analysis/synonyms/german");
  }
}
