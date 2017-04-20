package org.apache.solr.search;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;

import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.StoredFieldVisitor;
import org.apache.lucene.util.Bits;
import org.apache.solr.SolrIndexSearcherTestWrapper;
import org.apache.solr.SolrIndexSearcherTestWrapper.WrapperFactory;

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
 * A collection of wrappers of {@link LeafReader} for test. Different wrappers can have different
 * purposes.
 */
public class LeafReaderTestWrappers {

  /**
   * Abstract wrapper that does the basic of wrapping. Every actual wrapper should 
   * inherit from BaseWrapper 
   */
  public static class BaseWrapper extends LeafReader {
    protected LeafReader wrapped;
    private Method wrappedDoCloseMethod;
    
    /**
     * @param toBeWrapped The {@link LeafReader} to be wrapper
     */
    public BaseWrapper(LeafReader toBeWrapped) {
      wrapped = toBeWrapped;
      Class<?> clazz = toBeWrapped.getClass();
      while (clazz != null) {
        try {
          wrappedDoCloseMethod = clazz.getDeclaredMethod("doClose");
          wrappedDoCloseMethod.setAccessible(true);
          return;
        } catch (NoSuchMethodException e) {
          clazz = clazz.getSuperclass();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
      throw new RuntimeException("doClose method not found");
    }
    
    @Override
    public void addCoreClosedListener(CoreClosedListener listener) {
      wrapped.addCoreClosedListener(listener);
    }

    @Override
    public void removeCoreClosedListener(CoreClosedListener listener) {
      wrapped.removeCoreClosedListener(listener);
    }
    
    @Override
    public Fields fields() throws IOException {
      return wrapped.fields();
    }
  
    @Override
    public NumericDocValues getNumericDocValues(String field) throws IOException {
      return wrapped.getNumericDocValues(field);
    }
  
    @Override
    public BinaryDocValues getBinaryDocValues(String field) throws IOException {
      return wrapped.getBinaryDocValues(field);
    }
  
    @Override
    public SortedDocValues getSortedDocValues(String field) throws IOException {
      return wrapped.getSortedDocValues(field);
    }
  
    @Override
    public SortedNumericDocValues getSortedNumericDocValues(String field) throws IOException {
      return wrapped.getSortedNumericDocValues(field);
    }

    @Override
    public SortedSetDocValues getSortedSetDocValues(String field) throws IOException {
      return wrapped.getSortedSetDocValues(field);
    }
    
    @Override
    public Bits getDocsWithField(String field) throws IOException {
      return wrapped.getDocsWithField(field);
    }

  
    @Override
    public NumericDocValues getNormValues(String field) throws IOException {
      return wrapped.getNormValues(field);
    }
  
    @Override
    public FieldInfos getFieldInfos() {
      return wrapped.getFieldInfos();
    }
  
    @Override
    public Bits getLiveDocs() {
      return wrapped.getLiveDocs();
    }
    
    @Override
    public void checkIntegrity() throws IOException {
      wrapped.checkIntegrity();
    }
  
    @Override
    public Fields getTermVectors(int docID) throws IOException {
      return wrapped.getTermVectors(docID);
    }
  
    @Override
    public int numDocs() {
      return wrapped.numDocs();
    }
  
    @Override
    public int maxDoc() {
      return wrapped.maxDoc();
    }
  
    @Override
    public void document(int docID, StoredFieldVisitor visitor) throws IOException {
      wrapped.document(docID, visitor);
    }
  
    @Override
    protected void doClose() {
      try {
        wrappedDoCloseMethod.invoke(wrapped);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

  }
  
  /**
   * Wrapper with the purpose of counting the number of calls to {@link LeafReader#document} and keep
   * track of the unique doc-ids provided to {@link LeafReader#document} calls
   */
  public static class CountingWrapper extends BaseWrapper {

    private int documentCalls = 0;
    private HashSet<Integer> ids = new HashSet<Integer>();

    /**
     * @see BaseWrapper
     */
    public CountingWrapper(LeafReader toBeWrapped) {
      super(toBeWrapped);
    }
    
    private static Factory factory = new Factory();
    private static class Factory implements WrapperFactory<CountingWrapper> {
      @Override
      public CountingWrapper createInstance(LeafReader leafReader) {
        return new CountingWrapper(leafReader);
      }
    }
    /**
     * @return A factory creating instances. For easy integration with {@link SolrIndexSearcherTestWrapper}
     * and {@SolrCoreTestWrapper}
     */
    public static Factory getFactory() {
      return factory;
    }
    
    @Override
    public void document(int docID, StoredFieldVisitor visitor) throws IOException {
      documentCalls++;
      ids.add(docID);
      wrapped.document(docID, visitor);
    }
    
    /**
     * @return The number of unique doc-ids that {@link LeafReader#document} of the wrapped
     * {@link LeafReader} has been called with since creation or last reset of this wrapper.
     * Resets the counter
     */
    public int getUniqueDocumentCountAndReset() {
      int result = ids.size();
      ids.clear();
      return result;
    }

    /**
     * @return The total number of calls to {@link LeafReader#document} of the wrapped
     * {@link LeafReader} since creation or last reset of this wrapper.
     * Resets the counter
     */
    public int getTotalDocumentCountAndReset() {
      int result = documentCalls;
      documentCalls = 0;
      return result;
    }

    /**
     * Sums the result of {@link #getUniqueDocumentCountAndReset} across a list
     * of CountingWrappers
     * @param cws The list of CountingWrapper to sum across
     * @return The sum
     */
    public static int getUniqueDocumentCountAndReset(List<CountingWrapper> cws)
    {
      int result = 0;      

      for (CountingWrapper cw : cws) {
        result += cw.getUniqueDocumentCountAndReset();
      }
      
      return result;
    }

    /**
     * Sums the result of {@link #getTotalDocumentCountAndReset} across a list
     * of CountingWrappers
     * @param cws The list of CountingWrapper to sum across
     * @return The sum
     */
    public static int getTotalDocumentCountAndReset(List<CountingWrapper> cws) {
      int result = 0;
      for (CountingWrapper cw : cws) {
        result += cw.getTotalDocumentCountAndReset();
      }
      return result;
    }
    
  }

}
