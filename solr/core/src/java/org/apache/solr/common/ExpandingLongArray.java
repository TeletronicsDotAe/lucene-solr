package org.apache.solr.common;

import java.util.ArrayList;

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

public class ExpandingLongArray {
  
  private static final int DEFAULT_FIRST_ARRAY_SIZE = 10;
  
  private final int firstArraySize;
  
  private ArrayList<long[]> arrays = null;
  private long[] currentAddArray = null;
  private int indexForNextAddInCurrentAddArray = -1;
  private int size = 0;
  
  public ExpandingLongArray() {
    this(DEFAULT_FIRST_ARRAY_SIZE);
  }
  
  public ExpandingLongArray(int firstArraySize) {
    this.firstArraySize = firstArraySize;
    reset();
  }
  
  public void reset() {
    arrays = null;
    currentAddArray = null;
    indexForNextAddInCurrentAddArray = -1;
    size = 0;
  }
  
  public void add(long value) {
    add(size, value);
  }
  
  public void add(int index, long value) {
    if (index != size) throw new IllegalArgumentException("Appending only suppported");
    
    if (arrays == null) arrays = new ArrayList<long[]>(10);
    if (currentAddArray == null) {
      currentAddArray = new long[firstArraySize];
      arrays.add(currentAddArray);
      indexForNextAddInCurrentAddArray = 0;
    }
    if (indexForNextAddInCurrentAddArray >= currentAddArray.length) {
      currentAddArray = new long[currentAddArray.length*2];
      arrays.add(currentAddArray);
      indexForNextAddInCurrentAddArray = 0;
    }
    currentAddArray[indexForNextAddInCurrentAddArray++] = value;
    size++;
  }
  
  public long get(int index) {
    if (index >= size) throw new IndexOutOfBoundsException("Index " + index + ", size " + size());
    
    int arrayIndex = index;
    for (long[] array : arrays) {
      if (arrayIndex >= array.length) {
        arrayIndex -= array.length;
      } else {
        return array[arrayIndex];
      }
    }
    
    throw new RuntimeException("Never supposed to get here. Something is inconsistent");
  }
  
  public int size() {
    return size;
  }
  
}
