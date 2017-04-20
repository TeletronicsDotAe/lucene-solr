package org.apache.solr.update;

import java.util.Locale;

import org.apache.lucene.util.BytesRef;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.util.plugin.PluginInfoInitialized;

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

public class RecentlyLookedUpOrUpdatedDocumentsHandler {
  
  public static enum FoundLocation {
    ULog,
    Index,
    New
  }
  
  public static interface RecentlyLookedUpOrUpdatedDocuments {
    SolrInputDocument addDocument(UpdateLog ulog, BytesRef id, SolrInputDocument doc, FoundLocation fl);
    void deleteAll(UpdateLog ulog);
    Long getVersion(UpdateLog ulog, BytesRef id); 
    void addVersion(UpdateLog ulog, BytesRef id, Long version, FoundLocation fl);
    void deleteDocument(UpdateLog ulog, BytesRef id, Long version);
    SolrInputDocument getDocument(UpdateLog ulog, BytesRef id, boolean updateGetStats);
  }
  
  public static class RecentlyLookedUpOrUpdatedDocumentsDefImpl implements RecentlyLookedUpOrUpdatedDocuments {

    @Override
    public SolrInputDocument addDocument(UpdateLog ulog, BytesRef id, SolrInputDocument doc, FoundLocation fl) {
      return null;
    }

    @Override
    public void deleteAll(UpdateLog ulog) {}

    @Override
    public Long getVersion(UpdateLog ulog, BytesRef id) {
      return null;
    }

    @Override
    public void addVersion(UpdateLog ulog, BytesRef id, Long version, FoundLocation fl) {}

    @Override
    public void deleteDocument(UpdateLog ulog, BytesRef id, Long version) {}

    @Override
    public SolrInputDocument getDocument(UpdateLog ulog, BytesRef id, boolean updateGetStats) {
      return null;
    }
    
  }
  
  /**
   * Create and set a new RecentlyLookedUpOrUpdatedDocuments implementation
   * @param info    a PluginInfo object defining which type to create and its params. If null, nothing will be created and set
   * @param loader  a SolrResourceLoader used to find the ShardHandlerFactory classes
   */
  public static void setRecentlyLookedUpOrUpdatedDocuments(PluginInfo info, SolrResourceLoader loader) {
    if (info == null) return;

    try {
      RecentlyLookedUpOrUpdatedDocuments recentlyLookedUpOrUpdatedDocuments = loader.findClass(info.className, RecentlyLookedUpOrUpdatedDocuments.class).newInstance();
      if (PluginInfoInitialized.class.isAssignableFrom(recentlyLookedUpOrUpdatedDocuments.getClass()))
        PluginInfoInitialized.class.cast(recentlyLookedUpOrUpdatedDocuments).init(info);
      setRecentlyLookedUpOrUpdatedDocuments(recentlyLookedUpOrUpdatedDocuments);
    }
    catch (Exception e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
          String.format(Locale.ROOT, "Error instantiating RecentlyLookedUpOrUpdatedDocuments class [%s]: %s",
              info.className, e.getMessage()));
    }

  }

  public static void setRecentlyLookedUpOrUpdatedDocuments(RecentlyLookedUpOrUpdatedDocuments recentlyLookedUpOrUpdatedDocuments) {
    synchronized(RecentlyLookedUpOrUpdatedDocumentsHandler.class) {
      RecentlyLookedUpOrUpdatedDocumentsHandler.recentlyLookedUpOrUpdatedDocuments = recentlyLookedUpOrUpdatedDocuments;
    }
  }
  
  private static volatile RecentlyLookedUpOrUpdatedDocuments recentlyLookedUpOrUpdatedDocuments;
  // Kind of a global "singleton". Only one per JVM (classloader) so that we can control the global limit of the cache, instead
  // of e.g. only per UpdateLog
  public static RecentlyLookedUpOrUpdatedDocuments getRecentlyLookedUpOrUpdatedDocuments() {
    if (recentlyLookedUpOrUpdatedDocuments == null) {
      synchronized(RecentlyLookedUpOrUpdatedDocumentsHandler.class) {
        if (recentlyLookedUpOrUpdatedDocuments == null) {
          recentlyLookedUpOrUpdatedDocuments = new RecentlyLookedUpOrUpdatedDocumentsDefImpl();
        }
      }
    }
    return recentlyLookedUpOrUpdatedDocuments;
  }
  
}
