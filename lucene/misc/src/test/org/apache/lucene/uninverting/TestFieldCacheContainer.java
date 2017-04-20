package org.apache.lucene.uninverting;

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

import org.apache.lucene.uninverting.FieldCacheImpl;
import org.apache.lucene.util.LuceneTestCase;
import org.junit.Test;

public class TestFieldCacheContainer extends LuceneTestCase {

  private class MyFieldCache extends FieldCacheImpl {}
  
  @Test
  public void testTheRealFieldCache() {
    // You can get it and default it is an FieldCacheImpl
    assertEquals(FieldCacheImpl.class, FieldCache.DEFAULT.getClass());
    // Now that it has been retrieved, verify it cannot be set
    try {
      FieldCacheContainer.getTheFieldCacheContainer().setFieldCache(new FieldCacheImpl());
      fail();
    } catch (UnsupportedOperationException e) {
      // expected
    }
  }
  
  @Test
  public void testFieldCacheContainer() {
    // Since the real FieldCache-implementation is a static on FieldCache, we probably cannot changes
    // it by now, because numerous other tests has already fetched it - and it cant be changed after fetch
    // Therefore the set-part is tested on another FieldCacheContainer, than "the real" one
    FieldCache fc1 = new MyFieldCache();
    FieldCacheContainer fcc = new FieldCacheContainer();
    fcc.setFieldCache(fc1);
    // Retrieve the FieldCache
    assertEquals(fc1, fcc.getFieldCache());
    // Now that it has been retrieved, verify it cannot be set again
    try {
      fcc.setFieldCache(new FieldCacheImpl(){});
      fail();
    } catch (UnsupportedOperationException e) {
      // expected
    }
    
  }
  
}
