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

public class FieldCacheContainer {
  
  private static FieldCacheContainer fcc = new FieldCacheContainer();
  public static FieldCacheContainer getTheFieldCacheContainer() {
    return fcc;
  }
  
  private FieldCache instance = new FieldCacheImpl();
  private boolean hasInstanceBeenRetrieved = false;
  
  public synchronized FieldCache getFieldCache() {
    hasInstanceBeenRetrieved = true;
    return instance;
  }
  
  /** @return original instance of the FieldCache */
  public synchronized FieldCache setFieldCache(final FieldCache newInstance) {
    final FieldCache orgInstance = instance;
    if (hasInstanceBeenRetrieved) {
      throw new UnsupportedOperationException("Cannot change field cache after it has been retrieved. You tried to set " + 
          newInstance + ", but it has already been set to " + instance);
    } else {
      instance = newInstance;
    }
    
    return orgInstance;
  }
  
}
