package org.apache.solr;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.BaseCompositeReader;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReader;
import org.apache.solr.search.SolrCache;
import org.apache.solr.search.SolrIndexSearcher;

import static org.apache.solr.search.LeafReaderTestWrappers.*;

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

/**
 * Generic wrapper of the {@link LeafReader}s of a {@link SolrIndexSearcher}. Wrappers can have different purposes depending
 * on the test. @see LeafReaderTestWrappers for a set of wrappers
 */
public class SolrIndexSearcherTestWrapper<LEAF_READER_WRAPPER extends BaseWrapper> {
  
  private DirectoryReader dr;
  private LeafReader[] orgSubReader;
  private Field subReadersField;
  private List<LEAF_READER_WRAPPER> newSubReaders;
  
  /**
   * Method passed from outside. Used to produce the wrappers. Method passes in interface - grrr - be need lambdas
   */
  public static interface WrapperFactory<LEAF_READER_WRAPPER extends BaseWrapper> {
    /**
     * Create wrappers of one particular {@link LeafReader}
     * @param leafReader The {@link LeafReader} to wrap
     * @return The created wrapper
     */
    LEAF_READER_WRAPPER createInstance(LeafReader leafReader);
  }

  /**
   * The {@link LeafReader}s of the provided {@link SolrIndexSearcher} will be wrapped. Remember to call {@link #unwrap()}
   * before you finish using this instance
   * 
   * @param searcher The {@link SolrIndexSearcher} that will have its {@link LeafReader}s wrapped 
   * @param wrapperFactory Used to create wrappers of one particular {@link LeafReader}
   */
  public SolrIndexSearcherTestWrapper(SolrIndexSearcher searcher, WrapperFactory<LEAF_READER_WRAPPER> wrapperFactory) throws Exception {
    Field solrIndexSearcherDocumentCache = SolrIndexSearcher.class.getDeclaredField("documentCache");
    solrIndexSearcherDocumentCache.setAccessible(true);
    
    subReadersField = BaseCompositeReader.class.getDeclaredField("subReaders");
    subReadersField.setAccessible(true);
    
    Field solrIndexSearcherReader = SolrIndexSearcher.class.getDeclaredField("reader");
    solrIndexSearcherReader.setAccessible(true);
    
    try {
      // Clear cache so that we get the real counts without help from cache
      ((SolrCache<Integer,Document>)solrIndexSearcherDocumentCache.get(searcher)).clear();

      // Wrapping the LeafReaders underneath the SolrIndexSearcher with a
      // counting wrapper, so that we can assert on how many calls have been made to them
      dr = (DirectoryReader) solrIndexSearcherReader.get(searcher);
      orgSubReader = (LeafReader[]) subReadersField.get(dr);
      newSubReaders = new ArrayList<LEAF_READER_WRAPPER>(orgSubReader.length);
      for (int i = 0; i < orgSubReader.length; i++) {
        newSubReaders.add(wrapperFactory.createInstance(orgSubReader[i]));
      }
      subReadersField.set(dr, newSubReaders.toArray(new LeafReader[0]));
    } catch (Exception e) {
      unwrap();
      throw e;
    }
  }
  
  /**
   * Unwraps the {@link LeafReader}s 
   */
  public void unwrap() throws IllegalArgumentException, IllegalAccessException {
    if (dr != null && orgSubReader != null) {
      subReadersField.set(dr, orgSubReader);
    }
  }

  /**
   * @return The list of wrappers. Order is the same as the order of the {@link LeafReader}
   * inside the {@link SolrIndexSearcher}
   */
  public List<LEAF_READER_WRAPPER> getWrappers() {
    return newSubReaders;
  }
  
}
