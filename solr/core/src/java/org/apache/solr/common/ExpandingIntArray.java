package org.apache.solr.common;

import java.util.ArrayList;

import org.apache.lucene.util.FixedBitSet;

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

public class ExpandingIntArray {
  
  private static final int DEFAULT_FIRST_ARRAY_SIZE = 10;
  
  private final int firstArraySize;

  private ArrayList<int[]> arrays = null;
  private int[] currentAddArray = null;
  private int indexForNextAddInCurrentAddArray = -1;
  private int size = 0;
  
  public ExpandingIntArray() {
    this(DEFAULT_FIRST_ARRAY_SIZE);
  }
  
  public ExpandingIntArray(int firstArraySize) {
    this.firstArraySize = firstArraySize;
    reset();
  }
  
  public void reset() {
    arrays = null;
    currentAddArray = null;
    indexForNextAddInCurrentAddArray = -1;
    size = 0;
  }
  
  public void add(int value) {
    add(size, value);
  }
  
  public void add(int index, int value) {
    if (index != size) throw new IllegalArgumentException("Appending only suppported");
    
    if (arrays == null) arrays = new ArrayList<int[]>(10);
    if (currentAddArray == null) {
      currentAddArray = new int[firstArraySize];
      arrays.add(currentAddArray);
      indexForNextAddInCurrentAddArray = 0;
    }
    if (indexForNextAddInCurrentAddArray >= currentAddArray.length) {
      currentAddArray = new int[currentAddArray.length*2];
      arrays.add(currentAddArray);
      indexForNextAddInCurrentAddArray = 0;
    }
    currentAddArray[indexForNextAddInCurrentAddArray++] = value;
    size++;
  }
  
  public void copyTo(FixedBitSet bits) {
    if (size > 0) {
      int resultPos = 0;
      for (int i = 0; i < arrays.size(); i++) {
        int[] srcArray = arrays.get(i);
        int intsToCopy = (i < (arrays.size()-1))?srcArray.length:indexForNextAddInCurrentAddArray;
        for (int j = 0; j < intsToCopy; j++) {
          bits.set(srcArray[j]);
        }
        resultPos += intsToCopy;
      }
      assert resultPos == size;
    }
  }
  
  public int[] toArray() {
    int[] result = new int[size];
    if (size > 0) {
      int resultPos = 0;
      for (int i = 0; i < arrays.size(); i++) {
        int[] srcArray = arrays.get(i);
        int intsToCopy = (i < (arrays.size()-1))?srcArray.length:indexForNextAddInCurrentAddArray;
        System.arraycopy(srcArray, 0, result, resultPos, intsToCopy);
        resultPos += intsToCopy;
      }
      assert resultPos == size;
    }
    return result;
  }
  
  public long get(int index) {
    if (index >= size) throw new IndexOutOfBoundsException("Index " + index + ", size " + size());
    
    int arrayIndex = index;
    for (int[] array : arrays) {
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