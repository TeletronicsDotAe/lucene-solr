package org.apache.solr;

import org.apache.lucene.index.LeafReader;
import org.apache.solr.core.SolrCore;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.util.RefCounted;

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
 * Generic wrapper of the {@link LeafReader}s of the {@link SolrIndexSearcher} of a {@link SolrCore}. Wrappers can
 * have different purposes depending on the test. @see LeafReaderTestWrappers for a set of wrappers
 */
public class SolrCoreTestWrapper<LEAF_READER_WRAPPER extends BaseWrapper> extends SolrIndexSearcherTestWrapper<LEAF_READER_WRAPPER> {
  
  private RefCounted<SolrIndexSearcher> searcher;
  
  /**
   * The {@link LeafReader}s of the {@link SolrIndexSearcher} of the provided {@link SolrCore} will be wrapped. 
   * Remember to call {@link #unwrap()} before you finish using this instance
   * 
   * @param solrCore The {@link SolrCore} that holding the {@link SolrIndexSearcher} that will have
   * its {@link LeafReader}s wrapped 
   * @param wrapperFactory Used to create wrappers of one particular {@link LeafReader}
   */
  public SolrCoreTestWrapper(SolrCore solrCore, WrapperFactory<LEAF_READER_WRAPPER> wrapperFactory) throws Exception {
    super(getSolrIndexSearcherAndStoreRefCountedInTL(solrCore.getSearcher()), wrapperFactory);
    searcher = refCountedSolrIndexSearcherTL.get();
  }

  private static ThreadLocal<RefCounted<SolrIndexSearcher>> refCountedSolrIndexSearcherTL = new ThreadLocal<RefCounted<SolrIndexSearcher>>(); 
  private static SolrIndexSearcher getSolrIndexSearcherAndStoreRefCountedInTL(RefCounted<SolrIndexSearcher> searcher) {
    refCountedSolrIndexSearcherTL.set(searcher);
    return searcher.get();
  }

  /**
   * @see SolrIndexSearcherTestWrapper#unwrap()
   */
  @Override
  public void unwrap() throws IllegalArgumentException, IllegalAccessException {
    super.unwrap();
    if (searcher != null) {
      searcher.decref();
    }
  }

}
