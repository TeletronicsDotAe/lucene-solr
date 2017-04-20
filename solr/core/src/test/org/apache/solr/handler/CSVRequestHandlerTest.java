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
package org.apache.solr.handler;

import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrInputField;
import org.apache.solr.common.util.ContentStreamBase;
import org.apache.solr.handler.loader.CSVLoader;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.update.AddUpdateCommand;
import org.apache.solr.update.processor.BufferingRequestProcessor;
import org.junit.BeforeClass;
import org.junit.Test;


public class CSVRequestHandlerTest extends SolrTestCaseJ4 {

  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig.xml", "schema.xml");
  }

  @Test
  public void testCommitWithin() throws Exception {
    String csvString = "id;name\n123;hello";
    SolrQueryRequest req = req("separator", ";",
                               "commitWithin", "200");
    SolrQueryResponse rsp = new SolrQueryResponse();
    BufferingRequestProcessor p = new BufferingRequestProcessor(null, req, rsp);

    CSVLoader loader = new CSVLoader();
    loader.load(req, rsp, new ContentStreamBase.StringStream.StringStream(csvString), p);

    AddUpdateCommand add = p.addCommands.get(0);
    assertEquals(200, add.commitWithin);

    req.close();
  }

  protected void fieldsAndPartRefTestTemplate(String csvString) throws Exception {
    SolrQueryRequest req = req("separator", ";");
    SolrQueryResponse rsp = new SolrQueryResponse();
    BufferingRequestProcessor p = new BufferingRequestProcessor(null, req, rsp);

    CSVLoader loader = new CSVLoader();
    loader.load(req, rsp, new ContentStreamBase.StringStream.StringStream(csvString), p);

    assertEquals( 2, p.addCommands.size() );
    
    AddUpdateCommand add = p.addCommands.get(0);
    SolrInputDocument d = add.solrDoc;
    assertEquals("ref1", d.getUniquePartRef());
    SolrInputField f = d.getField( "id" );
    assertEquals("123", f.getValue());
    f = d.getField( "name" );
    assertEquals("hello", f.getValue());

    add = p.addCommands.get(1);
    d = add.solrDoc;
    assertEquals("ref2", d.getUniquePartRef());
    f = d.getField( "id" );
    assertEquals("456", f.getValue());
    f = d.getField( "name" );
    assertEquals("world", f.getValue());

    req.close();
  }

  
  @Test
  public void testFieldsAndPartRefFirst() throws Exception {
    fieldsAndPartRefTestTemplate("nonfield.partref;id;name\nref1;123;hello\nref2;456;world");
  }

  @Test
  public void testFieldsAndPartRefMiddle() throws Exception {
    fieldsAndPartRefTestTemplate("id;nonfield.partref;name\n123;ref1;hello\n456;ref2;world");
  }

  @Test
  public void testFieldsAndPartRefLast() throws Exception {
    fieldsAndPartRefTestTemplate("id;name;nonfield.partref\n123;hello;ref1\n456;world;ref2");
  }

}
